package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** What a run of the note policies tells the user, and the brand's say in it. */
class MaintenanceNoticeTest {
    private fun run(merged: Int = 0, split: Int = 0, rotated: Int = 0, collected: List<Collected> = emptyList(), replaced: Int = 0, made: Int = 0,
                    madeMsat: Long = 0, change: Long? = 0) =
        PolicyRun(rotated, merged, failed = 0, stoppedEarly = null, atMillis = 0, split = split, collected = collected,
            notesReplaced = replaced, notesMade = made, notesMadeMsat = madeMsat, balanceChangeMsat = change)

    @Test
    fun `the one thing that best says what happened`() {
        assertEquals(MaintenanceNotice.Combined(11, 1, 63_000, 1_000), MaintenanceNotice.of(run(merged = 12, replaced = 11, made = 1, madeMsat = 63_000, change = 1_000), true, true))
        assertEquals(MaintenanceNotice.Split(1, 4, -3_000), MaintenanceNotice.of(run(split = 3, replaced = 1, made = 4, change = -3_000), true, true))
        assertEquals(MaintenanceNotice.Rearranged(3, 2, null), MaintenanceNotice.of(run(merged = 2, split = 1, replaced = 3, made = 2), true, true))
        assertEquals(MaintenanceNotice.Renewed(2, null), MaintenanceNotice.of(run(rotated = 2, replaced = 2, made = 2), true, true))
        // A move home says so, merged or not; one still under way is not told yet.
        val home = listOf(Collected("other", 5_000, 100), Collected("third", 7_000, 0, pending = true))
        assertEquals(MaintenanceNotice.MovedHome(5_000, null), MaintenanceNotice.of(run(merged = 2, collected = home, replaced = 2, made = 1, change = null), true, true))
    }

    @Test
    fun `without notes or mints to see, only the balance`() {
        assertEquals(MaintenanceNotice.BalanceChanged(1_000), MaintenanceNotice.of(run(merged = 2, replaced = 2, made = 1, change = 1_000), false, true))
        assertNull(MaintenanceNotice.of(run(rotated = 1, replaced = 1, made = 1), false, true), "the balance stayed the same")
        assertEquals(MaintenanceNotice.BalanceChanged(-100), MaintenanceNotice.of(run(collected = listOf(Collected("other", 5_000, 100)), change = -100), true, false))
    }

    @Test
    fun `a run that only tried tells nothing`() {
        assertNull(MaintenanceNotice.of(PolicyRun(0, 0, failed = 1, stoppedEarly = "no answer", atMillis = 0), true, true))
    }

    @Test
    fun `the brand sets the default and whether users may change it`() {
        val on = MaintenanceNoticeDefaults.fromConfig("on", whenNotesHidden = true)
        assertEquals(true, on.offered)
        assertEquals(true, on.active(null))
        assertEquals(false, on.active(false))
        assertEquals(false, MaintenanceNoticeDefaults.fromConfig("off", true).active(null))
        assertEquals(true, MaintenanceNoticeDefaults.fromConfig("always", true).active(false), "no switch, always told")
        assertEquals(false, MaintenanceNoticeDefaults.fromConfig("Hidden", true).active(true), "no switch, never told")
        assertEquals(false, MaintenanceNoticeDefaults.fromConfig("on", whenNotesHidden = false).whenNotesHidden)
        assertFailsWith<IllegalStateException> { MaintenanceNoticeDefaults.fromConfig("sometimes", true) }
    }
}
