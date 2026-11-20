package whitewallet.feature.home

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.PREVIEW_MILLIS
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.wallet.AccountMint

// The home screen and moving money between accounts, in their everyday state (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only.

private const val HOME = "mint.example"
private const val OTHER = "cash.example"

private val satoshi = Account("a1", "Satoshi", PREVIEW_MILLIS, walletId = "w1", homeHost = HOME)
private val hal = Account("a2", "Hal", PREVIEW_MILLIS, walletId = "w1", index = 1, homeHost = HOME)

private fun activity(id: String, kind: ActivityKind, msat: Long, host: String, counterparty: String?, minutesBefore: Int) =
    Activity(
        id,
        satoshi.id,
        kind,
        msat,
        feeMsat = if (kind == ActivityKind.Sent) 2_000 else null,
        host = host,
        counterparty = counterparty,
        certified = null,
        noteCount = null,
        automatic = false,
        status = ActivityStatus.Done,
        journalId = null,
        createdAtMillis = PREVIEW_MILLIS - minutesBefore * 60_000L,
    )

/** Two accounts, money on the home mint and another one, and a few payments. */
internal val homeState =
    HomeState(
        accounts = listOf(AccountSummary(satoshi, 25_000_000, used = true), AccountSummary(hal, 5_000_000, used = true)),
        activeAccountId = satoshi.id,
        balanceMsat = 25_000_000,
        activity =
            listOf(
                activity("p3", ActivityKind.Sent, 2_100_000, HOME, "coffee@shop.example", 5),
                activity("p2", ActivityKind.Claimed, 4_000_000, OTHER, null, 90),
                activity("p1", ActivityKind.Received, 21_000_000, HOME, null, 600),
            ),
        mints =
            listOf(
                AccountMint(HOME, isHome = true, balanceMsat = 21_000_000, noteCount = 2, addresses = 1, trusted = true),
                AccountMint(OTHER, isHome = false, balanceMsat = 4_000_000, noteCount = 1, addresses = 0, trusted = true),
            ),
        receiveHosts = setOf(HOME, OTHER),
    )

internal val moveFundsState =
    MoveFundsState(
        from = AccountSummary(satoshi, 25_000_000, used = true),
        targets = listOf(AccountSummary(hal, 5_000_000, used = true)),
        places = mapOf(satoshi.id to 0, hal.id to 1),
        targetId = hal.id,
    )

@WalletPreviews
@Composable
private fun HomePreview() =
    HomeScreen(
        LocalAppName.current,
        homeState,
        onSend = {},
        onReceive = {},
        onRefresh = {},
        onSettings = {},
        onBackup = {},
        onDismissBackup = {},
        onOpenAddresses = {},
        accountActions = AccountActions(onSwitch = {}, onManage = {}, onMove = {}),
    )

@WalletPreviews
@Composable
private fun MoveFundsPreview() = MoveFundsScreen(moveFundsState, onBack = {}, onTarget = {}, onEverything = {}, onAmount = {}, onMove = {})
