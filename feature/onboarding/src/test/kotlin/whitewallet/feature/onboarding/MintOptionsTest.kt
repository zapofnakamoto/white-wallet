package whitewallet.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import whitewallet.core.wallet.AppDefaults

class MintOptionsTest {
    private val defaults = AppDefaults("mint.example", allowMintChange = true, localDevMints = listOf("localhost:8111", "localhost:8112"))

    @Test
    fun `every local mint is offered in developer mode, none otherwise`() {
        assertEquals(
            listOf(MintOption.Suggested("mint.example"), MintOption.LocalDev("localhost:8111"), MintOption.LocalDev("localhost:8112"), MintOption.Custom),
            mintOptions(defaults, developerMode = true),
        )
        assertEquals(listOf(MintOption.Suggested("mint.example"), MintOption.Custom), mintOptions(defaults, developerMode = false))
    }
}
