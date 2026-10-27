package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState

/**
 * What moving the active account's money to [toHost] would do (see [Wallet.previewMove]), for the user to confirm
 * first: per mint holding its money, what arrives and the fee, or why it can't move. Quotes, not promises: a move goes
 * ahead only at the fee the user confirmed or less (see [Wallet.moveMoney]).
 */
data class MovePreview(
    val toHost: String,
    /** One per mint (other than [toHost]) holding the account's money, by host. */
    val lines: List<Line>,
    /** The account's fee cap for collecting at its home mint, in percent: what moves by itself, see [Line.overCap]. */
    val feeCapPercent: Int,
) {
    data class Line(val host: String, val amountMsat: Long, val quote: Quote) {
        /** Collecting at the home mint would leave this money where it is: the fee is over [capPercent] of it. */
        fun overCap(capPercent: Int): Boolean = quote is Quote.Ok && quote.feeMsat * 100 > capPercent.toLong() * amountMsat
    }

    sealed interface Quote {
        /** About [arrivesMsat] arrives, for [feeMsat] (what leaves minus what arrives). */
        data class Ok(val arrivesMsat: Long, val feeMsat: Long) : Quote

        /** Worth less than the target takes ([minMsat]) or than its fees. */
        data class TooSmall(val minMsat: Long?) : Quote

        /** No quote now ([problem]): the mint or the target didn't answer, or refused. */
        data class Unavailable(val problem: NoteProblem) : Quote
    }
}

/** What [Wallet.moveMoney] did with one mint's money ([amountMsat] of it): moved, still settling, or not moved and why. */
data class MoneyMoved(val host: String, val amountMsat: Long, val result: ServiceMoveResult)

/**
 * Moves the account's money between mints at a fee the user saw: quotes per mint ([preview]), then moves each mint's
 * notes, combined into one, over Lightning ([move], see [NoteManager.move]).
 */
internal class MoneyMove(private val wallet: Wallet) {
    suspend fun preview(accountId: String, toHost: String, fromHost: String?, feeCapPercent: Int): MovePreview {
        val lines = movable(accountId).filter { it.host != toHost && (fromHost == null || it.host == fromHost) }.groupBy { it.host }.toSortedMap()
            .map { (host, notes) ->
                val total = notes.sumOf { it.amountMsat }
                MovePreview.Line(host, total, quote(notes.map { it.id }, toHost, total))
            }
        return MovePreview(toHost, lines, feeCapPercent)
    }

    /**
     * Moves each of [lines] (quoted [MovePreview.Quote.Ok]) to [toHost], with the mint's notes as they are now: only if
     * it costs at most the fee the user confirmed; otherwise nothing is sent ([NoteProblem.Declined]).
     */
    suspend fun move(accountId: String, toHost: String, lines: List<MovePreview.Line>): List<MoneyMoved> = lines.map { line ->
        val confirmed = line.quote as? MovePreview.Quote.Ok ?: return@map MoneyMoved(line.host, line.amountMsat, ServiceMoveResult.Failed(NoteProblem.Declined))
        val notes = movable(accountId).filter { it.host == line.host }
        if (notes.isEmpty()) return@map MoneyMoved(line.host, line.amountMsat, ServiceMoveResult.Failed(NoteProblem.NotFound))
        val result = NoteManager(wallet).move(accountId, notes.map { it.id }, toHost, automatic = false) { quote, notesMsat ->
            notesMsat - quote.expectedMsat <= confirmed.feeMsat
        }
        MoneyMoved(line.host, notes.sumOf { it.amountMsat }, result)
    }

    private suspend fun quote(ids: List<String>, toHost: String, total: Long): MovePreview.Quote =
        when (val q = NoteManager(wallet).quoteMove(ids, toHost)) {
            is ServiceMoveQuote.Ready -> MovePreview.Quote.Ok(q.expectedMsat, total - q.expectedMsat)
            is ServiceMoveQuote.Invalid ->
                if (q.problem == NoteProblem.BelowMinimum) MovePreview.Quote.TooSmall(q.limitMsat) else MovePreview.Quote.Unavailable(q.problem)
        }

    /** The notes a move can take: outstanding, with a key of this account (as collecting takes them). */
    private suspend fun movable(accountId: String): List<Note> = wallet.store.notes(accountId).first()
        .filter { it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.purpose != null && it.index != null }
}
