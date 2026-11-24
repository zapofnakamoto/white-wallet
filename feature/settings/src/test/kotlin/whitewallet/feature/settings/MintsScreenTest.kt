package whitewallet.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import java.text.NumberFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.core.designsystem.AccountActions
import whitewallet.core.designsystem.MINT_KEY_WARNING_TAG
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.model.Account
import whitewallet.core.model.AccountChoice
import whitewallet.core.model.AccountSummary
import whitewallet.core.wallet.NoHome

/** The Mints page: the home as a card on top, the other mints as quiet cards, the way to add one; nothing until it is read. */
@RunWith(RobolectricTestRunner::class)
class MintsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun card(
        host: String,
        msat: Long = 0,
        notes: Int = 0,
        home: Boolean = false,
        addresses: Int = 0,
        trusted: Boolean = true,
        keyChanged: Boolean = false,
        answering: Boolean = true,
    ) = MintCard(host, home, msat, notes, addresses, trusted && !keyChanged, keyChanged, answering)

    private val home = card("home.example", 21_000_000, notes = 3, home = true, addresses = 1)
    private val cash = card("cash.example", 4_000_000, notes = 1)
    private val old = card("old.example", trusted = false)

    private fun summary(id: String, name: String) = AccountSummary(Account(id, name, 0, walletId = "w"), 0)

    private fun show(
        state: MintsPageState,
        onOpen: (String) -> Unit = {},
        onAdd: () -> Unit = {},
        onNoHome: (NoHome) -> Unit = {},
        onChangeHome: () -> Unit = {},
        onSwitch: (String) -> Unit = {},
    ) = compose.setContent {
        MintsScreen(
            state,
            onBack = {},
            onOpen = onOpen,
            onAdd = onAdd,
            onNoHome = onNoHome,
            onChangeHome = onChangeHome,
            accountActions = AccountActions(onSwitch, {}, {}),
        )
    }

    /** An amount as the page shows it in the default unit. */
    private fun formatAmount(msat: Long) = text(DesignR.string.amount_bitcoin, NumberFormat.getIntegerInstance().format(msat / 1000))

    private fun shownWarnings() =
        compose.onAllNodes(hasTestTag(MINT_KEY_WARNING_TAG), useUnmergedTree = true).fetchSemanticsNodes().count { it.size.width > 0 }

    @Test
    fun nothingIsDrawnUntilTheMintsAreRead() {
        show(MintsPageState(home = home, others = listOf(cash), loaded = false))
        compose.onNodeWithText("home.example", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mints_add)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mints_empty)).assertDoesNotExist()
    }

    @Test
    fun theHomeCardComesFirstAndSaysHomeMint() {
        show(MintsPageState(home = home, others = listOf(cash)))
        val label = compose.onNode(hasText(text(R.string.settings_home_mint), ignoreCase = true)).fetchSemanticsNode()
        val other = compose.onNode(hasText("cash.example", substring = true)).fetchSemanticsNode()
        assertTrue("the home card is above the others", label.boundsInRoot.top < other.boundsInRoot.top)
    }

    @Test
    fun theHomeCardShowsItsAmountOnceWithNotesAndAddresses() {
        show(MintsPageState(home = home, others = emptyList()))
        assertEquals(1, compose.onAllNodes(hasText(formatAmount(21_000_000), substring = true)).fetchSemanticsNodes().size)
        compose.onNodeWithText(plural(DesignR.plurals.notes_count, 3), substring = true).assertExists()
        compose.onNodeWithText(plural(R.plurals.account_mints_addresses, 1), substring = true).assertExists()
    }

    @Test
    fun otherMintsShowTheirAmountNotesAndTrust() {
        show(MintsPageState(home = home, others = listOf(cash)))
        compose.onNodeWithText(formatAmount(4_000_000)).assertExists()
        compose.onNodeWithText(plural(DesignR.plurals.notes_count, 1), substring = true).assertExists()
        compose.onNode(hasContentDescription(text(DesignR.string.notes_trusted)), useUnmergedTree = true).assertExists()
    }

    @Test
    fun anUntrustedMintHasNoTrustMark() {
        show(MintsPageState(home = null, others = listOf(card("x.example", 1_000, notes = 1, trusted = false))))
        compose.onNode(hasContentDescription(text(DesignR.string.notes_trusted)), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aMintTheAccountDoesNotUseSaysSoAndShowsNoAmount() {
        show(MintsPageState(home = home, others = listOf(old)))
        compose.onNodeWithText(text(R.string.mints_card_unused)).assertExists()
        compose.onNodeWithText(formatAmount(0)).assertDoesNotExist()
        compose.onNodeWithText("—").assertDoesNotExist()
    }

    @Test
    fun theHomeCardOffersChangingTheHomeOnlyWhenAllowed() {
        var changing = false
        show(MintsPageState(home = home, canChangeHome = true), onChangeHome = { changing = true })
        compose.onNodeWithText(text(R.string.mint_change_home)).performClick()
        assertTrue(changing)
    }

    @Test
    fun withoutLeaveToChangeTheHomeThereIsNoButton() {
        show(MintsPageState(home = home, canChangeHome = false))
        compose.onNodeWithText(text(R.string.mint_change_home)).assertDoesNotExist()
    }

    @Test
    fun tappingACardOpensItsMint() {
        val opened = mutableListOf<String>()
        show(MintsPageState(home = home, others = listOf(cash)), onOpen = { opened += it })
        compose.onNodeWithText("cash.example", substring = true).performClick()
        compose.onNode(hasText(text(R.string.settings_home_mint), ignoreCase = true)).performClick()
        assertEquals(listOf("cash.example", "home.example"), opened)
    }

    @Test
    fun theHomeWithAChangedKeyShowsTheWarningStillSaysHomeAndOffersTheChange() {
        show(MintsPageState(home = home.copy(keyChanged = true, trusted = false), canChangeHome = true))
        assertEquals(1, shownWarnings())
        compose.onNode(hasText(text(R.string.settings_home_mint), ignoreCase = true)).assertExists()
        compose.onNodeWithText(text(R.string.mint_change_home)).assertExists()
    }

    @Test
    fun anotherMintWithAChangedKeyShowsTheWarningInPlaceOfItsNotesAndNoTrust() {
        show(MintsPageState(home = home, others = listOf(cash.copy(keyChanged = true, trusted = false))))
        assertEquals(1, shownWarnings())
        compose.onNodeWithText(plural(DesignR.plurals.notes_count, 1)).assertDoesNotExist()
        compose.onNode(hasContentDescription(text(DesignR.string.notes_trusted)), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aMintNotAnsweringSaysSoInHomesWords() {
        show(MintsPageState(home = home, others = listOf(cash.copy(answering = false))))
        compose.onNodeWithText(text(DesignR.string.mint_not_answering_short), substring = true).assertExists()
    }

    @Test
    fun withoutAHomeTheWayToChooseOneShowsAndTheOtherMintsStay() {
        var missing: NoHome? = null
        show(MintsPageState(home = null, others = listOf(cash), noHome = NoHome.PickHome), onNoHome = { missing = it })
        compose.onNodeWithText(text(R.string.settings_no_home)).assertExists()
        compose.onNodeWithText("cash.example", substring = true).assertExists()
        compose.onNodeWithText(text(R.string.settings_no_home_action)).performClick()
        assertEquals(NoHome.PickHome, missing)
    }

    @Test
    fun withoutMintsThePageSaysWhyAndAddingIsTheWayForward() {
        var adding = false
        show(MintsPageState(noHome = NoHome.NoMint), onAdd = { adding = true })
        compose.onNodeWithText(text(R.string.mints_empty)).assertExists()
        compose.onNodeWithText(text(R.string.mints_add)).performClick()
        assertTrue(adding)
    }

    @Test
    fun addingAMintShowsOnlyWhenTheBrandLetsUsersAdd() {
        show(MintsPageState(home = home, custom = false))
        compose.onNodeWithText(text(R.string.mints_add)).assertDoesNotExist()
    }

    @Test
    fun aMissingSuggestionCanStillBeAdded() {
        var adding = false
        show(MintsPageState(home = home, custom = false, offered = listOf("mint2.example")), onAdd = { adding = true })
        compose.onNodeWithText(text(R.string.mints_add)).performScrollTo().performClick()
        assertTrue(adding)
    }

    @Test
    fun theAccountMarkShowsByTheRuleAndSwitchesAccount() {
        val accounts = AccountChoice(listOf(summary("a", "Savings"), summary("b", "Daily")), "a", emptyMap(), shown = true)
        var switched: String? = null
        show(MintsPageState(home = home, accounts = accounts), onSwitch = { switched = it })
        compose.onNodeWithContentDescription("Savings").performClick()
        compose.onNodeWithText("Daily").performClick()
        assertEquals("b", switched)
    }

    @Test
    fun theAccountMarkIsAbsentWhenThereIsNoChoice() {
        show(MintsPageState(home = home, accounts = AccountChoice(listOf(summary("a", "Savings")), "a", emptyMap(), shown = false)))
        compose.onNodeWithContentDescription("Savings").assertDoesNotExist()
    }

    @Test
    fun eachCardIsReadAsOne() {
        show(MintsPageState(home = home, others = listOf(cash)))
        val read = compose.onNode(hasText("cash.example", substring = true)).fetchSemanticsNode().config
        val words = read.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString(" ")
        assertTrue(words, formatAmount(4_000_000) in words)
        assertTrue(words, plural(DesignR.plurals.notes_count, 1) in words)
    }

    private fun showLarge(state: MintsPageState) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            MintsScreen(
                state,
                onBack = {},
                onOpen = {},
                onAdd = {},
                onNoHome = {},
                onChangeHome = {},
                accountActions = AccountActions({}, {}, {}),
            )
        }
    }

    private fun assertWhole(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertTrue("$text is not cut", !layout.single().hasVisualOverflow)
        assertTrue("$text is drawn", node.size.width > 0)
    }

    @Test
    fun aLongHostAndAHugeAmountAreShownWholeAtTwiceTheFontSize() {
        showLarge(MintsPageState(home = card("a-very-long-subdomain.of-a-mint.example", 2_100_000_000_000_000, notes = 9, home = true)))
        assertWhole(formatAmount(2_100_000_000_000_000))
    }

    @Test
    fun anotherMintsHugeAmountIsWholeAndLeavesTheNameHalfTheRowAtTwiceTheFontSize() {
        showLarge(
            MintsPageState(home = home, others = listOf(card("a-very-long-subdomain.of-a-mint.example", 2_100_000_000_000_000, notes = 9)))
        )
        assertWhole(formatAmount(2_100_000_000_000_000))
        assertWhole("a-very-long-subdomain.of-a-mint.example")
        // The amount gets smaller before it takes more than half the room beside the mark: the name keeps the other half.
        val amount = compose.onNodeWithText(formatAmount(2_100_000_000_000_000), useUnmergedTree = true).fetchSemanticsNode().size.width
        val root = compose.onRoot().fetchSemanticsNode().size.width
        assertTrue("the amount is $amount of $root", amount <= root / 3)
    }

    @Test
    @Config(qualifiers = "de")
    fun german() {
        show(MintsPageState(home = home, others = listOf(old)))
        compose.onNodeWithText("In diesem Konto nicht genutzt").assertExists()
    }
}
