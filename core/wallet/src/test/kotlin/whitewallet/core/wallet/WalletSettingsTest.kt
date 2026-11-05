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
}
