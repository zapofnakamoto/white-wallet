package whitewallet.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.TrustDefault

@RunWith(RobolectricTestRunner::class)
class MintsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val key = "02" + "ab".repeat(32)
    private val home = MintInfo("home.example", key, trusted = true, trustedByDefault = TrustDefault.Home, homeOf = listOf("Main", "Savings"))
    private val other = MintInfo("other.example", "03" + "cd".repeat(32), trusted = false, trustedByDefault = null, removable = true)
    private val unknown = MintInfo("old.example", null, trusted = false, trustedByDefault = null)

    private fun show(state: MintsState, onTrusted: (MintInfo, Boolean) -> Unit = { _, _ -> }) = compose.setContent {
        MintsScreen(state, onBack = {}, onOpen = {}, onTrusted = onTrusted, onHost = {}, onAdd = {})
    }

    @Test
    fun theHomeMintIsTrustedByDefaultAndOthersCanBeSwitchedOn() {
        var changed: Pair<String, Boolean>? = null
        show(MintsState(listOf(home, other, unknown))) { m, t -> changed = m.host to t }
        compose.onNodeWithText("Trusted by default: your account's home").assertExists()
        compose.onNodeWithText("Home mint of Main, Savings").assertExists()
        compose.onNodeWithText("₿", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Key 02ababab…abababab").assertExists()
        val switches = compose.onAllNodes(isToggleable())
        switches[0].assertIsOn()
        switches[1].assertIsOff().performClick()
        assertEquals("other.example" to true, changed)
        switches[2].assertIsNotEnabled()
        compose.onNodeWithText("No signing key known yet").assertExists()
    }

    @Test
    fun untrustingADefaultSaysSo() {
        show(MintsState(listOf(home.copy(trusted = false))))
        compose.onNodeWithText("Trusted by default, but you switched it off").assertExists()
    }

    @Test
    fun aMintThatCantBeReachedIsNotAdded() {
        show(MintsState(listOf(home), newHost = "nope.example", problem = MintsProblem.Unreachable))
        compose.onNodeWithText("Couldn't connect. Check the address and your connection.").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        show(MintsState(listOf(home.copy(trustedByDefault = TrustDefault.Brand))))
        compose.onNodeWithText("Standardmäßig vertraut in dieser App").assertExists()
    }
}
