package whitewallet.feature.settings

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    @get:Rule
    val compose = createComposeRule()

    private val home = AddressEntry(RegisteredAddress("alice", "home.example", 0), primary = true, status = AddressStatus.Ours)
    private val other = AddressEntry(RegisteredAddress("alice", "other.example", 1), primary = false, status = AddressStatus.Ours)

    private fun show(state: AddressesState, onAddElsewhere: () -> Unit = {}) = compose.setContent {
        AddressesScreen(state, {}, {}, {}, {}, AddressActions({}, {}, {}), {}, {}, onAddElsewhere)
    }

    @Test
    fun anAddressOnAnotherMintIsListedUnderItAndCanNotBecomePrimary() {
        show(AddressesState(listOf(home, other), homeHost = "home.example", otherServices = true))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("alice@other.example"))
        compose.onNodeWithText("other.example").assertExists()
        compose.onNodeWithText("Make primary").assertDoesNotExist()
    }

    @Test
    fun anotherServiceIsOfferedOnlyWhenTheBrandAllowsIt() {
        var asked = false
        show(AddressesState(listOf(home), homeHost = "home.example", otherServices = true), onAddElsewhere = { asked = true })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add an address on another mint"))
        compose.onNodeWithText("Add an address on another mint").performClick()
        assertEquals(true, asked)
    }

    @Test
    fun aLockedBrandShowsNoOtherService() {
        show(AddressesState(listOf(home), homeHost = "home.example", otherServices = false))
        compose.onNodeWithText("Add an address on another mint").assertDoesNotExist()
    }

    @Test
    fun withOnlyAnAddressElsewhereTheFirstOneHereIsStillOffered() {
        show(AddressesState(listOf(other), homeHost = "home.example", otherServices = true))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Get a Lightning address"))
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        show(AddressesState(listOf(home), homeHost = "home.example", otherServices = true))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Adresse bei einer anderen Mint hinzufügen"))
    }
}
