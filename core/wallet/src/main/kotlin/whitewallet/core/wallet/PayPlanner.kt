package whitewallet.core.wallet

import whitewallet.core.model.Note

/**
 * How to turn an account's notes on one mint into one note worth exactly the amount to pay, under LUD-25's fee
 * rules: a split costs the mint's base fee, taken from the change (which must stay above zero); a merge of n
 * notes refunds (n - 1) base fees; a note used as is costs nothing.
 */
internal data class PayPlan(
    /** The notes to spend, largest first. */
    val inputs: List<Note>,
    val merge: Boolean,
    val split: Boolean,
    /** What the balance drops by beyond the amount; negative when merge refunds outweigh the split fee. */
    val feeMsat: Long,
)

internal object PayPlanner {
    /** The cheapest plan touching as few notes as possible, or null if no combination makes exactly [amountMsat]. */
    fun plan(notes: List<Note>, amountMsat: Long, baseFeeMsat: Long): PayPlan? {
        require(amountMsat > 0) { "amount must be positive" }
        val sorted = notes.sortedByDescending { it.amountMsat }
        sorted.firstOrNull { it.amountMsat == amountMsat }?.let { return PayPlan(listOf(it), merge = false, split = false, feeMsat = 0) }
        var sum = 0L
        for ((i, note) in sorted.withIndex()) {
            sum += note.amountMsat
            val inputs = sorted.subList(0, i + 1)
            val merged = sum + i * baseFeeMsat
            if (merged == amountMsat) return PayPlan(inputs, merge = i > 0, split = false, feeMsat = sum - amountMsat)
            val change = merged - amountMsat - baseFeeMsat
            if (change >= 1) return PayPlan(inputs, merge = i > 0, split = true, feeMsat = sum - change - amountMsat)
        }
        return null
    }

    /** The most these notes can pay in one payment: all of them merged. */
    fun max(notes: List<Note>, baseFeeMsat: Long): Long =
        if (notes.isEmpty()) 0 else notes.sumOf { it.amountMsat } + (notes.size - 1) * baseFeeMsat

    /**
     * The most that can be paid below [max] (one split needs the base fee plus at least 1 msat of change), so an
     * amount between this and [max] can't be made.
     */
    fun maxWithChange(notes: List<Note>, baseFeeMsat: Long): Long = (max(notes, baseFeeMsat) - baseFeeMsat - 1).coerceAtLeast(0)
}
