package whitewallet.feature.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whitewallet.core.wallet.SplitLimits

/** Where the split amount starts, and when there is nothing to split. */
class SplitAmountTest {
    /** As the wallet gives them: [noteSats] less the [feeSats], at least one msat each side. */
    private fun dialog(noteSats: Long, feeSats: Long) = NotesDialog.Split("n", SplitLimits(1, noteSats * 1000 - feeSats * 1000 - 1, feeSats * 1000))

    @Test
    fun startsAtHalfOfWhatIsLeftAfterTheFee() {
        assertEquals(4L, dialog(10, 1).halfSats)
        assertEquals(50L, dialog(100, 0).halfSats)
        assertEquals("1 sat and 1 sat", 1L, dialog(3, 1).halfSats)
    }

    @Test
    fun tooSmallForTwoNotesOfWholeSats() {
        assertFalse(dialog(2, 1).possible)
        assertFalse(dialog(1, 0).possible)
        assertTrue(dialog(2, 0).possible)
        assertEquals(1L, dialog(2, 0).halfSats)
    }
}
