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
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteInfo
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

    /** Nothing was sent: the mint doesn't know the note (or burned it), so it was set aside (see [UnknownNotes]). */
    data object NoteGone : MeltOutcome
}

/**
 * LUD-25 melt of one of the wallet's own notes: journal, lock the note as [NoteState.Paying] (out of the balance), send once (never
 * retried), then wait a little for the mint to pay. [beforeSend] runs once the journal entry exists, before anything is sent (to write
 * History rows linked to it).
 */
internal class Melter(private val wallet: Wallet) {
    private val log = ActivityLog(wallet)
    private val unknownNotes = UnknownNotes(wallet)

    @OptIn(RevealsNoteSecret::class)
    suspend fun melt(
        accountId: String,
        host: String,
        note: Note,
        invoice: String,
        beforeSend: suspend (journalId: String) -> Unit = {},
    ): MeltOutcome =
        spend(accountId, host, note, MutationType.Melt, callback = null, beforeSend) { info, k1 ->
            Requests.melt(info.callback, k1, invoice)
        }

    /** Internal transfer (LUD-26): [note], whole, to the payee at their pay [callback] on the note's mint. It settles like a melt. */
    @OptIn(RevealsNoteSecret::class)
    suspend fun transfer(
        accountId: String,
        host: String,
        note: Note,
        callback: String,
        beforeSend: suspend (journalId: String) -> Unit = {},
    ): MeltOutcome =
        spend(accountId, host, note, MutationType.Transfer, callback, beforeSend) { _, k1 ->
            Requests.transfer(callback, note.amountMsat, k1)
        }

    private suspend fun spend(
        accountId: String,
        host: String,
        note: Note,
        type: MutationType,
        callback: String?,
        beforeSend: suspend (journalId: String) -> Unit,
        request: (NoteInfo, String) -> String,
    ): MeltOutcome {
        val mint = wallet.mintOf(accountId, host) ?: return MeltOutcome.Unreachable
        val spend = wallet.keys(accountId, host).spend(note, spendDomain(host))
        val info =
            try {
                // Asked by the note's key alone: the spend goes out only with the melt.
                wallet.client.lookup(mint.withdrawUrl, ByteVector32(note.id)).also { Pinning.check(wallet, mint, it.mintPubkey) }
            } catch (e: NoteLookupError) {
                if (e.kind in UnknownNotes.GONE && unknownNotes.sortOut(listOf(note)).isNotEmpty()) return MeltOutcome.NoteGone
                return MeltOutcome.Unreachable
            } catch (e: Exception) {
                // Including a signing key other than the pinned one: nothing is paid through such a mint.
                return MeltOutcome.Unreachable
            }
        val created = wallet.now()
        // Written into the request and the journal; it goes out only through SecretGate.
        @OptIn(RevealsNoteSecret::class)
        val entry =
            JournalEntry(
                id = UUID.randomUUID().toString(),
                accountId,
                type = OperationType.Melt,
                host,
                mint.withdrawUrl,
                inputIds = listOf(note.id),
                inputK1s = listOf(spend.k1),
                outputs = emptyList(),
                requestUrl = request(info, spend.k1),
                status = JournalStatus.Prepared,
                detail = null,
                createdAtMillis = created,
                updatedAtMillis = created,
            )
        wallet.store.putJournal(entry)
        wallet.store.upsertNotes(listOf(note.copy(state = NoteState.Paying, updatedAtMillis = created)))
        beforeSend(entry.id)

        val outcome =
            when (val released = SecretGate(wallet).release(entry, info, type, callback ?: info.callback)) {
                is SecretGate.Released.Sent -> released.outcome
                is SecretGate.Released.NotSent -> {
                    wallet.store.upsertNotes(listOf(note.copy(state = NoteState.Outstanding, updatedAtMillis = wallet.now())))
                    wallet.store.putJournal(
                        entry.copy(status = JournalStatus.Rejected, detail = released.reason, updatedAtMillis = wallet.now())
                    )
                    log.settle(entry.id, JournalStatus.Rejected)
                    return MeltOutcome.Unreachable
                }
            }
        val inFlight =
            when (outcome) {
                is MutationOutcome.Confirmed,
                is MutationOutcome.Unverifiable ->
                    entry.copy(status = JournalStatus.Unknown, detail = MeltReconciler.IN_FLIGHT, updatedAtMillis = wallet.now())
                is MutationOutcome.Rejected -> {
                    wallet.store.upsertNotes(listOf(note.copy(state = NoteState.Outstanding, updatedAtMillis = wallet.now())))
                    wallet.store.putJournal(
                        entry.copy(status = JournalStatus.Rejected, detail = outcome.reason, updatedAtMillis = wallet.now())
                    )
                    log.settle(entry.id, JournalStatus.Rejected)
                    return MeltOutcome.Refused(entry.id, outcome.reason)
                }
                is MutationOutcome.Ambiguous ->
                    entry.copy(status = JournalStatus.Unknown, detail = outcome.cause, updatedAtMillis = wallet.now())
            }
        wallet.store.putJournal(inFlight)

        // The mint pays in the background; the note is burned once the payment settles, or restored if it fails.
        val melts = MeltReconciler(wallet)
        return waitForPayment(wallet) { final ->
            // Unknown leaves the entry as stored, so inFlight stays current until it is settled.
            when (melts.reconcile(inFlight, final)) {
                JournalStatus.Confirmed -> MeltOutcome.Paid(entry.id)
                JournalStatus.Rejected -> MeltOutcome.PaymentFailed(entry.id)
                else -> null
            }
        } ?: MeltOutcome.Pending(entry.id)
    }
}

