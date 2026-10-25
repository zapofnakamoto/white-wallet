package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState

class PayPlannerTest {
    private fun notes(vararg sats: Long) = sats.mapIndexed { i, s ->
        Note("q$i", "a", "h", NoteKind.KeyPath, 0, i.toLong(), s * 1000, null, NoteState.Outstanding, 0)
    }
    private val fee = 1_000L

    @Test
    fun `a note worth exactly the amount is used as is, for free`() {
        val plan = assertNotNull(PayPlanner.plan(notes(500, 21), 21_000, fee))
        assertEquals(listOf("q1"), plan.inputs.map { it.id })
        assertEquals(PayPlan(plan.inputs, merge = false, split = false, feeMsat = 0), plan)
    }

    @Test
    fun `one bigger note is split, and the base fee comes out of the change`() {
        val plan = assertNotNull(PayPlanner.plan(notes(500), 100_000, fee))
        assertEquals(true, plan.split)
        assertEquals(false, plan.merge)
        assertEquals(1_000, plan.feeMsat)
    }

    @Test
    fun `notes are merged when no single one is enough, and the merge refund offsets the split fee`() {
        val plan = assertNotNull(PayPlanner.plan(notes(300, 200, 100), 450_000, fee))
        assertEquals(listOf("q0", "q1"), plan.inputs.map { it.id }, "largest first, as few as needed")
        assertEquals(true, plan.merge)
        assertEquals(true, plan.split)
        assertEquals(0, plan.feeMsat, "one refund, one split fee")
    }

    @Test
    fun `everything can be paid exactly by merging, but not one sat less`() {
        val all = notes(300, 200)
        assertEquals(501_000, PayPlanner.max(all, fee), "the merge refunds one base fee")
        val exact = assertNotNull(PayPlanner.plan(all, 501_000, fee))
        assertEquals(PayPlan(exact.inputs, merge = true, split = false, feeMsat = -1_000), exact)
        assertNull(PayPlanner.plan(all, 500_500, fee), "the change wouldn't cover the split fee")
        assertNotNull(PayPlanner.plan(all, PayPlanner.maxWithChange(all, fee), fee))
        assertNull(PayPlanner.plan(all, 502_000, fee))
    }

    @Test
    fun `without fees any amount up to the balance works`() {
        assertNotNull(PayPlanner.plan(notes(10), 9_999, 0))
        assertNull(PayPlanner.plan(notes(10), 10_001, 0))
    }
}
