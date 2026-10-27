package whitewallet.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
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
    fun aChangedSigningKeyIsUsedOnlyAfterTheUserConfirms() {
        val presented = "03" + "cd".repeat(32)
        val accepted = mutableListOf<String>()
        compose.setContent {
            ServiceDetailsScreen(
                ServiceDetailsState(details(ServiceDetails.Reachability.KeyChanged).copy(presentedKey = presented), loading = false),
                onBack = {}, onRefresh = {}, onToggleWatchOnly = {}, onCopyWatchOnly = {}, onAcceptKey = { accepted += it },
            )
        }
        compose.onNodeWithText("Remembered key").assertExists()
        compose.onNodeWithText(presented.chunked(4).joinToString(" ")).assertExists()
        compose.onNodeWithText("Use the new key").performClick()
        compose.onNodeWithText("Only do this if you know why mint.example changed its key", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), accepted)
        compose.onNodeWithText("Use the new key").performClick()
        compose.onNodeWithText("Use it").performClick()
        assertEquals(listOf(presented), accepted)
    }

    @Test
    fun appLockNeedsAScreenLock() {
        compose.setContent { SecuritySettingsScreen(appLock = false, available = false, onBack = {}, onAppLock = {}) }
        compose.onNodeWithText("Set up a screen lock on your phone first.").assertExists()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun theHomeMintCanBeChangedFromItsPageWhenTheBrandAllowsIt() {
        var asked: String? = "unset"
        compose.setContent {
            ServiceDetailsScreen(
                ServiceDetailsState(details(ServiceDetails.Reachability.Reachable), loading = false, canChangeHome = true),
                {}, {}, {}, {}, onChangeHome = { asked = it },
            )
        }
        compose.onNodeWithText("Change home mint").performScrollTo().performClick()
        assertEquals(null, asked)
        compose.onNodeWithText("Make this my home mint").assertDoesNotExist()
    }

    @Test
    fun anotherMintCanBecomeTheHome() {
        var asked: String? = null
        compose.setContent {
            ServiceDetailsScreen(
                ServiceDetailsState(details(ServiceDetails.Reachability.Reachable).copy(isHome = false), loading = false, canChangeHome = true),
                {}, {}, {}, {}, onChangeHome = { asked = it },
            )
        }
        compose.onNodeWithText("Make this my home mint").performScrollTo().performClick()
        assertEquals("mint.example", asked)
    }

    @Test
    fun aLockedBrandOffersNoChangeOfTheHomeMint() {
        compose.setContent {
            ServiceDetailsScreen(ServiceDetailsState(details(ServiceDetails.Reachability.Reachable), loading = false), {}, {}, {}, {})
        }
        compose.onNodeWithText("Change home mint").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanAmountsPutTheSignAfterTheNumber() {
        compose.setContent {
            ServiceDetailsScreen(ServiceDetailsState(details(ServiceDetails.Reachability.Reachable), loading = false), {}, {}, {}, {})
        }
        compose.onNodeWithText("999 ₿ in 1 Schein").assertExists()
    }

    @Test
    fun whileCollectingTheHomePageOffersNoMove() {
        var state by mutableStateOf(ServiceDetailsState(details(ServiceDetails.Reachability.Reachable), loading = false, collects = true))
        compose.setContent { ServiceDetailsScreen(state, {}, {}, {}, {}) }
        compose.onNodeWithText("Move…").assertDoesNotExist()
        state = state.copy(collects = false)
        compose.onNodeWithText("Move…").assertExists()
        state = state.copy(collects = true, details = state.details!!.copy(host = "other.example", isHome = false))
        compose.onNodeWithText("Move…").assertExists()
    }
}
