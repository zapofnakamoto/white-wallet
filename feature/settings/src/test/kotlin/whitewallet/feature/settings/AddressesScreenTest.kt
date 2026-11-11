package whitewallet.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.AddressEntry
import whitewallet.core.wallet.AddressStatus

/** Addresses on the home mint and on other mints. */
@RunWith(RobolectricTestRunner::class)
class AddressesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val home = AddressEntry(RegisteredAddress("alice", "home.example", 0), primary = true, status = AddressStatus.Ours)
    private val other = AddressEntry(RegisteredAddress("alice", "other.example", 1), primary = false, status = AddressStatus.Ours)

    private fun show(state: AddressesState, onAddElsewhere: () -> Unit = {}) = compose.setContent {
        AddressesScreen(state, {}, {}, {}, {}, AddressActions({}, {}, {}), {}, {}, onAddElsewhere)
    }

    @Test
    fun anAddressOnAnotherMintIsListedUnderItAndCanBecomePrimary() {
        show(AddressesState(listOf(home, other), homeHost = "home.example", otherMints = true))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("alice@other.example"))
        compose.onNodeWithText("other.example").assertExists()
        compose.onNodeWithText(text(R.string.addresses_make_primary)).assertExists()
    }

    @Test
    fun anAddressShowsItsOwnWatchOnlyKeyAndCopiesIt() {
        var state by mutableStateOf(AddressesState(listOf(home, other), homeHost = "home.example", otherMints = true))
        var toggled: RegisteredAddress? = null
        var copied: String? = null
        val actions = AddressActions({}, {}, {}, onToggleWatchOnly = { toggled = it }, onCopyWatchOnly = { copied = it })
        compose.setContent { AddressesScreen(state, {}, {}, {}, {}, actions, {}, {}, {}) }

        compose.onAllNodesWithText(text(R.string.addresses_watch_only))[0].performClick()
        assertEquals(home.address, toggled)

        state = state.copy(watchOnly = WatchOnlyKey(home.address.address, "cx1test"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("cx1test"))
        compose.onNodeWithText(text(R.string.addresses_watch_only_hint)).assertExists()
        compose.onAllNodesWithText(text(R.string.mint_copy)).onLast().performScrollTo().performClick()
        assertEquals("cx1test", copied)
    }

    @Test
    fun anotherMintIsOfferedOnlyWhenTheBrandAllowsIt() {
        var asked = false
        show(AddressesState(listOf(home), homeHost = "home.example", otherMints = true), onAddElsewhere = { asked = true })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add an address on another mint"))
        compose.onNodeWithText(text(R.string.addresses_add_elsewhere)).performClick()
        assertEquals(true, asked)
    }

    @Test
    fun aHeldAddressWaitsForItsFeeAndOffersOnlyPayingIt() {
        var paid = false
        val held =
            AddressEntry(RegisteredAddress("bob", "home.example", 2, share = 1, feeInvoice = "lnbc1fee"), primary = false, status = null)
        compose.setContent {
            AddressesScreen(
                AddressesState(listOf(home, held), homeHost = "home.example", otherMints = false),
                {},
                {},
                {},
                {},
                AddressActions({}, {}, {}, onPayFee = { paid = true }),
                {},
                {},
                {},
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.addresses_held_pay)))
        compose.onNodeWithText(text(R.string.addresses_held)).assertExists()
        compose.onAllNodesWithText(text(R.string.addresses_unregister)).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.addresses_held_pay)).performClick()
        assertEquals(true, paid)
    }

    @Test
    fun aLockedBrandShowsNoOtherMint() {
        show(AddressesState(listOf(home), homeHost = "home.example", otherMints = false))
        compose.onNodeWithText(text(R.string.addresses_add_elsewhere)).assertDoesNotExist()
    }

    @Test
    fun withOnlyAnAddressElsewhereOneCanStillBeAddedHere() {
        show(AddressesState(listOf(other), homeHost = "home.example", otherMints = true))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.addresses_add)))
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        show(AddressesState(listOf(home), homeHost = "home.example", otherMints = true))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Adresse bei einer anderen Mint hinzufügen"))
    }

    @Test
    fun untilTheAddressesAreReadThePageDoesNotSayThereAreNone() {
        show(AddressesState(loaded = false))
        compose.onNodeWithText(text(R.string.addresses_none)).assertDoesNotExist()
    }
}
