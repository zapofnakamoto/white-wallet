package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState

/**
 * What changing the active account's home mint to [toHost] would do with its money on other mints (see
 * [Wallet.previewChangeHome]), for the user to confirm first: per mint, whether it moves and what that costs, or why it
 * stays. Quotes, not promises: when the move runs, the fee cap decides again.
 */
data class HomeChangePreview(
    val toHost: String,
    /** One per mint (other than [toHost]) holding the account's money, by host. */
    val lines: List<Line>,
    /** The money moves by itself: the account collects at its home mint (brand `collectAtHomeMint` `on` or `always`). */
    val collects: Boolean,
    /** The account doesn't collect, but the user may have the money moved this once (`collectAtHomeMint=off`). */
    val canMove: Boolean,
    val feeCapPercent: Int,
) {
    data class Line(val host: String, val amountMsat: Long, val outcome: Outcome)

    sealed interface Outcome {
        /** Within the fee cap: about [arrivesMsat] arrives, for [feeMsat]. */
        data class Moves(val arrivesMsat: Long, val feeMsat: Long) : Outcome

        /** Moving would cost [feeMsat], more than the fee cap. */
        data class TooExpensive(val feeMsat: Long) : Outcome

        /** Worth less than the new home takes ([minMsat]) or than its fees. */
        data class TooSmall(val minMsat: Long?) : Outcome

        /** No quote now ([problem]); collecting asks again later. */
        data class Unknown(val problem: NoteProblem) : Outcome

        /** Not moved: the brand doesn't collect at the home mint (`collectAtHomeMint=hidden`). */
        data object Stays : Outcome
    }

    /** What would leave the other mints, and the fees for it. */
    val movingMsat: Long get() = lines.filter { it.outcome is Outcome.Moves }.sumOf { it.amountMsat }
    val feeMsat: Long get() = lines.sumOf { (it.outcome as? Outcome.Moves)?.feeMsat ?: 0 }
}

/** Builds [HomeChangePreview]s: the account's notes per other mint, each quoted like collecting would (see [NoteManager.quoteMove]). */
internal class HomeChange(private val wallet: Wallet) {
    suspend fun preview(accountId: String, toHost: String, collects: Boolean, canMove: Boolean, feeCapPercent: Int): HomeChangePreview {
        // The notes collecting can move: outstanding, with a key of this account.
        val elsewhere = wallet.store.notes(accountId).first()
            .filter { it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.purpose != null && it.index != null && it.host != toHost }
            .groupBy { it.host }
        val lines = elsewhere.toSortedMap().map { (host, notes) ->
            val total = notes.sumOf { it.amountMsat }
            val outcome = if (!collects && !canMove) HomeChangePreview.Outcome.Stays else quote(notes.map { it.id }, toHost, total, feeCapPercent)
            HomeChangePreview.Line(host, total, outcome)
        }
        return HomeChangePreview(toHost, lines, collects, canMove, feeCapPercent)
    }

    /** The same rule as collecting: the fee (what leaves minus what arrives) at most [capPercent] of what leaves. */
    private suspend fun quote(ids: List<String>, toHost: String, total: Long, capPercent: Int): HomeChangePreview.Outcome =
        when (val q = NoteManager(wallet).quoteMove(ids, toHost)) {
            is ServiceMoveQuote.Ready -> {
                val fee = total - q.expectedMsat
                if (fee * 100 <= capPercent.toLong() * total) HomeChangePreview.Outcome.Moves(q.expectedMsat, fee) else HomeChangePreview.Outcome.TooExpensive(fee)
            }
            is ServiceMoveQuote.Invalid ->
                if (q.problem == NoteProblem.BelowMinimum) HomeChangePreview.Outcome.TooSmall(q.limitMsat) else HomeChangePreview.Outcome.Unknown(q.problem)
        }
}
