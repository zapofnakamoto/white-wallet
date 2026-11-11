package whitewallet.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
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
import org.robolectric.annotation.Config
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.TrustDefault

@RunWith(RobolectricTestRunner::class)
class MintsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val key = "02" + "ab".repeat(32)
    private val home = MintInfo("home.example", key, trusted = true, trustedByDefault = TrustDefault.Home, isHome = true)
    private val other = MintInfo("other.example", "03" + "cd".repeat(32), trusted = false, trustedByDefault = null, removable = true)
    private val unknown = MintInfo("old.example", null, trusted = false, trustedByDefault = null)

    private fun show(state: MintsState, onAdd: () -> Unit = {}, onTrusted: (MintInfo, Boolean) -> Unit = { _, _ -> }) = compose.setContent {
        MintsScreen(state, onBack = {}, onOpen = {}, onTrusted = onTrusted, onAdd = onAdd)
    }

    @Test
    fun theHomeMintIsTrustedByDefaultAndOthersCanBeSwitchedOn() {
        var changed: Pair<String, Boolean>? = null
        show(MintsState(listOf(home, other, unknown))) { m: MintInfo, t: Boolean -> changed = m.host to t }
        compose.onNodeWithText(text(R.string.mints_default_home)).assertExists()
        compose.onNodeWithText(text(R.string.settings_home_mint)).assertExists()
        compose.onNodeWithText("₿", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mints_key, "02ababab…abababab")).assertExists()
        val switches = compose.onAllNodes(isToggleable())
        switches[0].assertIsOn()
        switches[1].assertIsOff().performClick()
        assertEquals("other.example" to true, changed)
        switches[2].assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mints_no_key)).assertExists()
    }

    @Test
    fun untrustingADefaultSaysSo() {
        show(MintsState(listOf(home.copy(trusted = false))))
        compose.onNodeWithText(text(R.string.mints_untrusted_default)).assertExists()
    }

    @Test
    fun addingAMintOpensItsOwnSteps() {
        var adding = false
        show(MintsState(listOf(home)), onAdd = { adding = true })
        compose.onNodeWithText(text(R.string.mints_add)).performScrollTo().performClick()
        assertTrue(adding)
    }

    @Test
    fun withoutCustomMintsAMissingSuggestionCanStillBeAdded() {
        show(MintsState(listOf(home), offered = listOf("mint2.example"), custom = false))
        compose.onNodeWithText(text(R.string.mints_add)).performScrollTo().assertExists()
    }

    @Test
    fun withEverySuggestionKnownThereIsNothingToAdd() {
        show(MintsState(listOf(home), custom = false))
        compose.onNodeWithText(text(R.string.mints_add)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        show(MintsState(listOf(home.copy(trustedByDefault = TrustDefault.Brand))))
        compose.onNodeWithText("Standardmäßig vertraut in dieser App").assertExists()
    }

    @Test
    fun untilTheMintsAreReadThePageDrawsNoneAndNoButton() {
        show(MintsState(loaded = false))
        compose.onNodeWithText(text(R.string.mints_intro)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mints_add)).assertDoesNotExist()
    }
}
