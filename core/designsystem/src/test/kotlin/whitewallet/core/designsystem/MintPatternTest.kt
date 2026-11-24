package whitewallet.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import whitewallet.brand.ui.BillBorder
import whitewallet.brand.ui.MintPattern
import whitewallet.brand.ui.SealMotif
import whitewallet.brand.ui.SealShape

class MintPatternTest {
    @Test
    fun `a mint keeps its pattern across releases and phones, as in the approved mockup`() {
        assertEquals(
            MintPattern(BillBorder.Dots, 2, 0.375f, 20f, SealShape.Diamond, SealMotif.Spokes, 6, 0.16f),
            MintPattern.of("mint.harbor.example"),
        )
        assertEquals(
            MintPattern(BillBorder.Dots, 3, 0.75f, 20f, SealShape.Square, SealMotif.Rings, 10, 0.26f),
            MintPattern.of("mint.harb0r.example"),
        )
        assertEquals(
            MintPattern(BillBorder.Dashes, 5, 0.25f, 8f, SealShape.Teeth, SealMotif.Rings, 10, 0.16f),
            MintPattern.of("cash.orbit.example"),
        )
        assertEquals(
            MintPattern(BillBorder.Zigzag, 4, 0.875f, -8f, SealShape.Hexagon, SealMotif.Rosette, 6, 0.36f),
            MintPattern.of("localhost:8111"),
        )
    }

    @Test
    fun `case and the Unicode or punycode spelling of a host don't change its pattern`() {
        assertEquals(MintPattern.of("mint.harbor.example"), MintPattern.of("Mint.Harbor.EXAMPLE"))
        assertEquals(MintPattern.of("xn--mnze-0ra.example"), MintPattern.of("münze.example"))
        assertNotEquals(MintPattern.of("mint.harbor.example"), MintPattern.of("mint.harbor.example:3338"))
    }

    @Test
    fun `every trait takes each of its values about equally often`() {
        val patterns = (0 until 10_000).map { MintPattern.of("mint$it.example") }
        fun <T> even(values: List<T>, of: (MintPattern) -> T) {
            val counts = patterns.groupingBy(of).eachCount()
            assertEquals(values.toSet(), counts.keys)
            val expected = patterns.size / values.size
            counts.values.forEach { assertTrue("$counts", it in (expected * 0.85).toInt()..(expected * 1.15).toInt()) }
        }
        even(BillBorder.entries) { it.border }
        even(SealShape.entries) { it.sealShape }
        even(SealMotif.entries) { it.sealMotif }
        even((2..5).toList()) { it.waveFrequency }
        even((5..12).toList()) { it.petals }
        even(listOf(-35f, -20f, -8f, 8f, 20f, 35f)) { it.engravingAngle }
        even((0..7).map { it / 8f }) { it.wavePhase }
    }
}
