package whitewallet.feature.onboarding

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The address step of a wallet's setup, and the address screens of Settings. */
@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        state: OnboardingState,
        onBack: (() -> Unit)? = null,
        onSuggestion: (String) -> Unit = {},
        onSkip: () -> Unit = {},
        onAbandon: () -> Unit = {},
    ) = compose.setContent {
        OnboardingScreen(
            state,
            onBack,
            onUsernameChange = {},
            mint = MintActions({}, {}),
            onSuggestion = onSuggestion,
            onStart = {},
            onContinue = {},
            onAbandon = onAbandon,
            onSkipAddress = onSkip,
        )
    }

    private fun setup(username: String = "alice") =
        OnboardingState(
            username = username,
            host = "mint.example",
            canChangeHost = false,
            mode = OnboardingMode.Setup,
            canSkipAddress = true,
        )

    @Test
    fun untilTheMintAndTheNameAreKnownNothingShows() {
        show(setup("").copy(ready = false))
        compose.onNodeWithText(text(R.string.setup_address_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.setup_address_submit)).assertDoesNotExist()
    }

    @Test
    fun whileTheMintIsAskedAboutTheNameTheFormIsLocked() {
        show(setup("calmotter4821").copy(busy = true, availability = Availability.Checking))
        compose.onNodeWithText("calmotter4821").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.availability_checking)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_skip_address)).assertIsNotEnabled()
    }

    @Test
    fun aTakenNameOffersOthers() {
        var picked: String? = null
        show(
            setup("calmotter4821").copy(availability = Availability.Taken, suggestions = listOf("calmotter7310")),
            onSuggestion = { picked = it },
        )
        compose.onNodeWithText(text(R.string.availability_taken)).assertExists()
        compose.onNodeWithText("calmotter7310").performScrollTo().performClick()
        assertEquals("calmotter7310", picked)
    }

    @Test
    fun theSetupsAddressNamesItsMintAndCanBeSkipped() {
        var skipped = false
        show(setup(), onSkip = { skipped = true })
        compose.onNodeWithText(text(R.string.setup_address_title)).assertExists()
        compose.onNodeWithText(text(R.string.setup_address_body, "mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.setup_address_submit)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.onboarding_mint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.onboarding_skip_address_hint)).performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.onboarding_skip_address)).performScrollTo().performClick()
        assertTrue(skipped)
    }

    @Test
    fun backOnTheSetupsAddressAsksBeforeTheWalletGoes() {
        var abandoned = false
        show(setup(), onAbandon = { abandoned = true })
        compose.onNodeWithContentDescriptionBack().performClick()
        compose.onNodeWithText(text(R.string.setup_abandon_body)).assertExists()
        compose.onNodeWithText(text(R.string.setup_abandon_keep)).performClick()
        assertTrue(!abandoned)
        compose.onNodeWithContentDescriptionBack().performClick()
        compose.onNodeWithText(text(R.string.setup_abandon_confirm)).performClick()
        assertTrue(abandoned)
    }

    @Test
    fun anAddressFromSettingsCantBeSkipped() {
        show(
            OnboardingState(username = "alice", host = "mint.example", canChangeHost = false, mode = OnboardingMode.AddAddress),
            onBack = {},
        )
        compose.onNodeWithText(text(R.string.onboarding_add_title)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_skip_address)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.onboarding_claim)).performScrollTo().assertExists()
    }

    @Test
    fun anAddressOnAnotherMintOffersTheMintChoice() {
        show(
            OnboardingState(
                username = "alice",
                host = "other.example",
                canChangeHost = true,
                mode = OnboardingMode.AddAddressElsewhere,
                mintOptions = listOf(MintOption.Suggested("other.example"), MintOption.Custom),
                selectedMint = MintOption.Suggested("other.example"),
            ),
            onBack = {},
        )
        compose.onNodeWithText(text(R.string.onboarding_add_elsewhere_title)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_address_preview, "alice@other.example")).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_mint)).performScrollTo().assertExists()
    }
}
