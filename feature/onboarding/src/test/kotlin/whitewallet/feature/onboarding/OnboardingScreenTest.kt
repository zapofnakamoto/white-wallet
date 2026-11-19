package whitewallet.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.core.designsystem.R as DesignR

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

    /** The anchors the screen asks the brand for, with their status. */
    private val decorated = mutableListOf<Pair<Anchor, WalletStatus>>()

    private fun showClaimed(state: OnboardingState, onContinue: () -> Unit = {}) = compose.setContent {
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    decorated += anchor to context.status
                }
            }
        CompositionLocalProvider(LocalBrandDesign provides design) {
            OnboardingScreen(
                state,
                onBack = {},
                onUsernameChange = {},
                mint = MintActions({}, {}),
                onSuggestion = {},
                onStart = {},
                onContinue = onContinue,
            )
        }
    }

    private fun aCheckedAddressEndsOnTheSuccessScreen(mode: OnboardingMode, button: Int) {
        var continued = false
        showClaimed(setup().copy(mode = mode, claimed = Claimed("alice@mint.example", confirmed = true)), { continued = true })
        assertEquals(listOf(Anchor.AddressAdded to WalletStatus.Done(0)), decorated.distinct())
        compose.onNodeWithText(text(R.string.claimed_title)).assertExists()
        compose.onNodeWithText("alice@mint.example").assertExists()
        compose.onNodeWithText(text(DesignR.string.copy)).assertExists()
        compose.onNodeWithText(text(DesignR.string.share)).assertExists()
        compose.onNodeWithText(text(button)).performClick()
        assertTrue(continued)
    }

    // The setup goes on to its next step; from Settings the address is the end.
    @Test
    fun aCheckedAddressInTheSetupEndsOnTheSuccessScreen() =
        aCheckedAddressEndsOnTheSuccessScreen(OnboardingMode.Setup, R.string.claimed_continue)

    @Test
    fun aCheckedAddressFromSettingsEndsOnTheSuccessScreen() =
        aCheckedAddressEndsOnTheSuccessScreen(OnboardingMode.AddAddress, R.string.success_done)

    @Test
    fun anAddressThatCouldNotBeCheckedKeepsItsWarning() {
        showClaimed(setup().copy(mode = OnboardingMode.AddAddress, claimed = Claimed("alice@mint.example", confirmed = false)))
        compose.onNodeWithText(text(R.string.claimed_title_unconfirmed)).assertExists()
        compose.onNodeWithText(text(R.string.claimed_body_unconfirmed)).assertExists()
        assertTrue(decorated.none { it.first == Anchor.AddressAdded })
    }
}
