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
import whitewallet.lnurlcash.protocol.RejectionKind
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

    /** Processed and refused by the service. Nothing changed. */
    data class Refused(val kind: RejectionKind, val reason: String?) : MutationResult

    /** The service could not be asked (unknown service or no answer before anything was sent). Nothing changed. */
    data class Unreachable(val reason: String?) : MutationResult

    /** Sent, outcome unknown; the journal entry [journalId] is settled by reconciliation on a later refresh. */
    data class Pending(val journalId: String) : MutationResult
}

/**
 * The one safe path for spending the wallet's own key-path notes in a rotate, merge or split:
 * reserve outputs, journal before disclosing anything, send without transport retries, record the outcome, and
 * reconcile when the answer is lost.
 */
internal class NoteMutator(private val wallet: Wallet) {
    private val guard = IndexGuard(wallet)

    /**
     * Journals and sends one mutation of [fromId]'s [inputs], then records its outputs (in [outputs] order).
     * If the service says an output key is `already in use`, the local index was behind: the index is rediscovered
     * and the mutation retried with fresh output keys, up to [IN_USE_RETRIES] times.
     */
    suspend fun mutate(
        fromId: String,
        host: String,
        inputs: List<Note>,
        type: OperationType,
        outputs: List<OutputSpec>,
        buildUrl: (callback: String, k1s: List<String>, outputs: List<ReservedOutput>) -> String,
    ): MutationResult {
        val service = wallet.store.service(host) ?: return MutationResult.Unreachable("unknown host $host")
        val domain = spendDomain(host)
        val branch = wallet.branch(fromId, host)
        val spends = inputs.map { note -> branch.spend(NotePurpose.entries.first { it.value == note.purpose }, note.index!!, domain) }
        val info = runCatching { wallet.client.noteInfo(service.withdrawUrl, spends.first().k1) }.getOrElse {
            return MutationResult.Unreachable(it.message)
        }
        // Nothing goes out to a mint that signs with another key than the pinned one (see Pinning).
        try {
            Pinning.check(wallet, service, info.mintPubkey)
        } catch (e: MintKeyChangedException) {
            return MutationResult.Unreachable(e.message)
        }
        var attempt = 0
        while (true) {
            val reserved = outputs.map { spec ->
                when (spec) {
                    is OutputSpec.Own -> guard.reserve(spec.accountId, host, spec.purpose)
                    is OutputSpec.External -> spec.keyAt(attempt).let { ReservedOutput(it, OutputRef.external(it.toHex())) }
                }
            }
            val result = send(fromId, host, service.withdrawUrl, inputs, spends, type, reserved, info, buildUrl(info.callback, spends.map(Spend::k1), reserved))
            if (result is MutationResult.Refused && result.kind == RejectionKind.AlreadyInUse && attempt < IN_USE_RETRIES) {
                attempt++
                outputs.filterIsInstance<OutputSpec.Own>().distinct().forEach { guard.recover(it.accountId, host, it.purpose) }
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
        spends: List<Spend.KeyPath>,
        type: OperationType,
        outputs: List<ReservedOutput>,
        info: NoteInfo,
        url: String,
    ): MutationResult {
        val created = wallet.now()
        val entry = JournalEntry(UUID.randomUUID().toString(), fromId, type, host, withdrawUrl, inputs.map { it.id },
            spends.map(Spend::k1), outputs.map { it.ref }, url, JournalStatus.Prepared, null, created, created)
        wallet.store.putJournal(entry)

        val mutation = when (type) {
            OperationType.Rotate -> MutationType.Rotate
            OperationType.Merge -> MutationType.Merge
            OperationType.Split -> MutationType.Split
            OperationType.Melt, OperationType.Mint -> error("${type} is not sent through NoteMutator")
        }
        return when (val outcome = wallet.client.mutate(mutation, url)) {
            is MutationOutcome.Confirmed, is MutationOutcome.Unverifiable -> {
                val (c, c2) = when (outcome) {
                    is MutationOutcome.Confirmed -> outcome.certificate to outcome.changeCertificate
                    is MutationOutcome.Unverifiable -> outcome.certificate to outcome.changeCertificate
                    else -> error("unreachable")
                }
                val total = inputs.sumOf { it.amountMsat }
                val notes = outputs.mapIndexed { i, out ->
                    val cert = (if (i == 0) c else c2)?.let(Cs1::decode)?.takeIf { Certificates.verify(it, out.key, info.mintPubkey) }
                    // Without a certificate, fall back to what the inputs imply (fees may make this approximate).
                    val amount = cert?.amountMsat ?: if (outputs.size == 1) total else 0L
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
                when (Reconciler(wallet).reconcile(unknown)) {
                    JournalStatus.Confirmed -> MutationResult.Done(outputs.map { out ->
                        wallet.store.note(out.ref.q) ?: Note(out.ref.q, "", host, NoteKind.KeyPath, out.ref.purpose, null, 0, null, NoteState.Outstanding, wallet.now())
                    }, entry.id)
                    // Settled already: it never landed, so nothing changed, as for a refusal.
                    JournalStatus.Rejected -> MutationResult.Refused(RejectionKind.Other, outcome.cause)
                    else -> MutationResult.Pending(entry.id)
                }
            }
        }
    }

    private companion object {
        const val IN_USE_RETRIES = 2
    }
}
