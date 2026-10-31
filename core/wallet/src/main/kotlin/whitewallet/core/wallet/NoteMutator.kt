package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.util.UUID
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain

/** An output key reserved for a mutation, with its journal reference. */
internal class ReservedOutput(val key: ByteVector32, val ref: OutputRef)

/** Where an output goes. */
internal sealed interface OutputSpec {
    /** A key of one of this wallet's accounts; the key itself is reserved when the request is built. */
    data class Own(val accountId: String, val purpose: NotePurpose) : OutputSpec

    /**
     * Someone else's key (an internal transfer, LUD-26): [keyAt] gives the key for each attempt, since on
     * `already in use` the next index of the payee's branch is tried.
     */
    class External(val keyAt: (attempt: Int) -> ByteVector32) : OutputSpec
}

internal sealed interface MutationResult {
    /** Landed; [outputs] are the recorded output notes, in request order. */
    data class Done(val outputs: List<Note>, val journalId: String) : MutationResult

    /**
     * Processed and refused by the mint. Nothing changed at the mint; inputs it doesn't know (or burned) were found and
     * [setAside] (see [UnknownNotes]), so the rest can be spent without them.
     */
    data class Refused(val kind: RejectionKind, val reason: String?, val setAside: List<String> = emptyList()) : MutationResult

    /** The mint could not be asked (unknown mint or no answer before anything was sent). Nothing changed. */
    data class Unreachable(val reason: String?) : MutationResult

    /** Sent, outcome unknown; the journal entry [journalId] is settled by reconciliation on a later refresh. */
    data class Pending(val journalId: String) : MutationResult
}

/**
 * The one safe path for spending key-path notes in a rotate, merge or split: the wallet's own ([mutate]) or one
 * handed over by a link ([rotateIn]). Reserve outputs, journal before disclosing anything, send without transport
 * retries, record the outcome, and reconcile when the answer is lost.
 */
internal class NoteMutator(private val wallet: Wallet) {
    private val guard = IndexGuard(wallet)

    /**
     * Journals and sends one mutation of [fromId]'s [inputs], then records its outputs (in [outputs] order).
     * If the mint says an output key is `already in use`, the mutation is retried with fresh output keys, up to
     * [IndexGuard.IN_USE_ATTEMPTS] keys in all; after the first refusal the index is rediscovered.
     */
    suspend fun mutate(
        fromId: String,
        host: String,
        inputs: List<Note>,
        type: OperationType,
        outputs: List<OutputSpec>,
        buildUrl: (callback: String, k1s: List<String>, outputs: List<ReservedOutput>) -> String,
    ): MutationResult {
        val mint = wallet.store.mint(host) ?: return MutationResult.Unreachable("unknown host $host")
        val domain = spendDomain(host)
        val branch = wallet.branch(fromId, host)
        val spends = inputs.map { note -> branch.spend(NotePurpose.entries.first { it.value == note.purpose }, note.index!!, domain) }
        if (SimulatedFailure.take(SimulatedFailure.Kind.Unreachable)) return MutationResult.Unreachable("simulated: no answer")
        val info = runCatching { wallet.client.noteInfo(mint.withdrawUrl, spends.first().k1) }.getOrElse {
            // The mint won't say what the note is worth: it may not know it. That is about the notes, not the mint.
            if (it is NoteLookupError && it.kind in UnknownNotes.GONE) return refused(inputs, it.kind, it.reason)
            return MutationResult.Unreachable(it.message)
        }
        // Nothing goes out to a mint that signs with another key than the pinned one (see Pinning).
        try {
            Pinning.check(wallet, mint, info.mintPubkey)
        } catch (e: MintKeyChangedException) {
            return MutationResult.Unreachable(e.message)
        }
        val result = run(fromId, host, mint.withdrawUrl, info, spends, inputs, inputs.sumOf { it.amountMsat }, type, outputs, buildUrl)
        return if (result is MutationResult.Refused && result.kind in UnknownNotes.GONE) refused(inputs, result.kind, result.reason) else result
    }

    /** A refusal that may be about some of [inputs]: those the mint doesn't know (or burned) are set aside. */
    private suspend fun refused(inputs: List<Note>, kind: RejectionKind, reason: String?): MutationResult.Refused =
        MutationResult.Refused(kind, reason, UnknownNotes(wallet).sortOut(inputs))

    /**
     * Rotates a note handed over by a link ([spend], worth [amountMsat] as [info] says) into a fresh wallet key of
     * [accountId]. The note may be one of this app's, sent as a link ([sent]): it is spent with it. The mint's key
     * was checked against the pinned one when [info] was looked up.
     */
    suspend fun rotateIn(accountId: String, host: String, withdrawUrl: String, info: NoteInfo, spend: Spend, amountMsat: Long, sent: Note?): MutationResult =
        run(accountId, host, withdrawUrl, info, listOf(spend), listOfNotNull(sent), amountMsat, OperationType.Rotate,
            listOf(OutputSpec.Own(accountId, NotePurpose.Wallet))) { callback, k1s, outs -> Requests.rotate(callback, k1s.single(), outs.single().key) }

