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
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain

/** An output key reserved for a mutation, with its journal reference. */
internal class ReservedOutput(val key: ByteVector32, val ref: OutputRef)

/** Where an output goes. */
internal sealed interface OutputSpec {
    /** A key of one of this wallet's accounts, on its wallet or change chain; the key itself is reserved when the request is built. */
    data class Own(val accountId: String, val set: KeySet) : OutputSpec

    /**
     * Someone else's key (an internal transfer, LUD-26): [keyAt] gives the key for each attempt, since on `already in use` the next index
     * of the payee's branch is tried.
     */
    class External(val keyAt: (attempt: Int) -> ByteVector32) : OutputSpec
}

internal sealed interface MutationResult {
    /** Landed; [outputs] are the recorded output notes, in request order. */
    data class Done(val outputs: List<Note>, val journalId: String) : MutationResult

    /**
     * Processed and refused by the mint. Nothing changed at the mint; inputs it doesn't know (or burned) were found and [setAside] (see
     * [UnknownNotes]), so the rest can be spent without them.
     */
    data class Refused(val kind: RejectionKind, val reason: String?, val setAside: List<String> = emptyList()) : MutationResult

    /**
     * The mint could not be asked (unknown mint or no answer before anything was sent), or wasn't because it shows another signing key than
     * the pinned one ([keyChanged]). Nothing changed.
     */
    data class Unreachable(val reason: String?, val keyChanged: Boolean = false) : MutationResult

    /** Sent, outcome unknown; the journal entry [journalId] is settled by reconciliation on a later refresh. */
    data class Pending(val journalId: String) : MutationResult
}

/**
 * The one safe path for spending key-path notes in a rotate, merge or split: the wallet's own ([mutate]) or one handed over by a link
 * ([rotateIn]). Reserve outputs, journal before disclosing anything, send without transport retries, record the outcome, and reconcile when
 * the answer is lost.
 */
internal class NoteMutator(private val wallet: Wallet) {
    private val guard = IndexGuard(wallet)
    private val unknownNotes = UnknownNotes(wallet)
    private val reconciler = Reconciler(wallet)

    /** [inputs] as [outputs] of the same worth: renews a note, or hands it to a key of someone else. */
    suspend fun rotate(fromId: String, host: String, inputs: List<Note>, outputs: List<OutputSpec>): MutationResult =
        mutate(fromId, host, inputs, OperationType.Rotate, outputs)

    /** [inputs] as [outputs], the first worth [splitMsat] and the second the change. */
    suspend fun split(fromId: String, host: String, inputs: List<Note>, outputs: List<OutputSpec>, splitMsat: Long): MutationResult =
        mutate(fromId, host, inputs, OperationType.Split, outputs, splitMsat)

    /**
     * Journals and sends one mutation of [fromId]'s [inputs], then records its outputs (in [outputs] order): a rotate or merge into one
     * output, or a split, whose first output gets [splitMsat]. If the mint says an output key is `already in use`, the mutation is retried
     * with fresh output keys, up to [WalletSettings.newNoteMaxKeysTriedWhileInUse] keys in all; after the first refusal the index is
     * rediscovered.
     */
    private suspend fun mutate(
        fromId: String,
        host: String,
        inputs: List<Note>,
        type: OperationType,
        outputs: List<OutputSpec>,
        splitMsat: Long? = null,
    ): MutationResult {
        val mint = wallet.mintOf(fromId, host) ?: return MutationResult.Unreachable("unknown host $host")
        val domain = spendDomain(host)
        val keys = wallet.keys(fromId, host)
        val spends = inputs.map { note -> keys.spend(note, domain) }
        if (SimulatedFailure.take(SimulatedFailure.Kind.Unreachable)) return MutationResult.Unreachable("simulated: no answer")
        // Asked by the first note's key alone (LUD-25 "Checking a note without exposing it"): spends go out only with the mutation.
        val info = runCatching {
            wallet.client.lookup(mint.withdrawUrl, ByteVector32(inputs.first().id))
        }
            .getOrElse {
                // The mint won't say what the note is worth: it may not know it. That is about the notes, not the mint.
                if (it is NoteLookupError && it.kind in UnknownNotes.GONE) return refused(inputs, it.kind, it.reason)
                return MutationResult.Unreachable(it.message)
            }
        // Nothing goes out to a mint that signs with another key than the pinned one (see Pinning).
        try {
            Pinning.check(wallet, mint, info.mintPubkey)
        } catch (e: MintKeyChangedException) {
            return MutationResult.Unreachable(e.message, keyChanged = true)
        } catch (e: ProtocolViolation) {
            return MutationResult.Unreachable(e.message)
        }
        val result =
            run(fromId, host, mint.withdrawUrl, info, spends, inputs, inputMsat = inputs.sumOf { it.amountMsat }, type, outputs, splitMsat)
        return if (result is MutationResult.Refused && result.kind in UnknownNotes.GONE) refused(inputs, result.kind, result.reason)
        else result
    }

