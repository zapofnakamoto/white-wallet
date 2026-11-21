package whitewallet.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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

/** Where every wallet's setup ends: what the screen says for a first and an added wallet, with and without an address. */
@RunWith(RobolectricTestRunner::class)
class WalletReadyScreenTest {
    @get:Rule val compose = createComposeRule()

    /** The anchors the screen asks the brand for, with their status. */
    private val decorated = mutableListOf<Pair<Anchor, WalletStatus>>()
    private var opened = 0

    private fun show(state: WalletReadyState, address: String? = null) = compose.setContent {
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    decorated += anchor to context.status
                }
            }
        CompositionLocalProvider(LocalBrandDesign provides design) { WalletReadyScreen(state, address) { opened++ } }
    }

    @Test
    fun theFirstWalletIsReadyWithoutNamingIt() {
        show(WalletReadyState())
        assertEquals(listOf(Anchor.WalletReady to WalletStatus.Done(0)), decorated.distinct())
        compose.onNodeWithText(text(R.string.wallet_ready_title)).assertExists()
        // One wallet: nothing to tell apart, and nothing was found or added.
        compose.onNodeWithText(text(R.string.wallet_ready_in_use, text(DesignR.string.wallet_numbered, 1))).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.claimed_body)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wallet_ready_open)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun anAddedWalletSaysItIsTheOneInUseNow() {
        show(WalletReadyState(number = 2, several = true))
        compose.onNodeWithText(text(R.string.wallet_ready_in_use, text(DesignR.string.wallet_numbered, 2))).assertExists()
    }

    @Test
    fun anAddedWalletTheUserNamedIsCalledByThatName() {
        show(WalletReadyState(name = "Family", number = 2, several = true))
        compose.onNodeWithText(text(R.string.wallet_ready_in_use, "Family")).assertExists()
    }

    @Test
    fun theAddressTheSetupAddedShowsHereWithCopyAndShare() {
        show(WalletReadyState(), address = "alice@mint.example")
        // One success screen for the setup: the wallet's, not the address's.
        assertEquals(listOf(Anchor.WalletReady to WalletStatus.Done(0)), decorated.distinct())
        compose.onNodeWithText(text(R.string.wallet_ready_title)).assertExists()
        compose.onNodeWithText(text(R.string.claimed_title)).assertDoesNotExist()
        compose.onNodeWithText("alice@mint.example").assertExists()
        compose.onNodeWithText(text(DesignR.string.copy)).assertExists()
        compose.onNodeWithText(text(DesignR.string.share)).assertExists()
        compose.onNodeWithText(text(R.string.claimed_body)).assertExists()
    }

    @Test
    fun whatARestoreFoundIsSaidAsOnTheOtherSuccessScreens() {
        show(WalletReadyState(foundMsat = 21_000_000, backedUp = true))
        assertEquals(listOf(Anchor.WalletReady to WalletStatus.Done(21_000_000)), decorated.distinct())
        compose.onNodeWithText(text(R.string.add_mint_found_total, "₿21,000")).assertExists()
    }

    @Test
    fun untilTheWalletIsReadTheScreenShowsNothing() {
        show(WalletReadyState(loaded = false), address = "alice@mint.example")
        compose.onNodeWithText(text(R.string.wallet_ready_title)).assertDoesNotExist()
        compose.onNodeWithText("alice@mint.example").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.wallet_ready_open)).assertDoesNotExist()
        assertEquals(emptyList<Pair<Anchor, WalletStatus>>(), decorated)
    }
}
