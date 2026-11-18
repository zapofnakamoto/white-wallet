package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation

/**
 * Settles mutations whose outcome is unknown by looking up their outputs and inputs by `p`, which never discloses a spend. An output of
 * ours that exists means the mutation landed; inputs that are all still outstanding mean it did not. A mutation with no output left to look
 * up (they were on the older keys, which version 9 of the database dropped): only this wallet can spend its own inputs, so inputs that are
 * all burned (`already in use`) mean it landed. A received note's input can be spent by whoever made its link: gone, with our output
 * missing, it never landed.
 */
internal class Reconciler(private val wallet: Wallet) {
    private val log = ActivityLog(wallet)
    private val melts = MeltReconciler(wallet)
    private val mints = MintReconciler(wallet)

    /**
     * Settles the open operations of the accounts [accountIds]: those of the active wallet. One on a mint whose signing key doesn't match
     * stays open, waiting for an answer, until the key matches again or the user accepts the new one: most answers here carry no key.
     */
    suspend fun reconcileAll(accountIds: Set<String>) {
        for (entry in wallet.store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown)).filter { it.accountId in accountIds }) {
            if (!wallet.keyMatches(wallet.walletOf(entry.accountId), entry.host)) continue
            when (entry.type) {
                OperationType.Melt -> melts.reconcile(entry)
                OperationType.Mint -> mints.reconcile(entry)
                else -> reconcile(entry)
            }
        }
    }

    /** Returns the entry's new status. */
    suspend fun reconcile(entry: JournalEntry): JournalStatus {
        try {
            var landed = false
            for (output in entry.outputs) {
                val q = ByteVector32(output.q)
                val info = lookup(entry, q).info ?: continue
                landed = true
                val cert = info.certificate?.takeIf { certifies(it, entry.host, q, wallet.pinnedKey(output.accountId, entry.host)) }
                if (wallet.store.note(output.q) == null) {
                    wallet.store.upsertNotes(
                        listOf(
                            Note(
                                id = output.q,
                                output.accountId,
                                entry.host,
                                kind = NoteKind.KeyPath,
                                output.purpose,
                                output.index,
                                amountMsat = info.maxWithdrawableMsat,
                                certificate = cert?.encode(),
                                state = NoteState.Outstanding,
                                updatedAtMillis = wallet.now(),
                                share = output.share,
                            )
                        )
                    )
                }
            }
            if (!landed) {
                val inputs = entry.inputIds.map { lookup(entry, ByteVector32(it)) }
                val live = inputs.map { it.info != null }
                if (live.all { it }) {
                    // It never landed: the inputs are spendable again.
                    for (id in entry.inputIds) {
                        wallet.store
                            .note(id)
                            ?.takeIf { it.state == NoteState.Ambiguous }
                            ?.let {
                                wallet.store.upsertNotes(listOf(it.copy(state = NoteState.Outstanding, updatedAtMillis = wallet.now())))
                            }
                    }
                    return finish(entry, JournalStatus.Rejected)
                }
                // A note received as a link: whoever made the link could spend it too, so its input gone and our
                // output missing means someone else claimed it first. It never landed.
                if (live.none { it } && entry.inputIds.any { wallet.store.receivedNote(it) != null })
                    return finish(entry, JournalStatus.Rejected)
                // Burned, not merely gone in the mint's own words, which prove nothing.
                landed = inputs.all { it.burned } && entry.outputs.isEmpty()
            }
            if (landed) {
                for (id in entry.inputIds) wallet.store.note(id)?.let {
                    wallet.store.upsertNotes(listOf(it.copy(state = NoteState.Spent, updatedAtMillis = wallet.now())))
                }
                return finish(entry, JournalStatus.Confirmed)
            }
        } catch (e: IOException) {
            // Still unknown; try again on the next refresh.
        } catch (e: ProtocolViolation) {
            // Same.
        } catch (e: MintKeyChangedException) {
            // An answer from a mint whose key doesn't match settles nothing: open until it matches, or the user accepts the new key.
        }
        if (entry.status == JournalStatus.Prepared)
            wallet.store.putJournal(entry.copy(status = JournalStatus.Unknown, updatedAtMillis = wallet.now()))
        return JournalStatus.Unknown
    }

    private suspend fun finish(entry: JournalEntry, status: JournalStatus): JournalStatus {
        wallet.store.putJournal(entry.copy(status = status, updatedAtMillis = wallet.now()))
        log.settle(entry.id, status)
        return status
    }

    /** What the mint says of a note: its [info], or none if it doesn't hold it, [burned] if it said `already in use`. */
    private class Lookup(val info: NoteInfo?, val burned: Boolean = false)

    /**
     * The note's info, or none if the mint says it is burned, unknown or spent. Other refusals are rethrown as IOException; an answer with
     * another key than the pinned one as [MintKeyChangedException].
     */
    private suspend fun lookup(entry: JournalEntry, q: ByteVector32): Lookup =
        try {
            Lookup(Pinning.settling(wallet, entry.accountId, entry.host, entry.withdrawUrl, q))
        } catch (e: NoteLookupError) {
            when {
                // Answered 0: an input of a rotate, split or merge is never being paid out (a melt holds its note as Paying).
                e.burned || e.burnedOrPending -> Lookup(null, burned = true)
                e.kind in UnknownNotes.GONE -> Lookup(null)
                else -> throw IOException(e.reason)
            }
        }
}