    /**
     * [inputs] as one note at [target], in as many merges as a request's limit asks for: only the last goes to [target], so if a merge
     * before it fails the notes merged so far are still [fromId]'s, on its own keys.
     */
    suspend fun merge(fromId: String, host: String, inputs: List<Note>, target: OutputSpec): MutationResult {
        val max = wallet.settings.noteMergeMaxNotesPerRequest
        var notes = inputs
        while (notes.size > max) {
            val own = listOf(OutputSpec.Own(fromId, KeySet.Wallet))
            val merged = mutate(fromId, host, notes.take(max), OperationType.Merge, own)
            if (merged !is MutationResult.Done) return merged
            notes = merged.outputs + notes.drop(max)
        }
        return mutate(fromId, host, notes, OperationType.Merge, listOf(target))
    }

    /** A refusal that may be about some of [inputs]: those the mint doesn't know (or burned) are set aside. */
    private suspend fun refused(inputs: List<Note>, kind: RejectionKind, reason: String?): MutationResult.Refused =
        MutationResult.Refused(kind, reason, unknownNotes.sortOut(inputs))

    /**
     * Rotates a note handed over by a link ([spend], worth [amountMsat] as [info] says) into a fresh wallet key of [accountId]. The mint's
     * key was checked against the pinned one when [info] was looked up.
     */
    suspend fun rotateIn(
        accountId: String,
        host: String,
        withdrawUrl: String,
        info: NoteInfo,
        spend: Spend,
        amountMsat: Long,
    ): MutationResult =
        run(
            fromId = accountId,
            host,
            withdrawUrl,
            info,
            spends = listOf(spend),
            inputs = emptyList(),
            inputMsat = amountMsat,
            type = OperationType.Rotate,
            outputs = listOf(OutputSpec.Own(accountId, KeySet.Wallet)),
            splitMsat = null,
        )

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
        splitMsat: Long?,
    ): MutationResult {
        var attempt = 0
        while (true) {
            val reserved = outputs.map { spec ->
                when (spec) {
                    is OutputSpec.Own -> guard.reserve(spec.accountId, host, spec.set)
                    is OutputSpec.External -> spec.keyAt(attempt).let { ReservedOutput(it, OutputRef.external(it.toHex())) }
                }
            }
            val result =
                send(
                    fromId,
                    host,
                    withdrawUrl,
                    inputs,
                    spends,
                    inputMsat,
                    type,
                    outputs = reserved,
                    info,
                    url = request(type, info.callback, spends, outputs = reserved, splitMsat),
                )
            if (
                result is MutationResult.Refused &&
                    result.kind == RejectionKind.AlreadyInUse &&
                    attempt + 1 < wallet.settings.newNoteMaxKeysTriedWhileInUse
            ) {
                if (attempt == 0)
                    outputs.filterIsInstance<OutputSpec.Own>().distinct().forEach { guard.recover(it.accountId, host, it.set) }
                attempt++
                continue
            }
            return result
        }
    }

    /** The mutation's request: the one place the wallet's spends are written into a URL (sent only by [SecretGate]). */
    @OptIn(RevealsNoteSecret::class)
    private fun request(
        type: OperationType,
        callback: String,
        spends: List<Spend>,
        outputs: List<ReservedOutput>,
        splitMsat: Long?,
    ): String {
        val k1s = spends.map(Spend::k1)
        return when (type) {
            OperationType.Rotate -> Requests.rotate(callback, k1s.single(), outputs.single().key)
            OperationType.Merge -> Requests.merge(callback, k1s, outputs.single().key)
            OperationType.Split ->
                Requests.split(callback, k1s, checkNotNull(splitMsat) { "a split needs its amount" }, outputs[0].key, outputs[1].key)
            OperationType.Melt,
            OperationType.Mint -> error("$type is not sent through NoteMutator")
        }
    }

    @OptIn(RevealsNoteSecret::class) // journals the spends, to reclaim a received note if the mutation didn't land
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
        val entry =
            JournalEntry(
                id = UUID.randomUUID().toString(),
                accountId = fromId,
                type,
                host,
                withdrawUrl,
                inputIds = spends.map { it.q.toHex() },
                inputK1s = spends.map(Spend::k1),
                outputs = outputs.map { it.ref },
                requestUrl = url,
                status = JournalStatus.Prepared,
                detail = null,
                createdAtMillis = created,
                updatedAtMillis = created,
            )
        wallet.store.putJournal(entry)

        val mutation =
            when (type) {
                OperationType.Rotate -> MutationType.Rotate
                OperationType.Merge -> MutationType.Merge
                OperationType.Split -> MutationType.Split
                OperationType.Melt,
                OperationType.Mint -> error("${type} is not sent through NoteMutator")
            }
        var lost = false
        val outcome =
            when {
                SimulatedFailure.take(SimulatedFailure.Kind.Refused) -> MutationOutcome.Rejected(RejectionKind.Other, "simulated: refused")
                else ->
                    when (val released = SecretGate(wallet).release(entry, info, mutation)) {
                        is SecretGate.Released.NotSent -> {
                            wallet.store.putJournal(
                                entry.copy(status = JournalStatus.Rejected, detail = released.reason, updatedAtMillis = wallet.now())
                            )
                            return MutationResult.Unreachable(released.reason)
                        }
                        is SecretGate.Released.Sent ->
                            if (SimulatedFailure.take(SimulatedFailure.Kind.AnswerLost)) {
                                lost = true
                                MutationOutcome.Ambiguous("simulated: answer lost")
                            } else {
                                released.outcome
                            }
                    }
            }
        return when (outcome) {
            is MutationOutcome.Confirmed,
            is MutationOutcome.Unverifiable -> {
                val (c, c2) =
                    when (outcome) {
                        is MutationOutcome.Confirmed -> outcome.certificate to outcome.changeCertificate
                        is MutationOutcome.Unverifiable -> outcome.certificate to outcome.changeCertificate
                    }
                val notes = outputs.mapIndexed { i, out ->
                    val cert = (if (i == 0) c else c2)?.let(Cs1::decode)?.takeIf { certifies(it, host, out.key, info.mintPubkey) }
                    // Without a certificate, fall back to what the inputs imply (fees may make this approximate).
                    val amount = cert?.amountMsat ?: if (outputs.size == 1) inputMsat else 0L
                    Note(
                        id = out.ref.q,
                        out.ref.accountId,
                        host,
                        kind = NoteKind.KeyPath,
                        out.ref.purpose,
                        out.ref.index,
                        amountMsat = amount,
                        certificate = cert?.encode(),
                        state = NoteState.Outstanding,
                        updatedAtMillis = wallet.now(),
                        scheme = out.ref.scheme,
                        share = out.ref.share,
                    )
                }
                // Someone else's outputs are reported to the caller but never stored as this wallet's notes.
                val ours = notes.filterIndexed { i, _ -> !outputs[i].ref.isExternal }
                wallet.store.upsertNotes(ours + inputs.map { it.copy(state = NoteState.Spent, updatedAtMillis = wallet.now()) })
                wallet.store.putJournal(entry.copy(status = JournalStatus.Confirmed, updatedAtMillis = wallet.now()))
                MutationResult.Done(notes, entry.id)
            }
            is MutationOutcome.Rejected -> {
                wallet.store.putJournal(
                    entry.copy(status = JournalStatus.Rejected, detail = outcome.reason, updatedAtMillis = wallet.now())
                )
                MutationResult.Refused(outcome.kind, outcome.reason)
            }
            is MutationOutcome.Ambiguous -> {
                val unknown = entry.copy(status = JournalStatus.Unknown, detail = outcome.cause, updatedAtMillis = wallet.now())
                wallet.store.putJournal(unknown)
                // A simulated lost answer can't be asked about right away either: it stays open until a refresh.
                when (if (lost) JournalStatus.Unknown else reconciler.reconcile(unknown)) {
                    JournalStatus.Confirmed ->
                        MutationResult.Done(
                            outputs.map { out ->
                                wallet.store.note(out.ref.q)
                                    ?: Note(
                                        id = out.ref.q,
                                        accountId = "",
                                        host,
                                        kind = NoteKind.KeyPath,
                                        out.ref.purpose,
                                        index = null,
                                        amountMsat = 0,
                                        certificate = null,
                                        state = NoteState.Outstanding,
                                        updatedAtMillis = wallet.now(),
                                        scheme = out.ref.scheme,
                                        share = out.ref.share,
                                    )
                            },
                            entry.id,
                        )
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
