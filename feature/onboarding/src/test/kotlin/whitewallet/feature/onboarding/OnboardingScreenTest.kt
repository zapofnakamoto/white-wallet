package whitewallet.feature.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.brand.ReleaseStage
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.LocalReleaseStage

@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTakenNameOffersOthers() {
        var picked: String? = null
        val state = OnboardingState(
            username = "calmotter4821",
            host = "mint.example",
            canChangeHost = false,
            availability = Availability.Taken,
            suggestions = listOf("calmotter7310"),
        )
        compose.setContent {
            OnboardingScreen(
                "Wallet", logo = {}, state = state, onBack = null, onUsernameChange = {}, service = ServiceActions({}, {}),
                onSuggestion = { picked = it }, onStart = {}, onContinue = {}, onAccountNameChange = {},
            )
        }
        compose.onNodeWithText("Already taken. Try one of these:").assertExists()
        compose.onNodeWithText("calmotter7310").performScrollTo().performClick()
        assertEquals("calmotter7310", picked)
    }

    @Test
    fun anAddressOnAnotherServiceOffersTheServiceChoice() {
        val state = OnboardingState(
            username = "alice", host = "other.example", canChangeHost = true, mode = OnboardingMode.AddAddressElsewhere,
            serviceOptions = listOf(ServiceOption.Suggested("other.example"), ServiceOption.Custom), selectedService = ServiceOption.Suggested("other.example"),
        )
        compose.setContent {
            OnboardingScreen(
                "Wallet", logo = {}, state = state, onBack = {}, onUsernameChange = {}, service = ServiceActions({}, {}),
                onSuggestion = {}, onStart = {}, onContinue = {}, onAccountNameChange = {},
            )
        }
        compose.onNodeWithText("Address on another service").assertExists()
        compose.onNodeWithText("Your address: alice@other.example").assertExists()
        compose.onNodeWithText("Mint").performScrollTo().assertExists()
    }

    @Test
    fun withoutAnAddressAndOneMintSetupIsOneButton() {
        var started = false
        val state = OnboardingState(username = "", host = "mint.example", canChangeHost = false, withAddress = false)
        compose.setContent {
            OnboardingScreen(
                "Wallet", logo = {}, state = state, onBack = null, onUsernameChange = {}, service = ServiceActions({}, {}),
                onSuggestion = {}, onStart = { started = true }, onContinue = {}, onAccountNameChange = {}, onRestore = {},
            )
        }
        compose.onNodeWithText("Your address").assertDoesNotExist()
        compose.onNodeWithText("Mint").assertDoesNotExist()
        compose.onNodeWithText("Restore from recovery phrase").assertExists()
        compose.onNodeWithText("Get started").performClick()
        assertTrue(started)
    }

    @Test
    fun withoutAnAddressTheMintIsStillOfferedWhenThereIsAChoice() {
        val state = OnboardingState(
            username = "", host = "mint.example", canChangeHost = true, withAddress = false,
            serviceOptions = listOf(ServiceOption.Suggested("mint.example"), ServiceOption.Custom), selectedService = ServiceOption.Suggested("mint.example"),
        )
        compose.setContent {
            OnboardingScreen(
                "Wallet", logo = {}, state = state, onBack = null, onUsernameChange = {}, service = ServiceActions({}, {}),
                onSuggestion = {}, onStart = {}, onContinue = {}, onAccountNameChange = {},
            )
        }
        compose.onNodeWithText("Your address").assertDoesNotExist()
        compose.onNodeWithText("Mint").assertExists()
    }

    @Test
    fun withoutABrandDesignTheLogoAndNameShow() {
        compose.setContent {
            OnboardingScreen(
                "Wallet", logo = { Text("logo") }, state = OnboardingState(username = "alice", host = "mint.example", canChangeHost = false),
                onBack = null, onUsernameChange = {}, service = ServiceActions({}, {}), onSuggestion = {}, onStart = {}, onContinue = {}, onAccountNameChange = {},
            )
        }
        // Decorations are decorative: the default logo is drawn but not read out; the name is a heading.
        compose.onNodeWithText("logo").assertDoesNotExist()
        compose.onNode(hasContentDescription("Wallet") and isHeading()).assertExists()
    }

    @Test
    fun aBrandDesignDrawsItsOwnHeaderAndTitle() {
        val anchors = mutableListOf<Anchor>()
        val design = object : BrandDesign {
            @Composable
            override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                anchors += anchor
                Text("brand ${anchor.id} ${context.appName}", Modifier.testTag(anchor.id))
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design, LocalAppName provides "Wallet") {
                OnboardingScreen(
                    "Wallet", logo = { Text("logo") }, state = OnboardingState(username = "alice", host = "mint.example", canChangeHost = false),
                    onBack = null, onUsernameChange = {}, service = ServiceActions({}, {}), onSuggestion = {}, onStart = {}, onContinue = {}, onAccountNameChange = {},
                )
            }
        }
        compose.onNodeWithTag(Anchor.OnboardingHeader.id, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("brand onboarding.title Wallet", useUnmergedTree = true).assertExists()
        // Whatever the brand draws, screen readers still get the name as a heading.
        compose.onNode(hasContentDescription("Wallet") and isHeading()).assertExists()
        assertTrue(Anchor.OnboardingHeader in anchors && Anchor.OnboardingTitle in anchors)
    }

    @Test
    fun anAlphaSaysSoBeforeTheUserStarts() {
        var stage: ReleaseStage? = null
        val design = object : BrandDesign {
            @Composable
            override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                stage = context.releaseStage
                if (anchor == Anchor.OnboardingTitle) Text("wordmark") else default()
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design, LocalReleaseStage provides ReleaseStage.Alpha) {
                OnboardingScreen(
                    "Wallet", logo = { Text("logo") }, state = OnboardingState(username = "alice", host = "mint.example", canChangeHost = false),
                    onBack = null, onUsernameChange = {}, service = ServiceActions({}, {}), onSuggestion = {}, onStart = {}, onContinue = {}, onAccountNameChange = {},
                )
            }
        }
        // The badge sits by the brand's own title, once.
        compose.onNodeWithText("wordmark", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("This app is an alpha version: it is still being built and may have bugs.").performScrollTo().assertExists()
        // A brand drawing its own title knows the stage too.
        assertEquals(ReleaseStage.Alpha, stage)
    }

    @Test
    fun aStableReleaseShowsNoStageAtSetup() {
        compose.setContent {
            OnboardingScreen(
                "Wallet", logo = { Text("logo") }, state = OnboardingState(username = "alice", host = "mint.example", canChangeHost = false),
                onBack = null, onUsernameChange = {}, service = ServiceActions({}, {}), onSuggestion = {}, onStart = {}, onContinue = {}, onAccountNameChange = {},
            )
        }
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithText("Alpha").assertDoesNotExist()
    }
}
