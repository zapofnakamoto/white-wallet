package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind

/**
 * Settles mutations whose outcome is unknown by looking up their outputs and inputs by `p`, which never discloses
 * a spend. An output of ours that exists means the mutation landed; inputs that are all still outstanding mean it did
 * not. Someone else's output (an internal transfer) proves nothing: another payer's note may sit at that key. Only
 * this wallet can spend the inputs, so for such a mutation inputs that are all gone mean it landed.
 */
internal class Reconciler(private val wallet: Wallet) {
    suspend fun reconcileAll() {
        for (entry in wallet.store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown))) {
            when (entry.type) {
                OperationType.Melt -> MeltReconciler(wallet).reconcile(entry)
                OperationType.Mint -> MintReconciler(wallet).reconcile(entry)
                else -> reconcile(entry)
            }
        }
    }

    /** Returns the entry's new status. */
    suspend fun reconcile(entry: JournalEntry): JournalStatus {
        try {
            var landed = false
            for (output in entry.outputs) {
                if (output.isExternal) continue
                val q = ByteVector32(output.q)
                val info = lookup(entry.withdrawUrl, q) ?: continue
                landed = true
                val cert = info.certificate?.takeIf { Certificates.verify(it, q, info.mintPubkey) }
                if (wallet.store.note(output.q) == null) {
                    wallet.store.upsertNotes(listOf(Note(output.q, output.accountId, entry.host, NoteKind.KeyPath, output.purpose, output.index,
                        info.maxWithdrawableMsat, cert?.encode(), NoteState.Outstanding, wallet.now())))
                }
            }
            if (!landed) {
                val live = entry.inputIds.map { lookup(entry.withdrawUrl, ByteVector32(it)) != null }
                if (live.all { it }) return finish(entry, JournalStatus.Rejected)
                // The payee may have moved the note on already, so its key can't tell; the burned inputs can.
                landed = live.none { it } && entry.outputs.any { it.isExternal }
            }
            if (landed) {
                for (id in entry.inputIds) wallet.store.note(id)?.let { wallet.store.upsertNotes(listOf(it.copy(state = NoteState.Spent, updatedAtMillis = wallet.now()))) }
                return finish(entry, JournalStatus.Confirmed)
            }
        } catch (e: IOException) {
            // Still unknown; try again on the next refresh.
        } catch (e: ProtocolViolation) {
            // Same.
        }
        if (entry.status == JournalStatus.Prepared) wallet.store.putJournal(entry.copy(status = JournalStatus.Unknown, updatedAtMillis = wallet.now()))
        return JournalStatus.Unknown
    }

    private suspend fun finish(entry: JournalEntry, status: JournalStatus): JournalStatus {
        wallet.store.putJournal(entry.copy(status = status, updatedAtMillis = wallet.now()))
        ActivityLog(wallet).settle(entry.id, status)
        return status
    }

    /** The note's info, or null if the service says it is unknown or spent. Other refusals are rethrown as IOException. */
    private suspend fun lookup(withdrawUrl: String, q: ByteVector32): NoteInfo? = try {
        wallet.client.lookup(withdrawUrl, q)
    } catch (e: NoteLookupError) {
        when (e.kind) {
            RejectionKind.Spent, RejectionKind.NoteUnknown -> null
            else -> throw IOException(e.reason)
        }
    }
}
