package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain

internal sealed interface MeltOutcome {
    data class Paid(val journalId: String) : MeltOutcome

    /** The mint tried and the payment failed; the note is outstanding again. */
    data class PaymentFailed(val journalId: String) : MeltOutcome

    /** The mint refused the melt; nothing was paid. */
    data class Refused(val journalId: String, val reason: String?) : MeltOutcome

    /** In flight past the wait; reconciliation settles it on a later refresh. */
    data class Pending(val journalId: String) : MeltOutcome

    /** Nothing was sent. */
    data object Unreachable : MeltOutcome
}

/**
 * LUD-25 melt of one of the wallet's own notes: journal, lock the note as [NoteState.Paying] (out of the balance),
 * send once (never retried), then wait a little for the mint to pay. [beforeSend] runs once the journal entry
 * exists, before anything is sent (to write History rows linked to it).
 */
internal class Melter(private val wallet: Wallet) {
    suspend fun melt(accountId: String, host: String, note: Note, invoice: String, beforeSend: suspend (journalId: String) -> Unit = {}): MeltOutcome {
        val mint = wallet.store.mint(host) ?: return MeltOutcome.Unreachable
        val spend = wallet.branch(accountId, host).spend(NotePurpose.entries.first { it.value == note.purpose }, note.index!!, spendDomain(host))
        val info = try {
            wallet.client.noteInfo(mint.withdrawUrl, spend.k1).also { Pinning.check(wallet, mint, it.mintPubkey) }
        } catch (e: Exception) {
            // Including a signing key other than the pinned one: nothing is paid through such a mint.
            return MeltOutcome.Unreachable
        }
        val url = Requests.melt(info.callback, spend.k1, invoice)
        val created = wallet.now()
        val entry = JournalEntry(UUID.randomUUID().toString(), accountId, OperationType.Melt, host, mint.withdrawUrl, listOf(note.id),
            listOf(spend.k1), emptyList(), url, JournalStatus.Prepared, null, created, created)
        wallet.store.putJournal(entry)
        wallet.store.upsertNotes(listOf(note.copy(state = NoteState.Paying, updatedAtMillis = created)))
        beforeSend(entry.id)

        val inFlight = when (val outcome = wallet.client.mutate(MutationType.Melt, url)) {
            is MutationOutcome.Confirmed, is MutationOutcome.Unverifiable ->
                entry.copy(status = JournalStatus.Unknown, detail = MeltReconciler.IN_FLIGHT, updatedAtMillis = wallet.now())
            is MutationOutcome.Rejected -> {
                wallet.store.upsertNotes(listOf(note.copy(state = NoteState.Outstanding, updatedAtMillis = wallet.now())))
                wallet.store.putJournal(entry.copy(status = JournalStatus.Rejected, detail = outcome.reason, updatedAtMillis = wallet.now()))
                ActivityLog(wallet).settle(entry.id, JournalStatus.Rejected)
                return MeltOutcome.Refused(entry.id, outcome.reason)
            }
            is MutationOutcome.Ambiguous -> entry.copy(status = JournalStatus.Unknown, detail = outcome.cause, updatedAtMillis = wallet.now())
        }
        wallet.store.putJournal(inFlight)

        // The mint pays in the background; the note is burned once the payment settles, or restored if it fails.
        val melts = MeltReconciler(wallet)
        var wait = POLL_START_MILLIS
        val deadline = wallet.now() + wallet.payWaitMillis
        while (true) {
            // Unknown leaves the entry as stored, so inFlight stays current until it is settled.
            when (melts.reconcile(inFlight, final = wallet.now() >= deadline)) {
                JournalStatus.Confirmed -> return MeltOutcome.Paid(entry.id)
                JournalStatus.Rejected -> return MeltOutcome.PaymentFailed(entry.id)
                else -> if (wallet.now() >= deadline) return MeltOutcome.Pending(entry.id)
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(POLL_MAX_MILLIS)
        }
    }

    private companion object {
        const val POLL_START_MILLIS = 250L
        const val POLL_MAX_MILLIS = 3_000L
    }
}

/**
 * Settles a melt by looking its input up by `p`: burned means paid; outstanding again means the payment failed
 * (or, when the request's answer was lost, that it never arrived); `pending` means it is still in flight.
 */
internal class MeltReconciler(private val wallet: Wallet) {
    /** [final]: give up waiting for an answer that was lost, if the note is still outstanding. */
    suspend fun reconcile(entry: JournalEntry, final: Boolean = false): JournalStatus {
        if (entry.status == JournalStatus.Confirmed || entry.status == JournalStatus.Rejected) return entry.status
        val q = ByteVector32(entry.inputIds.single())
        val outstanding = try {
            wallet.client.lookup(entry.withdrawUrl, q)
            true
        } catch (e: NoteLookupError) {
            when (e.kind) {
                RejectionKind.Spent -> false
                else -> return JournalStatus.Unknown
            }
        } catch (e: IOException) {
            return JournalStatus.Unknown
        } catch (e: ProtocolViolation) {
            return JournalStatus.Unknown
        }
        val note = wallet.store.note(entry.inputIds.single())
        if (!outstanding) {
            note?.let { wallet.store.upsertNotes(listOf(it.copy(state = NoteState.Spent, updatedAtMillis = wallet.now()))) }
            return finish(entry, JournalStatus.Confirmed)
        }
        // The mint said OK, so it marked the note pending before answering: outstanding again means it failed.
        // Without that answer the request may still be on its way, so wait before calling it failed.
        val answered = entry.detail == IN_FLIGHT
        if (answered || final || wallet.now() - entry.createdAtMillis > LOST_ANSWER_GRACE_MILLIS) {
            note?.let { wallet.store.upsertNotes(listOf(it.copy(state = NoteState.Outstanding, updatedAtMillis = wallet.now()))) }
            return finish(entry, JournalStatus.Rejected)
        }
        return JournalStatus.Unknown
    }

    private suspend fun finish(entry: JournalEntry, status: JournalStatus): JournalStatus {
        wallet.store.putJournal(entry.copy(status = status, updatedAtMillis = wallet.now()))
        ActivityLog(wallet).settle(entry.id, status)
        return status
    }

    companion object {
        /** Journal detail of a melt the mint accepted (its payment is in flight). */
        const val IN_FLIGHT = "in flight"
        private const val LOST_ANSWER_GRACE_MILLIS = 120_000L
    }
}
