package whitewallet.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.model.Service
import whitewallet.core.wallet.ServiceDetails

@RunWith(RobolectricTestRunner::class)
class SettingsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val service = Service(
        host = "mint.example",
        withdrawUrl = "https://mint.example/w",
        mintPubkey = "02" + "ab".repeat(32),
        minSendableMsat = 1_000,
        maxSendableMsat = 1_000_000_000,
        feeBaseMsat = 1_000,
        feePpm = 0,
    )

    private fun details(reachability: ServiceDetails.Reachability) =
        ServiceDetails("mint.example", service, reachability, "A test service", 999_000, 1, isHome = true)

    @Test
    fun serviceDetailsShowMoneyFeesAndTheWatchOnlyKeyOnRequest() {
        compose.setContent {
            var show by remember { mutableStateOf(false) }
            ServiceDetailsScreen(
                ServiceDetailsState(details(ServiceDetails.Reachability.Reachable), loading = false, watchOnlyKey = "cx1test", showWatchOnly = show),
                onBack = {}, onRefresh = {}, onToggleWatchOnly = { show = !show }, onCopyWatchOnly = {},
            )
        }
        compose.onNodeWithText("Reachable").assertExists()
        compose.onNodeWithText("₿999 in 1 note").assertExists()
        compose.onNodeWithText("cx1test").assertDoesNotExist()
        compose.onNodeWithText("Show").performScrollTo().performClick()
        compose.onNodeWithText("cx1test").assertExists()
    }

    @Test
    fun aChangedSigningKeyIsAWarning() {
        compose.setContent {
            ServiceDetailsScreen(ServiceDetailsState(details(ServiceDetails.Reachability.KeyChanged), loading = false), {}, {}, {}, {})
        }
        compose.onNodeWithText("Signing key changed").assertExists()
        compose.onNodeWithText("now signs with a different key", substring = true).assertExists()
    }

    @Test
    fun appLockNeedsAScreenLock() {
        compose.setContent { SecuritySettingsScreen(appLock = false, available = false, onBack = {}, onAppLock = {}) }
        compose.onNodeWithText("Set up a screen lock on your phone first.").assertExists()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanAmountsPutTheSignAfterTheNumber() {
        compose.setContent {
            ServiceDetailsScreen(ServiceDetailsState(details(ServiceDetails.Reachability.Reachable), loading = false), {}, {}, {}, {})
        }
        compose.onNodeWithText("999 ₿ in 1 Schein").assertExists()
    }
}