/**
 * Asks [check] how a payment stands until it says ([check] answers null while it can't), waiting twice as long each time from
 * [WalletSettings.paymentStatusFirstWaitMillis] up to [WalletSettings.paymentStatusLongestWaitMillis], for at most [Wallet.payWaitMillis].
 * [check] is told once the time is up ([final]). Null if it never said.
 */
internal suspend fun <T : Any> waitForPayment(wallet: Wallet, check: suspend (final: Boolean) -> T?): T? {
    var wait = wallet.settings.paymentStatusFirstWaitMillis
    val deadline = wallet.now() + wallet.payWaitMillis
    while (true) {
        check(wallet.now() >= deadline)?.let {
            return it
        }
        if (wallet.now() >= deadline) return null
        delay(wait)
        wait = (wait * 2).coerceAtMost(wallet.settings.paymentStatusLongestWaitMillis)
    }
}

/**
 * Settles a melt by looking its input up by `p`: burned means paid; outstanding again means the payment failed (or, when the request's
 * answer was lost, that it never arrived); `pending` means it is still in flight.
 */
internal class MeltReconciler(private val wallet: Wallet) {
    private val log = ActivityLog(wallet)

    /** [final]: give up waiting for an answer that was lost, if the note is still outstanding. */
    suspend fun reconcile(entry: JournalEntry, final: Boolean = false): JournalStatus {
        if (entry.status == JournalStatus.Confirmed || entry.status == JournalStatus.Rejected) return entry.status
        val q = ByteVector32(entry.inputIds.single())
        val outstanding =
            try {
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
        if (answered || final || wallet.now() - entry.createdAtMillis > wallet.settings.paymentLostAnswerWaitMillis) {
            note?.let { wallet.store.upsertNotes(listOf(it.copy(state = NoteState.Outstanding, updatedAtMillis = wallet.now()))) }
            return finish(entry, JournalStatus.Rejected)
        }
        return JournalStatus.Unknown
    }

    private suspend fun finish(entry: JournalEntry, status: JournalStatus): JournalStatus {
        wallet.store.putJournal(entry.copy(status = status, updatedAtMillis = wallet.now()))
        log.settle(entry.id, status)
        return status
    }

    companion object {
        /** Journal detail of a melt the mint accepted (its payment is in flight). */
        const val IN_FLIGHT = "in flight"
    }
}
