package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import whitewallet.brand.NoteStep
import whitewallet.brand.NoteStrategy
import whitewallet.brand.MintNotes
import whitewallet.brand.StrategyNote

class NoteStrategiesTest {
    private class Named(override val id: String) : NoteStrategy {
        override val title = 0
        override val description = 0
        override fun plan(mint: MintNotes): List<NoteStep> = emptyList()
    }

    private val all = listOf(SingleNoteStrategy(), ManualStrategy(), Named("keep-small"))

    @Test
    fun `offers the configured strategies in the brand's order`() {
        val strategies = NoteStrategies(all, listOf("keep-small", "single"), "single")
        assertEquals(listOf("keep-small", "single"), strategies.offered.map { it.id })
        assertEquals("single", strategies.default.id)
    }

    @Test
    fun `an account's choice applies only while it is offered`() {
        val strategies = NoteStrategies(all, listOf("single", "keep-small"), "keep-small")
        assertEquals("single", strategies.resolve("single").id)
        assertEquals("keep-small", strategies.resolve("manual").id, "no longer offered")
        assertEquals("keep-small", strategies.resolve(null).id, "never chosen")
    }

    @Test
    fun `no list offers just the default`() {
        assertEquals(listOf("manual"), NoteStrategies(all, emptyList(), "manual").offered.map { it.id })
    }

    @Test
    fun `a misconfigured brand fails with the problem named`() {
        assertTrue("keep-big" in assertFailsWith<IllegalStateException> { NoteStrategies(all, listOf("keep-big"), "keep-big") }.message!!)
        assertTrue("defaultNoteStrategy" in assertFailsWith<IllegalStateException> { NoteStrategies(all, listOf("single"), "manual") }.message!!)
        assertFailsWith<IllegalStateException> { NoteStrategies(all + Named("single"), listOf("single"), "single") }
        assertFailsWith<IllegalStateException> { NoteStrategies(all + Named("Keep Big"), listOf("single"), "single") }
    }

    @Test
    fun `keep only one note combines, then rotates a lone incoming note`() {
        val single = SingleNoteStrategy()
        val a = StrategyNote("a", 1_000, onAddressKey = true, updatedAtMillis = 2)
        val b = StrategyNote("b", 2_000, onAddressKey = false, updatedAtMillis = 1)
        assertEquals(listOf(NoteStep.Combine(listOf("b", "a"))), single.plan(MintNotes("h", listOf(a, b), 0)))
        assertEquals(listOf(NoteStep.Rotate("a")), single.plan(MintNotes("h", listOf(a), 0)))
        assertEquals(emptyList(), single.plan(MintNotes("h", listOf(b), 0)))
        assertEquals(emptyList(), ManualStrategy().plan(MintNotes("h", listOf(a, b), 0)))
    }
}
