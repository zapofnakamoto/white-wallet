package whitewallet.feature.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.HistoryFilterOption
import whitewallet.core.wallet.MaintenanceNotice

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: HomeState, onOpenMint: (String) -> Unit = {}, onToggle: () -> Unit = {}) = compose.setContent {
        HomeScreen(
            "Wallet",
            state,
            onSend = {},
            onReceive = {},
            onRefresh = {},
            onSettings = {},
            onBackup = {},
            onDismissBackup = {},
            onOpenAddresses = {},
            accountActions = AccountActions({}, {}, {}),
            onToggleBalance = onToggle,
            onOpenMint = onOpenMint,
        )
    }

    @Test
    fun homeOffersToScanAnyQrCode() {
        var scans = 0
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(balanceMsat = 845_000),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                onScan = { scans++ },
            )
        }
        compose.onNodeWithContentDescription(text(R.string.home_scan)).performClick()
        assertEquals(1, scans)
        // Only the wallet tab offers it.
        compose.onNodeWithText(text(R.string.home_tab_history)).performClick()
        compose.onNodeWithContentDescription(text(R.string.home_scan)).assertDoesNotExist()
    }

    @Test
    fun aWalletWithoutAMintAsksForOneUntilItHasOne() {
        var added = 0
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(activeAccountId = "a", showAddMintPrompt = true),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                onAddMint = { added++ },
            )
        }
        compose.onNodeWithText(text(R.string.home_add_mint_title)).assertExists()
        // No way to put it off: only adding a mint takes it away.
        compose.onNodeWithText(text(R.string.home_backup_later)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_add_mint_action)).performClick()
        assertEquals(1, added)
    }

    @Test
    fun severalAlertsShowTheMostImportantAndCountTheOthers() {
        show(HomeState(activeAccountId = "a", inactiveAddress = "me@mint.example", showBackupPrompt = true, showScanPrompt = true))
        compose.onNodeWithText(text(R.string.home_address_inactive_title)).assertExists()
        compose.onNodeWithText(text(R.string.home_backup_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_scan_title)).assertDoesNotExist()
        // The others open in a sheet over Home.
        compose.onNodeWithText(plural(R.plurals.home_alerts_more, 2, 2)).performClick()
        compose.onNodeWithText(text(R.string.home_backup_title)).assertExists()
        compose.onNodeWithText(text(R.string.home_scan_title)).assertExists()
    }

    @Test
    fun oneAlertIsNotCounted() {
        show(HomeState(activeAccountId = "a", showBackupPrompt = true))
        compose.onNodeWithText(text(R.string.home_backup_title)).assertExists()
        compose.onNodeWithText(plural(R.plurals.home_alerts_more, 1, 1)).assertDoesNotExist()
    }

    @Test
    fun aWalletWhoseScanIsNotFinishedOffersItUntilItIs() {
        var scans = 0
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(activeAccountId = "a", showScanPrompt = true),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                onScanMints = { scans++ },
            )
        }
        compose.onNodeWithText(text(R.string.home_scan_title)).assertExists()
        compose.onNodeWithText(text(R.string.home_scan_body)).assertExists()
        compose.onNodeWithText(text(R.string.home_scan_action)).performClick()
        assertEquals(1, scans)
    }

    @Test
    fun withoutAScanHomeShowsNoScanButton() {
        show(HomeState(balanceMsat = 845_000))
        compose.onNodeWithContentDescription(text(R.string.home_scan)).assertDoesNotExist()
    }

    @Test
    fun tappingTheBalanceHidesIt() {
        compose.setContent {
            var hidden by remember { mutableStateOf(false) }
            HomeScreen(
                "Wallet",
                HomeState(balanceMsat = 845_000, balanceHidden = hidden),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                onToggleBalance = { hidden = !hidden },
            )
        }
        compose.onNodeWithText("₿845").performClick()
        compose.onNodeWithText("₿845").assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.amount_hidden)).assertExists()
    }

    @Test
    fun anEmptyWalletSaysHowMoneyGetsIn() {
        show(HomeState(activeAccountId = "a"))
        compose.onNodeWithText(text(R.string.home_wallet_empty)).assertExists()
    }

    @Test
    fun aWalletWithMoneyHasNoEmptyHint() {
        show(HomeState(activeAccountId = "a", balanceMsat = 845_000))
        compose.onNodeWithText(text(R.string.home_wallet_empty)).assertDoesNotExist()
    }

    @Test
    fun aHiddenBalanceDoesNotGiveAwayAnEmptyWallet() {
        show(HomeState(activeAccountId = "a", balanceHidden = true))
        compose.onNodeWithText(text(R.string.home_wallet_empty)).assertDoesNotExist()
    }

    @Test
    fun aBetaCarriesItsBadgeInTheTitle() {
        compose.setContent {
            CompositionLocalProvider(LocalReleaseStage provides ReleaseStage.Beta) {
                HomeScreen(
                    "Wallet",
                    HomeState(),
                    onSend = {},
                    onReceive = {},
                    onRefresh = {},
                    onSettings = {},
                    onBackup = {},
                    onDismissBackup = {},
                    onOpenAddresses = {},
                    accountActions = AccountActions({}, {}, {}),
                    onToggleBalance = {},
                )
            }
        }
        compose.onNodeWithText(text(DesignR.string.release_stage_beta)).assertExists()
    }

    @Test
    fun aDebugBuildNamesItselfInTheTitleBesideTheBadge() {
        compose.setContent {
            CompositionLocalProvider(LocalReleaseStage provides ReleaseStage.Beta, LocalBuildLabel provides "v0.3.0-21-gb1593f9-dirty") {
                HomeScreen(
                    "Wallet",
                    HomeState(),
                    onSend = {},
                    onReceive = {},
                    onRefresh = {},
                    onSettings = {},
                    onBackup = {},
                    onDismissBackup = {},
                    onOpenAddresses = {},
                    accountActions = AccountActions({}, {}, {}),
                    onToggleBalance = {},
                )
            }
        }
        compose.onNodeWithText(text(DesignR.string.release_stage_beta)).assertExists()
        compose.onNodeWithText("v0.3.0-21-gb1593f9-dirty").assertExists()
    }

    @Test
    fun aStableDebugBuildStillNamesItself() {
        compose.setContent {
            CompositionLocalProvider(LocalBuildLabel provides "v1.0.0") {
                HomeScreen(
                    "Wallet",
                    HomeState(),
                    onSend = {},
                    onReceive = {},
                    onRefresh = {},
                    onSettings = {},
                    onBackup = {},
                    onDismissBackup = {},
                    onOpenAddresses = {},
                    accountActions = AccountActions({}, {}, {}),
                    onToggleBalance = {},
                )
            }
        }
        compose.onNodeWithText(text(DesignR.string.release_stage_beta)).assertDoesNotExist()
        compose.onNodeWithText("v1.0.0").assertExists()
    }

    @Test
    fun aStableReleaseHasNoBadgeInTheTitle() {
        show(HomeState())
        compose.onNodeWithText(text(DesignR.string.release_stage_beta)).assertDoesNotExist()
    }

    @Test
    fun aSingleAccountBrandShowsNoAccounts() {
        val main = AccountSummary(Account("a", "Main", 0), 0)
        show(HomeState(accounts = listOf(main), activeAccountId = "a", showAccounts = false))
        compose.onNodeWithText("Main").assertDoesNotExist()
        compose.onNodeWithContentDescription("Main").assertDoesNotExist()
    }

    @Test
    fun theSwitcherSwitchesAndLinksToWhereWalletsAreManaged() {
        val main = AccountSummary(Account("a", "Main", 0), 0)
        val savings = AccountSummary(Account("b", "Savings", 1, walletId = "a", index = 1), 0)
        show(HomeState(accounts = listOf(main, savings), activeAccountId = "a"))
        // One wallet: the mark in the top bar is all there is of the account, read out by its name.
        compose.onNodeWithText("Main").assertDoesNotExist()
        compose.onNodeWithContentDescription("Main").performClick()
        compose.onNodeWithText("Savings").assertExists()
        compose.onNodeWithText(text(R.string.accounts_manage)).assertExists()
    }

    @Test
    fun theAccountMarkIsOnEveryTab() {
        val main = AccountSummary(Account("a", "Main", 0), 0)
        val savings = AccountSummary(Account("b", "Savings", 1, walletId = "a", index = 1), 0)
        show(HomeState(accounts = listOf(main, savings), activeAccountId = "b"))
        compose.onNodeWithText(text(R.string.home_tab_history)).performClick()
        compose.onNodeWithContentDescription("Savings").assertExists()
    }

    @Test
    fun withSeveralWalletsTheWalletIsNamedWithTheAccount() {
        val main = AccountSummary(Account("a", "Main", 0), 0)
        val other = AccountSummary(Account("c", "Main", 2), 0)
        show(HomeState(accounts = listOf(main, other), activeAccountId = "c", walletNames = mapOf("a" to "Family")))
        val name = text(R.string.home_account_in_wallet, text(R.string.accounts_wallet, 2), "Main")
        compose.onNodeWithContentDescription(name).assertExists()
        compose.onNodeWithText(name).performClick()
        // The switcher: the wallet in use, the other one a step away.
        compose.onNodeWithText(plural(R.plurals.accounts_other_wallets, 1, 1)).assertExists()
    }

    @Test
    fun aNamedWalletGoesByItsName() {
        val main = AccountSummary(Account("a", "Main", 0), 0)
        val other = AccountSummary(Account("c", "Main", 2), 0)
        show(HomeState(accounts = listOf(main, other), activeAccountId = "a", walletNames = mapOf("a" to "Family")))
        compose.onNodeWithText(text(R.string.home_account_in_wallet, "Family", "Main")).assertExists()
    }

    @Test
    fun refreshingIsAPullAndAnActionForWhoCannotPull() {
        var refreshed = 0
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(balanceMsat = 845_000),
                onSend = {},
                onReceive = {},
                onRefresh = { refreshed++ },
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
            )
        }
        val actions = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode()
        val refresh = actions.config[SemanticsActions.CustomActions].single { it.label == text(DesignR.string.refresh) }
        refresh.action()
        assertEquals(1, refreshed)
    }

    @Test
    fun aChangedSigningKeyLeadsToTheDetails() {
        var opened: String? = null
        show(HomeState(keyChangedHost = "mint.example"), onOpenMint = { opened = it })
        compose.onNodeWithText(text(R.string.home_key_changed_title, "mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.home_key_changed_action)).performClick()
        assertEquals("mint.example", opened)
    }

    private fun combined(feeMsat: Long) =
        Activity(
            "op:1",
            "a",
            ActivityKind.Combined,
            1_701_000,
            feeMsat,
            "mint.example",
            null,
            true,
            2,
            automatic = true,
            status = ActivityStatus.Done,
            journalId = "1",
            createdAtMillis = 0,
        )

    @Test
    fun aFeeTheMintPaidBackShowsAsARefund() {
        show(HomeState(activity = listOf(combined(-1_000))))
        compose.onNodeWithText(text(R.string.home_tab_history)).performClick()
        compose.onNodeWithText(text(R.string.history_show_maintenance)).performClick()
        compose.onNodeWithText(text(R.string.activity_fee_refunded, "₿1"), substring = true).assertExists()
        compose.onNodeWithText(text(R.string.activity_fee, "₿"), substring = true).assertDoesNotExist()
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
        Activity(
            id,
            "a",
            kind,
            1_000_000,
            null,
            "mint.example",
            counterparty,
            true,
            2,
            automatic = automatic,
            status = ActivityStatus.Done,
            journalId = id,
            createdAtMillis = 0,
        )

    private val mixed =
        listOf(
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
        compose.onNodeWithText(text(R.string.home_tab_history)).performClick()
        compose.onNodeWithText(text(R.string.history_filter_maintenance)).performClick()
        compose.onAllNodesWithText(text(R.string.activity_automatic)).assertCountEquals(1)
        compose.onAllNodesWithText(text(R.string.activity_manual)).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.activity_sent_note)).assertDoesNotExist()
        // A move home that collecting did by itself is automatic too; a payment the user made says nothing.
        compose.onNodeWithText(text(R.string.history_filter_moved)).performClick()
        compose.onAllNodesWithText(text(R.string.activity_automatic)).assertCountEquals(2)
    }

    @Test
    fun theHistoryNarrowsToAKindOfEntry() {
        show(HomeState(activity = mixed))
        compose.onNodeWithText(text(R.string.home_tab_history)).performClick()
        compose.onNodeWithText(text(R.string.activity_split)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.activity_sent_to, "bob@mint.example")).assertExists()

        compose.onNodeWithText(text(R.string.history_filter_sent)).performClick()
        compose.onNodeWithText(text(R.string.activity_sent_to, "bob@mint.example")).assertExists()
        compose.onNodeWithText(text(R.string.activity_sent_note)).assertExists()
        compose.onNodeWithText(text(R.string.activity_moved_mint, "home.example")).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.history_filter_moved)).performClick()
        compose.onNodeWithText(text(R.string.activity_moved_mint, "home.example")).assertExists()
        compose.onNodeWithText(text(R.string.activity_split)).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.history_filter_sent)).performClick()
        compose.onNodeWithText(text(R.string.history_filter_moved)).performClick()
        compose.onNodeWithText(text(R.string.activity_split)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.activity_sent_note)).assertExists()
    }

    @Test
    fun aBrandCanOfferOnlyTheMaintenanceSwitchOrNoFilter() {
        show(
            HomeState(
                activity = listOf(row("n", ActivityKind.SentNote), row("m", ActivityKind.MovedMint), row("x", ActivityKind.Split)),
                historyFilter = HistoryFilterOption.Maintenance,
            )
        )
        compose.onNodeWithText(text(R.string.home_tab_history)).performClick()
        compose.onNodeWithText(text(R.string.history_filter_moved)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.history_filter_maintenance)).performClick()
        compose.onNodeWithText(text(R.string.activity_split)).assertExists()
        compose.onNodeWithText(text(R.string.activity_sent_note)).assertExists()
    }

    @Test
    fun withoutFiltersMaintenanceStaysHidden() {
        show(HomeState(activity = mixed, historyFilter = HistoryFilterOption.None))
        compose.onNodeWithText(text(R.string.home_tab_history)).performClick()
        compose.onNodeWithText(text(R.string.history_filter_maintenance)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.activity_split)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.activity_sent_note)).assertExists()
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

    private fun unknown(askedAtMillis: Long = 0) =
        AccountMint(
            "mint.example",
            true,
            0,
            0,
            0,
            trusted = true,
            unknownCount = 2,
            unknownMsat = 5_000,
            unknownAskedAtMillis = askedAtMillis,
        )

    @Test
    fun askingAgainAlwaysHasAnAnswer() {
        show(HomeState(unknownNotes = listOf(unknown()), unknownCheck = UnknownCheck.Still(2)))
        compose.onNodeWithText(plural(R.plurals.notice_unknown_still, 2)).assertExists()
    }

    @Test
    fun aMintThatDidNotAnswerIsNamedWhereMintsAre() {
        show(HomeState(unknownCheck = UnknownCheck.Unreachable("mint.example")))
        compose.onNodeWithText(text(R.string.notice_unknown_unreachable, "mint.example")).assertExists()
    }

    @Test
    fun aMintThatDidNotAnswerIsNotNamedWhereMintsAreHidden() {
        show(HomeState(unknownCheck = UnknownCheck.Unreachable("mint.example"), mintsVisible = false))
        compose.onNodeWithText(text(R.string.notice_unknown_unreachable_unnamed)).assertExists()
    }

    @Test
    fun notesBackInTheBalanceAreTold() {
        show(HomeState(unknownCheck = UnknownCheck.Back(1)))
        compose.onNodeWithText(plural(R.plurals.notice_unknown_back, 1, 1)).assertExists()
    }

    @Test
    fun theUnknownNotesCardSaysWhenTheMintWasLastAskedAndThatNothingIsToDo() {
        show(HomeState(unknownNotes = listOf(unknown(askedAtMillis = System.currentTimeMillis()))))
        compose.onNodeWithText(text(R.string.home_unknown_help)).assertExists()
        compose.onNodeWithText(text(R.string.home_unknown_asked_now)).assertExists()
    }

    @Test
    fun theUnknownNotesCardSaysHowLongAgoTheMintWasAsked() {
        show(HomeState(unknownNotes = listOf(unknown(askedAtMillis = System.currentTimeMillis() - 5 * 60_000))))
        compose.onNodeWithText(text(R.string.home_unknown_asked, "5 minutes ago")).assertExists()
    }

    @Test
    fun theUnknownNotesCardLeadsToTheNotesWhereTheAppShowsThem() {
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(unknownNotes = listOf(unknown())),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                notesTab = NotesTabSlot(refreshing = false, onRefresh = {}, actions = {}, content = { Text("the notes") }),
            )
        }
        compose.onNodeWithText(plural(R.plurals.home_unknown_show, 2)).performClick()
        compose.onNodeWithText("the notes").assertExists()
    }

    @Test
    fun withoutANotesViewTheUnknownNotesCardOnlyAsksAgain() {
        var asked = 0
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(unknownNotes = listOf(unknown())),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                onRecheckUnknown = { asked++ },
            )
        }
        compose.onNodeWithText(plural(R.plurals.home_unknown_show, 2)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_unknown_action)).performClick()
        assertEquals(1, asked)
    }

    @Test
    fun withoutNotesOnlyTheBalanceIsTold() {
        show(HomeState(notice = MaintenanceNotice.BalanceChanged(-1_000)))
        compose.onNodeWithText(text(R.string.notice_fees_paid, "₿1")).assertExists()
    }

    @Test
    fun detailsShowNoteMaintenanceInTheHistoryEvenWithoutFilters() {
        var shown = false
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(
                    activity = listOf(row("x", ActivityKind.Split), row("n", ActivityKind.SentNote)),
                    historyFilter = HistoryFilterOption.None,
                    notice = MaintenanceNotice.Split(1, 2, -1_000),
                ),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                onNoticeShown = { shown = true },
            )
        }
        compose.onNodeWithText(text(R.string.notice_details)).performClick()
        compose.onNodeWithText(text(R.string.activity_split)).assertExists()
        compose.onNodeWithText(text(R.string.activity_sent_note)).assertExists()
        assertEquals(true, shown)
        // Turned off, maintenance is hidden again, and so is its switch.
        compose.onNodeWithText(text(R.string.history_filter_maintenance)).performClick()
        compose.onNodeWithText(text(R.string.activity_split)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.history_filter_maintenance)).assertDoesNotExist()
    }

    /** Home with [state], changeable by the test while it is shown. */
    private fun showLive(initial: HomeState): (HomeState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            HomeScreen(
                "Wallet",
                state,
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
            )
        }
        return {
            state = it
            // A write from the test is seen by the next frame, as one from the app is.
            Snapshot.sendApplyNotifications()
        }
    }

    @Test
    fun aBalanceNotReadYetIsNotShownAsNothing() {
        show(HomeState(loaded = false, activeAccountId = "a"))
        compose.onNodeWithText("₿0").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_wallet_empty)).assertDoesNotExist()
    }

    @Test
    fun workThatTakesAWhileShowsTheLoadingScreenInsteadOfTheBalance() {
        compose.mainClock.autoAdvance = false
        val set = showLive(HomeState(activeAccountId = "a", balanceMsat = 845_000, task = WalletTask.Updating))
        compose.mainClock.advanceTimeByFrame()
        // Never shown yet: not shown stale either.
        compose.onNodeWithText("₿845").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_working_updating)).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(SHOWN_AFTER + 100)
        compose.onNodeWithText(text(R.string.home_working_updating)).assertExists()
        compose.onNodeWithText("₿845").assertDoesNotExist()

        set(HomeState(activeAccountId = "a", balanceMsat = 900_000, task = WalletTask.Maintaining(MaintenanceStep.Combining, "single")))
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.home_working_combining)).assertExists()

        // The work ended just after the loading screen came up: it stays for its shortest time rather than blinking.
        set(HomeState(activeAccountId = "a", balanceMsat = 900_000))
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.home_working_combining)).assertExists()
        compose.onNodeWithText("₿900").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(SHOWN_AT_LEAST)
        compose.onNodeWithText(text(R.string.home_working_combining)).assertDoesNotExist()
        compose.onNodeWithText("₿900").assertExists()
    }

    @Test
    fun figuresThatTakeAWhileToReadShowTheLoadingIndicator() {
        compose.mainClock.autoAdvance = false
        val set = showLive(HomeState(activeAccountId = "a", balanceMsat = 845_000, loaded = false))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER / 2)
        compose.onNodeWithContentDescription(text(DesignR.string.loading)).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(SHOWN_AFTER)
        compose.onNodeWithContentDescription(text(DesignR.string.loading)).assertExists()
        compose.onNodeWithText("₿845").assertDoesNotExist()
        set(HomeState(activeAccountId = "a", balanceMsat = 845_000))
        compose.mainClock.advanceTimeBy(SHOWN_AT_LEAST)
        compose.onNodeWithContentDescription(text(DesignR.string.loading)).assertDoesNotExist()
        compose.onNodeWithText("₿845").assertExists()
    }

    @Test
    fun aSlowStartIsOneWaitThroughTheRefreshAlreadyRunning() {
        compose.mainClock.autoAdvance = false
        val set = showLive(HomeState(activeAccountId = "a", balanceMsat = 845_000, loaded = false))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER + SHOWN_AT_LEAST + 100)
        compose.onNodeWithContentDescription(text(DesignR.string.loading)).assertExists()
        // The figures are read while the first refresh runs: they don't show between the two waits.
        set(HomeState(activeAccountId = "a", balanceMsat = 845_000, refreshing = true))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER / 2)
        compose.onNodeWithText("₿845").assertDoesNotExist()
        set(HomeState(activeAccountId = "a", balanceMsat = 845_000, refreshing = true, task = WalletTask.Updating))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER / 2)
        compose.onNodeWithText(text(R.string.home_working_updating)).assertExists()
        compose.onNodeWithText("₿845").assertDoesNotExist()
        set(HomeState(activeAccountId = "a", balanceMsat = 900_000))
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("₿900").assertExists()
    }

    @Test
    fun aQuickStartShowsTheBalanceAtOnceWhileItRefreshes() {
        compose.mainClock.autoAdvance = false
        val set = showLive(HomeState(activeAccountId = "a", balanceMsat = 845_000, loaded = false))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER / 2)
        set(HomeState(activeAccountId = "a", balanceMsat = 845_000, refreshing = true))
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("₿845").assertExists()
    }

    @Test
    fun quickWorkLeavesTheBalanceOnScreen() {
        compose.mainClock.autoAdvance = false
        val set = showLive(HomeState(activeAccountId = "a", balanceMsat = 845_000))
        compose.mainClock.advanceTimeByFrame()
        set(HomeState(activeAccountId = "a", balanceMsat = 845_000, task = WalletTask.Updating))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER / 2)
        compose.onNodeWithText("₿845").assertExists()
        set(HomeState(activeAccountId = "a", balanceMsat = 845_000))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER)
        compose.onNodeWithText(text(R.string.home_working_updating)).assertDoesNotExist()
        compose.onNodeWithText("₿845").assertExists()
    }

    @Test
    fun checkingTheNotesCountsThemOff() {
        compose.mainClock.autoAdvance = false
        showLive(HomeState(task = WalletTask.CheckingNotes(2, 5)))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER + 100)
        compose.onNodeWithText("Checking note 3 of 5…").assertExists()
    }

    @Test
    fun aStrategyNamesItsOwnSteps() {
        compose.mainClock.autoAdvance = false
        showLive(HomeState(task = WalletTask.Maintaining(MaintenanceStep.Splitting, "small"), strategyText = R.string.home_send))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER + 100)
        compose.onNodeWithText(text(R.string.home_send)).assertExists()
        compose.onNodeWithText(text(R.string.home_working_splitting)).assertDoesNotExist()
    }

    @Test
    fun withoutNotesOrMintsMaintenanceIsUpdatingTheBalance() {
        compose.mainClock.autoAdvance = false
        val set = showLive(HomeState(task = WalletTask.Maintaining(MaintenanceStep.Combining, "single"), notesVisible = false))
        compose.mainClock.advanceTimeBy(SHOWN_AFTER + 100)
        compose.onNodeWithText(text(R.string.home_working_updating)).assertExists()
        set(HomeState(task = WalletTask.Maintaining(MaintenanceStep.MovingHome), mintsVisible = false))
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.home_working_updating)).assertExists()
    }

    @Test
    fun aBrandDrawsTheLoadingScreenKnowingTheTask() {
        val design =
            object : BrandDesign {
                @Composable
                override fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
                    if (anchor == Anchor.Loading) Text("brand: ${context.status}") else default()
                }
            }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalBrandDesign provides design) {
                HomeScreen(
                    "Wallet",
                    HomeState(task = WalletTask.Updating),
                    onSend = {},
                    onReceive = {},
                    onRefresh = {},
                    onSettings = {},
                    onBackup = {},
                    onDismissBackup = {},
                    onOpenAddresses = {},
                    accountActions = AccountActions({}, {}, {}),
                )
            }
        }
        compose.mainClock.advanceTimeBy(SHOWN_AFTER + 100)
        compose.onNodeWithText("brand: ${WalletStatus.Working(WalletTask.Updating)}", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.home_working_updating)).assertExists()
    }

    private fun received(
        id: String,
        state: ReceivedState,
        amountMsat: Long? = 2_000_000,
        declaredMsat: Long? = null,
        lockedUntil: Long? = null,
    ) =
        ReceivedNote(
            id.padEnd(64, '0'),
            "a",
            "mint.example",
            "w",
            "k1",
            null,
            declaredMsat,
            amountMsat,
            lockedUntil,
            null,
            state,
            null,
            1,
            1,
        )

    @Test
    fun aNoteNotClaimedYetIsShownNextToTheBalanceNotInIt() {
        show(
            HomeState(
                activeAccountId = "a",
                received = listOf(received("r1", ReceivedState.Waiting), received("r2", ReceivedState.Locked, null, 1_000_000)),
            )
        )
        compose.onNodeWithText("₿0").assertExists()
        compose.onNodeWithText(text(DesignR.string.received_total, "₿3,000")).assertExists()
        compose.onNodeWithText(text(DesignR.string.received_title)).assertExists()
        compose.onNodeWithText("Not claimed yet. Tried again when online.").assertExists()
        compose.onNodeWithText(text(DesignR.string.received_unconfirmed, "₿1,000")).assertExists()
        compose.onNodeWithText("Locked for now. Tried again on every refresh.").assertExists()
        compose.onNodeWithText("Your wallet claims these on its own", substring = true).assertExists()
        compose.onNodeWithText(text(R.string.home_wallet_empty)).assertDoesNotExist()
    }

    @Test
    fun notesHandedOutAndNotClaimedYetAreShownUnderTheBalanceAndLeadToHistoryWithoutANotesTab() {
        show(HomeState(activeAccountId = "a", balanceMsat = 5_000_000, sharedMsat = 3_000_000))
        compose.onNodeWithText("₿5,000").assertExists()
        compose.onNodeWithText(text(R.string.home_wallet_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_shared_total, "₿3,000")).performClick()
        compose.onNodeWithText(text(R.string.home_shared_total, "₿3,000")).assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.home_tab_history)).assertCountEquals(2)
    }

    @Test
    fun aBalanceOnSeveralMintsIsShownPerMintAndEachLeadsToItsPage() {
        var opened: String? = null
        val mints = listOf(AccountMint("a.example", true, 6_000_000, 1, 0, true), AccountMint("b.example", false, 2_000_000, 2, 0, true))
        show(HomeState(activeAccountId = "a", balanceMsat = 8_000_000, mints = mints), onOpenMint = { opened = it })
        compose.onNodeWithText("₿8,000").assertExists()
        compose.onNodeWithText("₿6,000").assertExists()
        compose.onNodeWithText(text(R.string.home_mint_is_home)).assertExists()
        compose.onNodeWithText("b.example").performClick()
        assertEquals("b.example", opened)
    }

    @Test
    fun manyMintsShareALastRowThatLeadsToTheAccountsMints() {
        var opened = 0
        val mints =
            listOf(AccountMint("home.example", true, 0, 0, 0, true)) +
                (1..5).map { AccountMint("m$it.example", false, 1_000_000L * (6 - it), 1, 0, true) }
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(activeAccountId = "a", balanceMsat = 15_000_000, mints = mints),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                onOpenMints = { opened++ },
            )
        }
        // The home mint has its row though empty, then the two largest; the other three share a row.
        compose.onNodeWithText("home.example").assertExists()
        compose.onNodeWithText("m2.example").assertExists()
        compose.onNodeWithText("m3.example").assertDoesNotExist()
        compose.onNodeWithText("₿6,000").assertExists()
        compose.onNodeWithText(plural(R.plurals.home_more_mints, 3, 3)).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theAccountMarkHasTheRefreshButtonsPlace() {
        val main = AccountSummary(Account("a", "Main", 0), 0)
        val savings = AccountSummary(Account("b", "Savings", 1, walletId = "a", index = 1), 0)
        show(HomeState(accounts = listOf(main, savings), activeAccountId = "a"))
        compose.onNodeWithContentDescription(text(DesignR.string.refresh)).assertDoesNotExist()
    }

    @Test
    fun anAppOfOneAccountKeepsTheRefreshButton() {
        val main = AccountSummary(Account("a", "Main", 0), 0)
        show(HomeState(accounts = listOf(main), activeAccountId = "a", showAccounts = false))
        compose.onNodeWithContentDescription(text(DesignR.string.refresh)).assertExists()
    }

    @Test
    fun aBalanceOnOneMintIsTheTotalAlone() {
        show(
            HomeState(activeAccountId = "a", balanceMsat = 6_000_000, mints = listOf(AccountMint("a.example", true, 6_000_000, 1, 0, true)))
        )
        compose.onNodeWithText("a.example").assertDoesNotExist()
    }

    @Test
    fun nothingHandedOutSaysNothingAndAHiddenBalanceHidesItsAmount() {
        show(HomeState(activeAccountId = "a", balanceMsat = 5_000_000, balanceHidden = true, sharedMsat = 3_000_000))
        compose.onNodeWithText("₿3,000", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.home_shared_title)).assertExists()
    }

    @Test
    fun aHiddenBalanceHidesWhatIsIncomingToo() {
        show(HomeState(activeAccountId = "a", balanceHidden = true, received = listOf(received("r1", ReceivedState.Waiting))))
        compose.onNodeWithText("₿2,000", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Not claimed yet. Tried again when online.").assertExists()
    }

    @Test
    fun aNoteSomeoneElseSpentCanBeRemoved() {
        var dismissed: String? = null
        val gone = received("r1", ReceivedState.Gone)
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(activeAccountId = "a", received = listOf(gone)),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                onDismissReceived = { dismissed = it },
            )
        }
        compose.onNodeWithText(text(DesignR.string.received_gone)).assertExists()
        compose.onNodeWithText("received, not claimed yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.received_dismiss)).performClick()
        assertEquals(gone.id, dismissed)
    }

    @Test
    fun aNoteKeptForAChangedKeyLeadsToTheMintsDetails() {
        var opened: String? = null
        show(HomeState(activeAccountId = "a", received = listOf(received("r1", ReceivedState.KeyChanged))), onOpenMint = { opened = it })
        compose.onNodeWithText(text(R.string.home_key_changed_title, "mint.example")).assertExists()
        compose
            .onNodeWithText("Its mint changed its signing key. Claimed once you accept the new key in the mint's details.")
            .assertExists()
        compose.onNodeWithText(text(DesignR.string.received_total, "₿2,000")).assertExists()
        compose.onNodeWithText(text(R.string.home_key_changed_action)).performClick()
        assertEquals("mint.example", opened)
    }

    @Test
    fun aNoteItsMintKeptRefusingSaysSoAndCanBeRemoved() {
        var dismissed: String? = null
        val received = received("r1", ReceivedState.Refused)
        // Given up at the first refresh after a week (the default notes.received.giveUpAfter).
        val refused = received.copy(refusedSinceMillis = received.updatedAtMillis - 7 * 86_400_000L - 15 * 60_000L)
        compose.setContent {
            HomeScreen(
                "Wallet",
                HomeState(activeAccountId = "a", received = listOf(refused)),
                onSend = {},
                onReceive = {},
                onRefresh = {},
                onSettings = {},
                onBackup = {},
                onDismissBackup = {},
                onOpenAddresses = {},
                accountActions = AccountActions({}, {}, {}),
                onDismissReceived = { dismissed = it },
            )
        }
        compose.onNodeWithText("Its mint kept refusing it for 7 days, so your wallet stopped trying.").assertExists()
        compose.onNodeWithText("received, not claimed yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(DesignR.string.received_dismiss)).performClick()
        assertEquals(refused.id, dismissed)
    }

    private companion object {
        /** The loading times a test gets (LocalLoadingTimes). */
        const val SHOWN_AFTER = 400L
        const val SHOWN_AT_LEAST = 500L
    }
}
