package whitewallet.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.lnurlcash.client.NetworkConditions

@RunWith(RobolectricTestRunner::class)
class DeveloperSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aDeveloperPicksABadConnection() {
        var chosen: NetworkConditions.Profile? = null
        compose.setContent {
            DeveloperSettingsScreen(true, emptyList(), onBack = {}, onToggle = {}, onOpenPage = {}, network = NetworkConditions.Profile.Slow, onNetwork = { chosen = it })
        }
        compose.onNodeWithText("Every request takes 0.2 seconds longer. Normal again when developer mode is off.").assertExists()
        compose.onNodeWithText("Flaky").performScrollTo().performClick()
        assertEquals(NetworkConditions.Profile.Flaky, chosen)
    }

    @Test
    fun withoutDeveloperModeThereIsNoNetworkChoice() {
        compose.setContent { DeveloperSettingsScreen(false, emptyList(), onBack = {}, onToggle = {}, onOpenPage = {}, network = NetworkConditions.Profile.Normal) }
        compose.onNodeWithText("Network").assertDoesNotExist()
    }

    @Test
    fun aReleaseBuildOffersNoNetworkChoice() {
        compose.setContent { DeveloperSettingsScreen(true, emptyList(), onBack = {}, onToggle = {}, onOpenPage = {}, network = null) }
        compose.onNodeWithText("Network").assertDoesNotExist()
    }
}
