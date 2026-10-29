package whitewallet.feature.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import whitewallet.brand.MaintenanceStep
import whitewallet.brand.ReleaseStage
import whitewallet.brand.WalletStatus
import whitewallet.brand.WalletTask
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DecorationContext
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.core.designsystem.LocalBuildLabel
import whitewallet.core.designsystem.LocalReleaseStage
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.wallet.HistoryFilterOption
import whitewallet.core.wallet.MaintenanceNotice

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeState, onOpenMint: (String) -> Unit = {}, onToggle: () -> Unit = {}) = compose.setContent {
        HomeScreen(
            "Wallet", state, onSend = {}, onReceive = {}, onRefresh = {}, onSettings = {}, onBackup = {}, onDismissBackup = {},
            onChangeAddress = {}, accountActions = AccountActions({}, {}, {}), onToggleBalance = onToggle, onOpenMint = onOpenMint,
        )
    }

    @Test
    fun tappingTheBalanceHidesIt() {
        compose.setContent {
            var hidden by remember { mutableStateOf(false) }
            HomeScreen(
                "Wallet", HomeState(balanceMsat = 845_000, balanceHidden = hidden), onSend = {}, onReceive = {}, onRefresh = {},
                onSettings = {}, onBackup = {}, onDismissBackup = {}, onChangeAddress = {}, accountActions = AccountActions({}, {}, {}),
                onToggleBalance = { hidden = !hidden },
            )
        }
        compose.onNodeWithText("₿845").performClick()
        compose.onNodeWithText("₿845").assertDoesNotExist()
        compose.onNodeWithText("••••••").assertExists()
    }

    @Test
    fun anEmptyWalletSaysHowMoneyGetsIn() {
        show(HomeState(activeAccountId = "a"))
        compose.onNodeWithText("Nothing here yet. Share your address to get paid, or claim a note.").assertExists()
    }

    @Test
    fun aWalletWithMoneyHasNoEmptyHint() {
        show(HomeState(activeAccountId = "a", balanceMsat = 845_000))
        compose.onNodeWithText("Nothing here yet. Share your address to get paid, or claim a note.").assertDoesNotExist()
    }

    @Test
    fun aHiddenBalanceDoesNotGiveAwayAnEmptyWallet() {
        show(HomeState(activeAccountId = "a", balanceHidden = true))
        compose.onNodeWithText("Nothing here yet. Share your address to get paid, or claim a note.").assertDoesNotExist()
    }

    @Test
    fun aBetaCarriesItsBadgeInTheTitle() {
        compose.setContent {
            CompositionLocalProvider(LocalReleaseStage provides ReleaseStage.Beta) {
                HomeScreen(
                    "Wallet", HomeState(), onSend = {}, onReceive = {}, onRefresh = {}, onSettings = {}, onBackup = {}, onDismissBackup = {},
                    onChangeAddress = {}, accountActions = AccountActions({}, {}, {}), onToggleBalance = {},
                )
            }
        }
        compose.onNodeWithText("Beta").assertExists()
    }

    @Test
    fun aDebugBuildNamesItselfInTheTitleBesideTheBadge() {
        compose.setContent {
            CompositionLocalProvider(LocalReleaseStage provides ReleaseStage.Beta, LocalBuildLabel provides "v0.3.0-21-gb1593f9-dirty") {
                HomeScreen(
                    "Wallet", HomeState(), onSend = {}, onReceive = {}, onRefresh = {}, onSettings = {}, onBackup = {}, onDismissBackup = {},
                    onChangeAddress = {}, accountActions = AccountActions({}, {}, {}), onToggleBalance = {},
                )
            }
        }
        compose.onNodeWithText("Beta").assertExists()
        compose.onNodeWithText("v0.3.0-21-gb1593f9-dirty").assertExists()
    }

    @Test
    fun aStableDebugBuildStillNamesItself() {
        compose.setContent {
            CompositionLocalProvider(LocalBuildLabel provides "v1.0.0") {
                HomeScreen(
                    "Wallet", HomeState(), onSend = {}, onReceive = {}, onRefresh = {}, onSettings = {}, onBackup = {}, onDismissBackup = {},
                    onChangeAddress = {}, accountActions = AccountActions({}, {}, {}), onToggleBalance = {},
                )
            }
        }
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithText("v1.0.0").assertExists()
    }

    @Test
    fun aStableReleaseHasNoBadgeInTheTitle() {
        show(HomeState())
        compose.onNodeWithText("Beta").assertDoesNotExist()
    }

    @Test
    fun aSingleAccountBrandShowsNoAccounts() {
        val main = AccountSummary(Account("a", "Main", 0, "mint.example"), null, 0)
        show(HomeState(accounts = listOf(main), activeAccountId = "a", showAccounts = false))
        compose.onNodeWithText("Main").assertDoesNotExist()
    }

    @Test
    fun theSwitcherOffersNoNewAccountAtTheLimit() {
        val main = AccountSummary(Account("a", "Main", 0, "mint.example"), null, 0)
        val savings = AccountSummary(Account("b", "Savings", 1, "mint.example"), null, 0)
        show(HomeState(accounts = listOf(main, savings), activeAccountId = "a", canAddAccount = false))
        compose.onNodeWithText("Main").performClick()
        compose.onNodeWithText("Savings").assertExists()
        compose.onNodeWithText("Add account").assertDoesNotExist()
    }

    @Test
    fun aChangedSigningKeyLeadsToTheDetails() {
        var opened: String? = null
        show(HomeState(keyChangedHost = "mint.example"), onOpenMint = { opened = it })
        compose.onNodeWithText("mint.example changed its signing key").assertExists()
        compose.onNodeWithText("Details").performClick()
        assertEquals("mint.example", opened)
    }

    private fun combined(feeMsat: Long) = Activity("op:1", "a", ActivityKind.Combined, 1_701_000, feeMsat, "mint.example", null, true, 2,
        automatic = true, status = ActivityStatus.Done, journalId = "1", createdAtMillis = 0)

    @Test
    fun aFeeTheMintPaidBackShowsAsARefund() {
        show(HomeState(activity = listOf(combined(-1_000))))
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Show note maintenance").performClick()
        compose.onNodeWithText("Fee refunded ₿1", substring = true).assertExists()
        compose.onNodeWithText("Fee ₿", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun aRefundInGerman() {
        show(HomeState(activity = listOf(combined(-1_000))))
        compose.onNodeWithText("Verlauf").performClick()
        compose.onNodeWithText("Pflege der Scheine anzeigen").performClick()
        compose.onNodeWithText("Gebühr erstattet 1 ₿", substring = true).assertExists()
    }

    private fun row(id: String, kind: ActivityKind, automatic: Boolean = false, counterparty: String? = null) =
        Activity(id, "a", kind, 1_000_000, null, "mint.example", counterparty, true, 2, automatic = automatic, status = ActivityStatus.Done,
            journalId = id, createdAtMillis = 0)

    private val mixed = listOf(
        row("r", ActivityKind.Received),
        row("s", ActivityKind.Sent, counterparty = "bob@mint.example"),
        row("n", ActivityKind.SentNote),
        row("m", ActivityKind.MovedMint, automatic = true, counterparty = "home.example"),
        row("c", ActivityKind.Combined, automatic = true),
        row("x", ActivityKind.Split),
    )

    @Test
    fun whoDidAnEntryShowsNextToItsTitle() {
        show(HomeState(activity = mixed))
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Note maintenance").performClick()
        compose.onAllNodesWithText("Automatic").assertCountEquals(1)
        compose.onAllNodesWithText("By you").assertCountEquals(1)
        compose.onNodeWithText("Sent a note").assertDoesNotExist()
        // A move home that collecting did by itself is automatic too; a payment the user made says nothing.
        compose.onNodeWithText("Moved").performClick()
        compose.onAllNodesWithText("Automatic").assertCountEquals(2)
    }

    @Test
    fun theHistoryNarrowsToAKindOfEntry() {
        show(HomeState(activity = mixed))
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Split a note in two").assertDoesNotExist()
        compose.onNodeWithText("Sent to bob@mint.example").assertExists()

        compose.onNodeWithText("Sent").performClick()
        compose.onNodeWithText("Sent to bob@mint.example").assertExists()
        compose.onNodeWithText("Sent a note").assertExists()
        compose.onNodeWithText("Moved to home.example").assertDoesNotExist()

        compose.onNodeWithText("Moved").performClick()
        compose.onNodeWithText("Moved to home.example").assertExists()
        compose.onNodeWithText("Split a note in two").assertDoesNotExist()

        compose.onNodeWithText("Sent").performClick()
        compose.onNodeWithText("Moved").performClick()
        compose.onNodeWithText("Split a note in two").assertDoesNotExist()
        compose.onNodeWithText("Sent a note").assertExists()
    }

    @Test
    fun aBrandCanOfferOnlyTheMaintenanceSwitchOrNoFilter() {
        show(HomeState(activity = listOf(row("n", ActivityKind.SentNote), row("m", ActivityKind.MovedMint), row("x", ActivityKind.Split)),
            historyFilter = HistoryFilterOption.Maintenance))
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Moved").assertDoesNotExist()
        compose.onNodeWithText("Note maintenance").performClick()
        compose.onNodeWithText("Split a note in two").assertExists()
        compose.onNodeWithText("Sent a note").assertExists()
    }

    @Test
    fun withoutFiltersMaintenanceStaysHidden() {
        show(HomeState(activity = mixed, historyFilter = HistoryFilterOption.None))
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Note maintenance").assertDoesNotExist()
        compose.onNodeWithText("Split a note in two").assertDoesNotExist()
        compose.onNodeWithText("Sent a note").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun theFiltersInGerman() {
        show(HomeState(activity = mixed))
        compose.onNodeWithText("Verlauf").performClick()
        compose.onNodeWithText("Verschoben").performClick()
        compose.onNodeWithText("Automatisch").assertExists()
        compose.onNodeWithText("Gesendet").assertExists()
    }

    @Test
    fun aMessageSaysWhatTheWalletDidAndWhatItDidToTheBalance() {
        show(HomeState(notice = MaintenanceNotice.Combined(notes = 2, into = 1, amountMsat = 63_000, balanceChangeMsat = 1_000)))
        compose.onNodeWithText("Combined 2 notes into one of ₿63. You got ₿1 in fees back.").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun theMessagesInGerman() {
        show(HomeState(notice = MaintenanceNotice.Renewed(notes = 3, balanceChangeMsat = null)))
        compose.onNodeWithText("3 Scheine erneuert").assertExists()
    }

    @Test
    fun withoutNotesOnlyTheBalanceIsTold() {
        show(HomeState(notice = MaintenanceNotice.BalanceChanged(-1_000)))
        compose.onNodeWithText("Fees paid: ₿1.").assertExists()
    }

    @Test
    fun detailsShowNoteMaintenanceInTheHistoryEvenWithoutFilters() {
        var shown = false
        compose.setContent {
            HomeScreen(
                "Wallet", HomeState(activity = listOf(row("x", ActivityKind.Split), row("n", ActivityKind.SentNote)), historyFilter = HistoryFilterOption.None,
                    notice = MaintenanceNotice.Split(1, 2, -1_000)),
                onSend = {}, onReceive = {}, onRefresh = {}, onSettings = {}, onBackup = {}, onDismissBackup = {}, onChangeAddress = {},
                accountActions = AccountActions({}, {}, {}), onNoticeShown = { shown = true },
            )
        }
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Split a note in two").assertExists()
        compose.onNodeWithText("Sent a note").assertExists()
        assertEquals(true, shown)
        // Turned off, maintenance is hidden again, and so is its switch.
        compose.onNodeWithText("Note maintenance").performClick()
        compose.onNodeWithText("Split a note in two").assertDoesNotExist()
        compose.onNodeWithText("Note maintenance").assertDoesNotExist()
    }

    /** Home with [state], changeable by the test while it is shown. */
    private fun showLive(initial: HomeState): (HomeState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            HomeScreen(
                "Wallet", state, onSend = {}, onReceive = {}, onRefresh = {}, onSettings = {}, onBackup = {}, onDismissBackup = {},
                onChangeAddress = {}, accountActions = AccountActions({}, {}, {}),
            )
        }
        return { state = it }
    }

    @Test
    fun aBalanceNotReadYetIsNotShownAsNothing() {
        show(HomeState(loaded = false, activeAccountId = "a"))
        compose.onNodeWithText("₿0").assertDoesNotExist()
        compose.onNodeWithText("Nothing here yet. Share your address to get paid, or claim a note.").assertDoesNotExist()
    }

    @Test
    fun workThatTakesAWhileShowsTheLoadingScreenInsteadOfTheBalance() {
        compose.mainClock.autoAdvance = false
        val set = showLive(HomeState(activeAccountId = "a", balanceMsat = 845_000, task = WalletTask.Updating))
        compose.mainClock.advanceTimeByFrame()
        // Never shown yet: not shown stale either.
        compose.onNodeWithText("₿845").assertDoesNotExist()
        compose.onNodeWithText("Updating your balance…").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(LOADING_GRACE_MILLIS + 100)
        compose.onNodeWithText("Updating your balance…").assertExists()
        compose.onNodeWithText("₿845").assertDoesNotExist()

        set(HomeState(activeAccountId = "a", balanceMsat = 900_000, task = WalletTask.Maintaining(MaintenanceStep.Combining, "single")))
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Combining your notes…").assertExists()

        set(HomeState(activeAccountId = "a", balanceMsat = 900_000))
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Combining your notes…").assertDoesNotExist()
        compose.onNodeWithText("₿900").assertExists()
    }

    @Test
    fun quickWorkLeavesTheBalanceOnScreen() {
        compose.mainClock.autoAdvance = false
        val set = showLive(HomeState(activeAccountId = "a", balanceMsat = 845_000))
        compose.mainClock.advanceTimeByFrame()
        set(HomeState(activeAccountId = "a", balanceMsat = 845_000, task = WalletTask.Updating))
        compose.mainClock.advanceTimeBy(LOADING_GRACE_MILLIS / 2)
        compose.onNodeWithText("₿845").assertExists()
        set(HomeState(activeAccountId = "a", balanceMsat = 845_000))
        compose.mainClock.advanceTimeBy(LOADING_GRACE_MILLIS)
        compose.onNodeWithText("Updating your balance…").assertDoesNotExist()
        compose.onNodeWithText("₿845").assertExists()
    }

    @Test
    fun checkingTheNotesCountsThemOff() {
        compose.mainClock.autoAdvance = false
        showLive(HomeState(task = WalletTask.CheckingNotes(2, 5)))
        compose.mainClock.advanceTimeBy(LOADING_GRACE_MILLIS + 100)
        compose.onNodeWithText("Checking note 3 of 5…").assertExists()
    }

    @Test
    fun aStrategyNamesItsOwnSteps() {
        compose.mainClock.autoAdvance = false
        showLive(HomeState(task = WalletTask.Maintaining(MaintenanceStep.Splitting, "small"), strategyText = R.string.home_send))
        compose.mainClock.advanceTimeBy(LOADING_GRACE_MILLIS + 100)
        compose.onNodeWithText("Send").assertExists()
        compose.onNodeWithText("Splitting your notes…").assertDoesNotExist()
    }

    @Test
    fun withoutNotesOrMintsMaintenanceIsUpdatingTheBalance() {
        compose.mainClock.autoAdvance = false
        val set = showLive(HomeState(task = WalletTask.Maintaining(MaintenanceStep.Combining, "single"), notesVisible = false))
        compose.mainClock.advanceTimeBy(LOADING_GRACE_MILLIS + 100)
        compose.onNodeWithText("Updating your balance…").assertExists()
        set(HomeState(task = WalletTask.Maintaining(MaintenanceStep.MovingHome), mintsVisible = false))
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Updating your balance…").assertExists()
    }

    @Test
    fun aBrandDrawsTheLoadingScreenKnowingTheTask() {
        val design = object : BrandDesign {
            @Composable
            override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                if (anchor == Anchor.Loading) Text("brand: ${context.status}") else default()
            }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                HomeScreen(
                    "Wallet", HomeState(task = WalletTask.Updating), onSend = {}, onReceive = {}, onRefresh = {}, onSettings = {}, onBackup = {},
                    onDismissBackup = {}, onChangeAddress = {}, accountActions = AccountActions({}, {}, {}),
                )
            }
        }
        compose.mainClock.advanceTimeBy(LOADING_GRACE_MILLIS + 100)
        compose.onNodeWithText("brand: ${WalletStatus.Working(WalletTask.Updating)}", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Updating your balance…").assertExists()
    }
}
