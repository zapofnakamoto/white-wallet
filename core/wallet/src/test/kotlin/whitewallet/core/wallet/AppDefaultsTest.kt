package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import whitewallet.brand.ReleaseStage
import whitewallet.core.model.AmountUnit

class AppDefaultsTest {
    private val defaults = AppDefaults(mintSuggestions = listOf("mint.example"), allowMintChange = false)

    @Test
    fun `without custom mints, only the suggested ones can be used`() {
        val curated = defaults.copy(mintSuggestions = listOf("mint.example", "mint2.example:8443"), customMints = false)
        assertEquals(true, curated.mintAllowed("https://Mint2.example:8443/"))
        assertEquals(false, curated.mintAllowed("learned.example"))
        assertEquals(true, defaults.mintAllowed("learned.example"))
        assertEquals("mint.example", curated.defaultMintHost)
    }

    @Test
    fun `another home is only possible with another allowed mint`() {
        val single = defaults.copy(customMints = false)
        assertEquals(false, single.otherMintsAllowed("mint.example"))
        assertEquals(true, single.copy(mintSuggestions = listOf("mint.example", "mint2.example")).otherMintsAllowed("mint.example"))
        assertEquals(true, defaults.otherMintsAllowed("mint.example"))
    }

    @Test
    fun `users bring their own mint, but there must be one`() {
        val own = AppDefaults(mintSuggestions = emptyList(), allowMintChange = true)
        assertEquals("", own.defaultMintHost)
        assertFailsWith<IllegalArgumentException> { AppDefaults(mintSuggestions = emptyList(), allowMintChange = true, customMints = false) }
    }

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

    @Test
    fun `auto is beta below 1_0_0 and stable from it on`() {
        assertEquals(ReleaseStage.Beta, releaseStageFromConfig("auto", "0.2.0"))
        assertEquals(ReleaseStage.Beta, releaseStageFromConfig("auto", "0.9.9.1"))
        assertEquals(ReleaseStage.Stable, releaseStageFromConfig("auto", "1.0.0"))
        assertEquals(ReleaseStage.Stable, releaseStageFromConfig("auto", "12.3"))
    }

    @Test
    fun `a brand can set the stage itself`() {
        assertEquals(ReleaseStage.Alpha, releaseStageFromConfig("alpha", "1.4.0"))
        assertEquals(ReleaseStage.Stable, releaseStageFromConfig(" Stable ", "0.2.0"))
        assertFailsWith<IllegalStateException> { releaseStageFromConfig("gamma", "0.2.0") }
        assertFailsWith<IllegalStateException> { releaseStageFromConfig("auto", "dev") }
    }

    @Test
    fun `a brand requires an address, lets users skip it, or leaves it out of setup`() {
        assertEquals(AddressPolicy.Optional, defaults.address)
        assertEquals(AddressPolicy.Required, AddressPolicy.fromConfig("required"))
        assertEquals(AddressPolicy.None, AddressPolicy.fromConfig(" None "))
        assertFailsWith<IllegalStateException> { AddressPolicy.fromConfig("true") }
    }
}
