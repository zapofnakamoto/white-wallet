package whitewallet.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import whitewallet.core.wallet.AppDefaults

class MintOptionsTest {
    private val defaults = AppDefaults(listOf("mint.example"), allowMintChange = true, localDevMints = listOf("localhost:8111", "localhost:8112"))

    @Test
    fun `every local mint is offered in developer mode, none otherwise`() {
        assertEquals(
            listOf(MintOption.Suggested("mint.example"), MintOption.LocalDev("localhost:8111"), MintOption.LocalDev("localhost:8112"), MintOption.Custom),
            mintOptions(defaults, developerMode = true),
        )
        assertEquals(listOf(MintOption.Suggested("mint.example"), MintOption.Custom), mintOptions(defaults, developerMode = false))
    }

    @Test
    fun `without custom mints, only the suggested ones are offered`() {
        val curated = defaults.copy(mintSuggestions = listOf("mint.example", "mint2.example"), customMints = false)
        assertEquals(listOf(MintOption.Suggested("mint.example"), MintOption.Suggested("mint2.example")), mintOptions(curated, developerMode = false))
        assertTrue(mintOptions(curated, developerMode = false).hasChoice())
        assertFalse(mintOptions(curated.copy(mintSuggestions = listOf("mint.example")), developerMode = false).hasChoice())
    }

    @Test
    fun `users who bring their own mint only get the field`() {
        val options = mintOptions(defaults.copy(mintSuggestions = emptyList()), developerMode = false)
        assertEquals(listOf(MintOption.Custom), options)
        assertTrue(options.hasChoice())
        assertEquals(MintOption.Custom, keepSelection(options, emptyList(), MintOption.Custom, ""))
    }

    @Test
    fun `known mints come first, each mint once`() {
        assertEquals(
            listOf(MintOption.Suggested("learned.example"), MintOption.Suggested("mint.example"), MintOption.Custom),
            mintOptions(defaults, developerMode = false, known = listOf("learned.example", "mint.example")),
        )
    }

    @Test
    fun `a choice no longer offered falls back to an offered mint`() {
        val options = listOf(MintOption.Suggested("mint.example"))
        assertEquals(MintOption.Suggested("mint.example"), keepSelection(options, emptyList(), MintOption.Custom, "typed.example"))
    }

    @Test
    fun `setup can't go on without a mint`() {
        val state = OnboardingState(username = "alice", host = "", canChangeHost = true, availability = Availability.Unknown)
        assertFalse(state.canSubmit)
        assertTrue(state.copy(host = "mint.example").canSubmit)
        assertFalse(OnboardingState(username = "", host = " ", canChangeHost = true, withAddress = false).canSubmit)
    }
}
