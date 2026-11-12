package whitewallet.feature.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.wallet.MoveCheck
import whitewallet.core.wallet.MoveFailure
import whitewallet.core.wallet.MovePending
import whitewallet.core.wallet.MoveResult
import whitewallet.core.wallet.MoveStayed
import whitewallet.core.wallet.StayReason

@RunWith(RobolectricTestRunner::class)
class MoveFundsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(id: String, name: String, balanceMsat: Long = 0, walletId: String = "w1") =
        AccountSummary(Account(id, name, 0, walletId = walletId), balanceMsat)

    @Test
    fun bothAccountsCarryTheirMarksAndTheTargetIsARadioChoice() {
        var target: String? = null
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000),
                targets = listOf(summary("b", "Savings"), summary("c", "Daily")),
                places = mapOf("a" to 0, "b" to 1, "c" to 2),
                targetId = "b",
            )
        compose.setContent {
            MoveFundsScreen(state, onBack = {}, onTarget = { target = it }, onEverything = {}, onAmount = {}, onMove = {})
        }
        listOf("M", "S", "D").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNode(hasText("Savings")).assertIsSelected()
        compose.onNodeWithText("Daily").performClick()
        assertEquals("c", target)
    }

    @Test
    fun withSeveralWalletsEveryAccountIsShownUnderItsWallet() {
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000, walletId = "w2"),
                targets = listOf(summary("b", "Main"), summary("c", "Savings", walletId = "w2")),
                places = mapOf("b" to 0, "a" to 1, "c" to 2),
                walletNumbers = mapOf("w1" to 1, "w2" to 2),
                walletNames = mapOf("w1" to "Travel"),
            )
        compose.setContent { MoveFundsScreen(state, onBack = {}, onTarget = {}, onEverything = {}, onAmount = {}, onMove = {}) }
        // The same account name in two wallets: the wallet says which is which, by the user's name for it or its number.
        compose.onNodeWithText("Wallet 2 · Main", substring = true).assertExists()
        // As in the account switcher, the other wallets stay folded away until asked for.
        compose.onNodeWithText("Travel").assertDoesNotExist()
        compose.onNodeWithText("1 other wallet").performClick()
        compose.onNodeWithText("1 other wallet").assertDoesNotExist()
        val headers = compose.onAllNodes(hasText("Wallet 2") or hasText("Travel")).fetchSemanticsNodes()
        // The wallet the money leaves comes first.
        assertEquals(listOf("Wallet 2", "Travel"), headers.map { it.config[SemanticsProperties.Text].single().text })
    }

    @Test
    fun withOneWalletNoWalletIsNamed() {
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000),
                targets = listOf(summary("b", "Savings")),
                places = mapOf("a" to 0, "b" to 1),
                walletNumbers = mapOf("w1" to 1),
            )
        compose.setContent { MoveFundsScreen(state, onBack = {}, onTarget = {}, onEverything = {}, onAmount = {}, onMove = {}) }
        compose.onNodeWithText("Wallet 1", substring = true).assertDoesNotExist()
    }

    @Test
    fun withNoOtherAccountInTheWalletInUseTheOtherWalletsAreOpen() {
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000),
                targets = listOf(summary("b", "Main", walletId = "w2")),
                places = mapOf("a" to 0, "b" to 1),
                walletNumbers = mapOf("w1" to 1, "w2" to 2),
            )
        compose.setContent { MoveFundsScreen(state, onBack = {}, onTarget = {}, onEverything = {}, onAmount = {}, onMove = {}) }
        compose.onNodeWithText("Wallet 2").assertExists()
        compose.onNodeWithText("1 other wallet").assertDoesNotExist()
    }

    @Test
    fun aMintTheMoveAddsToTheOtherWalletIsNamedBeforeMoving() {
        val state =
            MoveFundsState(
                from = summary("a", "Main", 5_000),
                targets = listOf(summary("b", "Main", walletId = "w2")),
                places = mapOf("a" to 0, "b" to 1),
                targetId = "b",
                walletNumbers = mapOf("w1" to 1, "w2" to 2),
                walletNames = mapOf("w2" to "Travel"),
                addsMints = listOf("mint.example"),
            )
        compose.setContent { MoveFundsScreen(state, onBack = {}, onTarget = {}, onEverything = {}, onAmount = {}, onMove = {}) }
        compose.onNodeWithText("mint.example will be added to the mints of Travel.").assertExists()
    }

    private val twoWallets =
        MoveFundsState(
            from = summary("a", "Main", 9_000),
            targets = listOf(summary("b", "Savings", walletId = "w2")),
            places = mapOf("a" to 0, "b" to 1),
            targetId = "b",
            walletNumbers = mapOf("w1" to 1, "w2" to 2),
        )

    private fun stayed(reason: StayReason, host: String = "mint-b.example") = MoveStayed(host, 9_000, reason)

    private fun show(
        state: MoveFundsState,
        onBack: () -> Unit = {},
        onMove: () -> Unit = {},
        onMint: (String) -> Unit = {},
        onConfirmPartial: () -> Unit = {},
        onCancelPartial: () -> Unit = {},
    ) = compose.setContent {
        MoveFundsScreen(
            state,
            onBack,
            onTarget = {},
            onEverything = {},
            onAmount = {},
            onMove = onMove,
            onMint = onMint,
            onConfirmPartial = onConfirmPartial,
            onCancelPartial = onCancelPartial,
        )
    }

    @Test
    fun aMoveThatLeftMoneyBehindSaysHowMuchIsStillWhereAndOffersToTryAgain() {
        var again = 0
        show(twoWallets.copy(result = MoveResult.Moved(3_000, listOf(stayed(StayReason.Unreachable)))), onMove = { again++ })
        // The amount that stayed, with the account it is still in.
        compose.onNode(hasText("Main", substring = true) and hasText("9", substring = true)).assertExists()
        compose.onNodeWithText(text(R.string.move_stayed_unreachable, "mint-b.example")).assertExists()
        compose.onNodeWithText(text(R.string.move_done)).assertExists()
        compose.onNodeWithText(text(R.string.move_retry)).performClick()
        assertEquals(1, again)
    }

    @Test
    fun aMoveThatWasWholeOffersNothingMore() {
        show(twoWallets.copy(result = MoveResult.Moved(9_000)))
        compose.onNodeWithText(text(R.string.move_retry)).assertDoesNotExist()
        compose.onNodeWithText("Main", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.move_done)).assertExists()
    }

    @Test
    fun aSigningKeyTheMintChangedOpensTheMintInsteadOfTryingAgain() {
        var opened: String? = null
        val failed = MoveResult.Failed(MoveFailure.Refused, "the signing key of this host changed", listOf(stayed(StayReason.KeyChanged)))
        show(twoWallets.copy(result = failed), onMint = { opened = it })
        compose.onNodeWithText(text(R.string.move_none)).assertExists()
        compose.onNodeWithText(text(R.string.move_stayed_key, "mint-b.example")).assertExists()
        // Nothing a mint or the wallet said in English is shown.
        compose.onNodeWithText("signing key of this host", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.move_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.move_mint_details, "mint-b.example")).performClick()
        assertEquals("mint-b.example", opened)
    }

    @Test
    fun aKeyTheOtherWalletDiffersOnIsLookedAtInThatWalletWhichIsNamed() {
        val result = MoveResult.Moved(3_000, listOf(stayed(StayReason.OtherWalletKey)))
        show(twoWallets.copy(result = result, walletNames = mapOf("w2" to "Travel")))
        compose.onNodeWithText(text(R.string.move_stayed_other_key, "mint-b.example", "Travel")).assertExists()
        // The page of the mint is the wallet in use's, where nothing is wrong: it isn't offered, nor is trying again.
        compose.onNodeWithText(text(R.string.move_mint_details, "mint-b.example")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.move_retry)).assertDoesNotExist()
        // With several wallets, the account the money is still in is named with its wallet.
        compose
            .onNode(hasText(text(R.string.home_account_in_wallet, text(R.string.accounts_wallet, 1), "Main"), substring = true))
            .assertExists()
    }

    @Test
    fun whereMintsArentNamedWhatStayedIsSaidWithoutSayingWhere() {
        val result = MoveResult.Moved(3_000, listOf(stayed(StayReason.Unreachable), stayed(StayReason.OtherWalletKey, "mint-c.example")))
        show(twoWallets.copy(result = result, showMints = false))
        compose.onNodeWithText(text(R.string.move_stayed_unreachable_unnamed)).assertExists()
        compose.onNodeWithText(text(R.string.move_stayed_blocked_unnamed)).assertExists()
        compose.onNodeWithText("mint-b.example", substring = true).assertDoesNotExist()
        compose.onNodeWithText("mint-c.example", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.move_retry)).assertExists()
    }

    @Test
    fun aMoveThatCantBeWholeIsAskedAboutBeforeAnyOfItIsMade() {
        var confirmed = 0
        var cancelled = 0
        show(
            twoWallets.copy(partial = MoveCheck(3_000, listOf(stayed(StayReason.Unreachable)))),
            onConfirmPartial = { confirmed++ },
            onCancelPartial = { cancelled++ },
        )
        compose.onNodeWithText(text(R.string.move_partial_title)).assertExists()
        compose.onNodeWithText(text(R.string.move_stayed_unreachable, "mint-b.example")).assertExists()
        compose.onNodeWithText(text(R.string.move_partial_cancel)).performClick()
        assertEquals(0 to 1, confirmed to cancelled)
        // The button says how much moves.
        compose.onNode(hasText("3", substring = true) and hasClickAction()).performClick()
        assertEquals(1 to 1, confirmed to cancelled)
    }

    @Test
    fun whatWasSentWithoutAnAnswerIsSaidApartFromWhatMovedAndWhatStayed() {
        val result =
            MoveResult.Moved(
                3_000,
                stayed = listOf(stayed(StayReason.Unreachable, "mint-c.example")),
                pending = listOf(MovePending("mint-b.example", 5_000)),
            )
        show(twoWallets.copy(result = result))
        compose.onNode(hasText("mint-b.example", substring = true) and hasText("5", substring = true)).assertExists()
        compose.onNodeWithText(text(R.string.move_stayed_unreachable, "mint-c.example")).assertExists()
        // Trying again is for the mint that didn't answer before anything was sent.
        compose.onNodeWithText(text(R.string.move_retry)).assertExists()
    }

    @Test
    fun aMoveOfWhichNothingIsKnownYetOffersNothingButToWait() {
        show(twoWallets.copy(result = MoveResult.Pending(listOf(MovePending("mint-b.example", 9_000)))))
        compose.onNode(hasText("mint-b.example", substring = true) and hasText("9", substring = true)).assertExists()
        compose.onNodeWithText(text(R.string.move_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.move_none)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.move_done)).assertExists()
    }

    @Test
    fun whereMintsArentNamedWhatIsWaitingIsOneAmount() {
        val pending = listOf(MovePending("mint-b.example", 5_000), MovePending("mint-c.example", 4_000))
        show(twoWallets.copy(result = MoveResult.Pending(pending), showMints = false))
        compose.onNode(hasText("9", substring = true)).assertExists()
        compose.onNodeWithText("mint-b.example", substring = true).assertDoesNotExist()
    }
}
