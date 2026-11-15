package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.RejectionKind

/**
 * Notes a mint says it doesn't know ([NoteState.Unknown]): a mint that lost its records, or misbehaves. One such note must never stop the
 * rest of the wallet, so a refused spend is sorted out note by note ([sortOut]) and the unknown ones are set aside, out of the balance.
 * They are never dropped: [recheckDue] asks again on refresh, and a note the mint knows again is outstanding again.
 */
internal class UnknownNotes(private val wallet: Wallet) {
    private val prober = KeyProber(wallet)

    /**
     * After the mint refused to spend [notes] (all on one mint) as unknown or spent: looks each up by `p`, which never discloses a spend.
     * One the mint doesn't know is set aside as unknown, one it burned (the lookup says `already in use`) is spent. Returns their ids;
     * empty when every note is still there (the refusal had another reason), the mint didn't answer, or answered in other words.
     */
    suspend fun sortOut(notes: List<Note>): List<String> {
        val mint = notes.firstOrNull()?.let { wallet.mintOf(it.accountId, it.host) } ?: return emptyList()
        return notes
            .filter { note ->
                if (note.purpose == null) return@filter false
                val id = KeySet.of(note).key(note.accountId, note.host, note.index ?: return@filter false)
                val probe =
                    try {
                        prober.probe(id, mint)
                    } catch (e: MintKeyChangedException) {
                        return emptyList()
                    }
                probe is KeyProbe.Unused || probe is KeyProbe.Spent
            }
            .map { it.id }
    }

    /**
     * Asks again about [accountId]'s unknown notes that are due: during a note's grace period
     * ([WalletSettings.unknownNoteGracePeriodMillis] from when it was set aside) at most every
     * [WalletSettings.unknownNoteCheckIntervalInGracePeriodMillis], after it once every
     * [WalletSettings.unknownNoteCheckIntervalAfterGracePeriodMillis]. A mint that doesn't answer is left for the next time.
     */
    suspend fun recheckDue(accountId: String) {
        val now = wallet.now()
        val due =
            wallet.store.notes(accountId).first().filter { note ->
                note.state == NoteState.Unknown &&
                    note.purpose != null &&
                    note.index != null &&
                    wallet.mintOf(accountId, note.host) != null &&
                    wallet.unknownCheckedAt[note.id]?.let { now - it >= interval(now - note.updatedAtMillis) } != false
            }
        for (note in due) {
            wallet.unknownCheckedAt[note.id] = now
            try {
                val probe = prober.probe(KeySet.of(note).key(accountId, note.host, note.index!!), wallet.mintOf(accountId, note.host)!!)
                if (probe is KeyProbe.Unused) wallet.unknownAnsweredAt.update { it + (note.id to now) }
            } catch (e: MintKeyChangedException) {
                // Nothing is asked of a mint with another key; the user decides first.
            }
        }
    }

    private fun interval(unknownForMillis: Long) =
        if (unknownForMillis < wallet.settings.unknownNoteGracePeriodMillis) wallet.settings.unknownNoteCheckIntervalInGracePeriodMillis
        else wallet.settings.unknownNoteCheckIntervalAfterGracePeriodMillis

    companion object {
        /**
         * The refusals that may mean the mint doesn't hold a note (a merge with an unknown input is refused as spent): one mint's wording,
         * which a lookup by `p` settles ([sortOut]), never proof.
         */
        val GONE = setOf(RejectionKind.NoteUnknown, RejectionKind.Spent)

        /** A lookup's refusal that may mean the mint doesn't hold the note: burned (proof), or in words that [sortOut] settles. */
        fun mayBeGone(e: NoteLookupError): Boolean = e.burned || e.kind in GONE
    }
}
