package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletSettingsTest {
    @Test
    fun `a setting outside what wallet properties allows is refused, naming its key`() {
        val refused = assertFailsWith<IllegalArgumentException> { WalletSettings(scanStopAfterUnusedKeys = 0) }
        assertEquals("scan.stop.after.unused.keys must be from 1 to 1000", refused.message)
    }

    @Test
    fun `an unknown note's grace period is from none to 30 days, a day by default`() {
        assertEquals(86_400_000L, WalletSettings().unknownNoteGracePeriodMillis)
        WalletSettings(unknownNoteGracePeriodMillis = 0)
        WalletSettings(unknownNoteGracePeriodMillis = 2_592_000_000L)
        for (refused in listOf(-1L, 2_592_000_001L)) {
            val error = assertFailsWith<IllegalArgumentException> { WalletSettings(unknownNoteGracePeriodMillis = refused) }
            assertEquals("unknown.note.grace.period.millis must be from 0 to 2592000000", error.message)
        }
    }
}
