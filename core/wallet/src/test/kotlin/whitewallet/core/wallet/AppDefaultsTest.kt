package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import whitewallet.core.model.AmountUnit

class AppDefaultsTest {
    private val defaults = AppDefaults(defaultServiceHost = "mint.example", allowServiceChange = false)

    @Test
    fun `amounts show in bitcoin unless the user picks sats`() {
        assertEquals(AmountUnit.Bitcoin, defaults.amountUnit(null))
        assertEquals(AmountUnit.Sats, defaults.amountUnit(AmountUnit.Sats))
    }

    @Test
    fun `a brand can fix the unit, whatever was stored`() {
        val fixed = defaults.copy(defaultAmountUnit = AmountUnit.Sats, allowUnitChange = false)
        assertEquals(AmountUnit.Sats, fixed.amountUnit(AmountUnit.Bitcoin))
        assertEquals(AmountUnit.Sats, fixed.amountUnit(null))
    }

    @Test
    fun `developer options are shown, unlocked from About, or hidden`() {
        assertEquals(true, defaults.developerOptionsVisible(developerMode = false, unlocked = false))
        val unlock = defaults.copy(developerOptions = DeveloperOptions.Unlock)
        assertEquals(false, unlock.developerOptionsVisible(developerMode = false, unlocked = false))
        assertEquals(true, unlock.developerOptionsVisible(developerMode = false, unlocked = true))
        assertEquals(true, unlock.developerOptionsVisible(developerMode = true, unlocked = false))
        val hidden = defaults.copy(developerOptions = DeveloperOptions.Hidden)
        assertEquals(false, hidden.developerOptionsVisible(developerMode = true, unlocked = true))
    }

    @Test
    fun `a debug build can always reach developer options`() {
        assertEquals(DeveloperOptions.Unlock, DeveloperOptions.fromConfig("hidden", debug = true))
        assertEquals(DeveloperOptions.Hidden, DeveloperOptions.fromConfig("hidden", debug = false))
        assertEquals(DeveloperOptions.Shown, DeveloperOptions.fromConfig(" Shown ", debug = false))
    }
}
