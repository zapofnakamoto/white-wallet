package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.protocol.RejectionKind

/**
 * Notes a mint says it doesn't know ([NoteState.Unknown]): a mint that lost its records, or misbehaves. One such note
 * must never stop the rest of the wallet, so a refused spend is sorted out note by note ([sortOut]) and the unknown ones
 * are set aside, out of the balance. They are never dropped: [recheckDue] asks again on refresh, and a note the mint
 * knows again is outstanding again.
 */
internal class UnknownNotes(private val wallet: Wallet) {
    private val prober = KeyProber(wallet)

    /**
     * After the mint refused to spend [notes] (all on one mint) as unknown or spent: looks each up by `p`, which never
     * discloses a spend. One the mint doesn't know is set aside as unknown, one it burned is spent. Returns their ids;
     * empty when every note is still there (the refusal had another reason) or the mint didn't answer.
     */
    suspend fun sortOut(notes: List<Note>): List<String> {
        val mint = notes.firstOrNull()?.let { wallet.store.mint(it.host) } ?: return emptyList()
        return notes.filter { note ->
            val id = KeyId(note.accountId, note.host, note.purpose ?: return@filter false, note.index ?: return@filter false)
            val probe = try {
                prober.probe(id, mint)
            } catch (e: MintKeyChangedException) {
                return emptyList()
            }
            probe is KeyProbe.Unused || probe is KeyProbe.Spent
        }.map { it.id }
    }

    /**
     * Asks again about [accountId]'s unknown notes that are due: each refresh for a day after it was set aside, at most
     * every [RECENT_INTERVAL_MILLIS], then once every [LATER_INTERVAL_MILLIS]. A mint that doesn't answer is left
     * for the next time.
     */
    suspend fun recheckDue(accountId: String) {
        val now = wallet.now()
        val due = wallet.store.notes(accountId).first().filter { note ->
            note.state == NoteState.Unknown && note.purpose != null && note.index != null && wallet.store.mint(note.host) != null &&
                wallet.unknownCheckedAt[note.id]?.let { now - it >= interval(now - note.updatedAtMillis) } != false
        }
        for (note in due) {
            wallet.unknownCheckedAt[note.id] = now
            try {
                prober.probe(KeyId(accountId, note.host, note.purpose!!, note.index!!), wallet.store.mint(note.host)!!)
            } catch (e: MintKeyChangedException) {
                // Nothing is asked of a mint with another key; the user decides first.
            }
        }
    }

    private fun interval(unknownForMillis: Long) = if (unknownForMillis < DAY_MILLIS) RECENT_INTERVAL_MILLIS else LATER_INTERVAL_MILLIS

    companion object {
        /** The refusals that may mean the mint doesn't know a note (a merge with an unknown input is refused as spent). */
        val GONE = setOf(RejectionKind.NoteUnknown, RejectionKind.Spent)

        private const val DAY_MILLIS = 24 * 60 * 60_000L
        private const val RECENT_INTERVAL_MILLIS = 5 * 60_000L
        private const val LATER_INTERVAL_MILLIS = DAY_MILLIS
    }
}