    /** Sends the mutation, with fresh output keys while the mint says one is `already in use`. */
    private suspend fun run(
        fromId: String,
        host: String,
        withdrawUrl: String,
        info: NoteInfo,
        spends: List<Spend>,
        inputs: List<Note>,
        inputMsat: Long,
        type: OperationType,
        outputs: List<OutputSpec>,
        buildUrl: (callback: String, k1s: List<String>, outputs: List<ReservedOutput>) -> String,
    ): MutationResult {
        var attempt = 0
        while (true) {
            val reserved = outputs.map { spec ->
                when (spec) {
                    is OutputSpec.Own -> guard.reserve(spec.accountId, host, spec.purpose)
                    is OutputSpec.External -> spec.keyAt(attempt).let { ReservedOutput(it, OutputRef.external(it.toHex())) }
                }
            }
            val result = send(fromId, host, withdrawUrl, inputs, spends, inputMsat, type, reserved, info, buildUrl(info.callback, spends.map(Spend::k1), reserved))
            if (result is MutationResult.Refused && result.kind == RejectionKind.AlreadyInUse && attempt + 1 < IndexGuard.IN_USE_ATTEMPTS) {
                if (attempt == 0) outputs.filterIsInstance<OutputSpec.Own>().distinct().forEach { guard.recover(it.accountId, host, it.purpose) }
                attempt++
                continue
            }
            return result
        }
    }

    private suspend fun send(
        fromId: String,
        host: String,
        withdrawUrl: String,
        inputs: List<Note>,
        spends: List<Spend>,
        inputMsat: Long,
        type: OperationType,
        outputs: List<ReservedOutput>,
        info: NoteInfo,
        url: String,
    ): MutationResult {
        val created = wallet.now()
        val entry = JournalEntry(UUID.randomUUID().toString(), fromId, type, host, withdrawUrl, spends.map { it.q.toHex() },
            spends.map(Spend::k1), outputs.map { it.ref }, url, JournalStatus.Prepared, null, created, created)
        wallet.store.putJournal(entry)

        val mutation = when (type) {
            OperationType.Rotate -> MutationType.Rotate
            OperationType.Merge -> MutationType.Merge
            OperationType.Split -> MutationType.Split
            OperationType.Melt, OperationType.Mint -> error("${type} is not sent through NoteMutator")
        }
        var lost = false
        val outcome = when {
            SimulatedFailure.take(SimulatedFailure.Kind.Refused) -> MutationOutcome.Rejected(RejectionKind.Other, "simulated: refused")
            SimulatedFailure.take(SimulatedFailure.Kind.AnswerLost) -> {
                wallet.client.mutate(mutation, url)
                lost = true
                MutationOutcome.Ambiguous("simulated: answer lost")
            }
            else -> wallet.client.mutate(mutation, url)
        }
        return when (outcome) {
            is MutationOutcome.Confirmed, is MutationOutcome.Unverifiable -> {
                val (c, c2) = when (outcome) {
                    is MutationOutcome.Confirmed -> outcome.certificate to outcome.changeCertificate
                    is MutationOutcome.Unverifiable -> outcome.certificate to outcome.changeCertificate
                }
                val notes = outputs.mapIndexed { i, out ->
                    val cert = (if (i == 0) c else c2)?.let(Cs1::decode)?.takeIf { Certificates.verify(it, out.key, info.mintPubkey) }
                    // Without a certificate, fall back to what the inputs imply (fees may make this approximate).
                    val amount = cert?.amountMsat ?: if (outputs.size == 1) inputMsat else 0L
                    Note(out.ref.q, out.ref.accountId, host, NoteKind.KeyPath, out.ref.purpose, out.ref.index, amount, cert?.encode(), NoteState.Outstanding, wallet.now())
                }
                // Someone else's outputs are reported to the caller but never stored as this wallet's notes.
                val ours = notes.filterIndexed { i, _ -> !outputs[i].ref.isExternal }
                wallet.store.upsertNotes(ours + inputs.map { it.copy(state = NoteState.Spent, updatedAtMillis = wallet.now()) })
                wallet.store.putJournal(entry.copy(status = JournalStatus.Confirmed, updatedAtMillis = wallet.now()))
                MutationResult.Done(notes, entry.id)
            }
            is MutationOutcome.Rejected -> {
                wallet.store.putJournal(entry.copy(status = JournalStatus.Rejected, detail = outcome.reason, updatedAtMillis = wallet.now()))
                MutationResult.Refused(outcome.kind, outcome.reason)
            }
            is MutationOutcome.Ambiguous -> {
                val unknown = entry.copy(status = JournalStatus.Unknown, detail = outcome.cause, updatedAtMillis = wallet.now())
                wallet.store.putJournal(unknown)
                // A simulated lost answer can't be asked about right away either: it stays open until a refresh.
                when (if (lost) JournalStatus.Unknown else Reconciler(wallet).reconcile(unknown)) {
                    JournalStatus.Confirmed -> MutationResult.Done(outputs.map { out ->
                        wallet.store.note(out.ref.q) ?: Note(out.ref.q, "", host, NoteKind.KeyPath, out.ref.purpose, null, 0, null, NoteState.Outstanding, wallet.now())
                    }, entry.id)
                    // Settled already: it never landed, so nothing changed, as for a refusal.
                    JournalStatus.Rejected -> MutationResult.Refused(RejectionKind.Other, outcome.cause)
                    // The inputs may be burned already: out of the balance and never picked again until settled.
                    else -> {
                        wallet.store.upsertNotes(inputs.map { it.copy(state = NoteState.Ambiguous, updatedAtMillis = wallet.now()) })
                        MutationResult.Pending(entry.id)
                    }
                }
            }
        }
    }
}
