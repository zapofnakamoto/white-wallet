package whitewallet.feature.onboarding

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
        compose.onNodeWithText("@other.example").assertExists()
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
}
