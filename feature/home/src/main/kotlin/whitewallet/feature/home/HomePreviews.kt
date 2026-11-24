package whitewallet.feature.home

import androidx.compose.runtime.Composable
import whitewallet.brand.MaintenanceStep
import whitewallet.brand.WalletTask
import whitewallet.core.designsystem.AccountActions
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.PREVIEW_MILLIS
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.MoveCheck
import whitewallet.core.wallet.MoveFailure
import whitewallet.core.wallet.MovePending
import whitewallet.core.wallet.MoveResult
import whitewallet.core.wallet.MoveStayed
import whitewallet.core.wallet.StayReason

// The home screen and moving money between accounts, in their everyday state, the move's success screen, and the states that tell of a
// problem or a wait (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only.

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

@WalletPreviews
@Moment
@Composable
private fun FundsMovedPreview() =
    MoveFundsScreen(
        moveFundsState.copy(result = MoveResult.Moved(5_000_000)),
        onBack = {},
        onTarget = {},
        onEverything = {},
        onAmount = {},
        onMove = {},
    )

private fun received(id: String, host: String, msat: Long, state: ReceivedState) =
    ReceivedNote(
        id,
        satoshi.id,
        host,
        withdrawUrl = "https://$host/withdraw",
        k1 = null,
        certificate = null,
        declaredMsat = msat,
        amountMsat = msat,
        lockedUntilMillis = null,
        lockedForSeconds = null,
        state = state,
        journalId = null,
        receivedAtMillis = PREVIEW_MILLIS,
        updatedAtMillis = PREVIEW_MILLIS,
    )

@Composable
private fun HomeWith(state: HomeState) =
    HomeScreen(
        LocalAppName.current,
        state,
        onSend = {},
        onReceive = {},
        onRefresh = {},
        onSettings = {},
        onBackup = {},
        onDismissBackup = {},
        onOpenAddresses = {},
        accountActions = AccountActions(onSwitch = {}, onManage = {}, onMove = {}),
    )

/** The phone has no connection: Home says what it shows may be out of date and updates by itself once it is back. */
@WalletPreviews @Composable private fun HomeOfflinePreview() = HomeWith(homeState.copy(offline = true))

/** The last refresh failed: Home says so and offers to try again. */
@WalletPreviews @Composable private fun HomeUpdateFailedPreview() = HomeWith(homeState.copy(refreshFailed = true))

/** The wallet combines notes by itself: Home shows what it works on instead of figures about to change. */
@WalletPreviews
@Composable
private fun HomeWorkingPreview() = HomeWith(homeState.copy(task = WalletTask.Maintaining(MaintenanceStep.Combining)))

/** The home mint did not answer the last time the wallet asked: a card says what that stops and offers to check again. */
@WalletPreviews
@Composable
private fun HomeMintNotAnsweringPreview() =
    HomeWith(homeState.copy(mints = homeState.mints.map { if (it.isHome) it.copy(answering = false) else it }))

/** A mint presents another signing key than the pinned one: a card warns, and the money held there is told as stuck under the balance. */
@WalletPreviews @Composable private fun HomeKeyChangedPreview() = HomeWith(homeState.copy(keyChangedHosts = setOf(OTHER)))

private val unknownAtHome =
    AccountMint(
        HOME,
        isHome = true,
        balanceMsat = 15_000_000,
        noteCount = 1,
        addresses = 1,
        trusted = true,
        unknownCount = 2,
        unknownMsat = 6_000_000,
    )

/** The home mint says it does not know two of the notes: they are set aside, and a card explains and offers to ask again. */
@WalletPreviews
@Composable
private fun HomeUnknownNotesPreview() =
    HomeWith(
        homeState.copy(
            mints = listOf(unknownAtHome, homeState.mints.last()),
            unknownNotes = listOf(unknownAtHome),
        )
    )

/** A note received as a link waits for its mint: Home tells what is incoming, next to the balance and never in it. */
@WalletPreviews
@Composable
private fun HomeIncomingPreview() = HomeWith(homeState.copy(received = listOf(received("r1", OTHER, 1_000_000, ReceivedState.Waiting))))

/** An address of the account that its mint no longer routes to this wallet: a card says so and leads to the addresses. */
@WalletPreviews @Composable private fun HomeAddressInactivePreview() = HomeWith(homeState.copy(inactiveAddress = "satoshi@$HOME"))

/** The move did not happen as asked: the amount is more than the account holds. */
@WalletPreviews
@Composable
private fun MoveFundsFailedPreview() =
    MoveFundsScreen(
        moveFundsState.copy(result = MoveResult.Failed(MoveFailure.InsufficientFunds)),
        onBack = {},
        onTarget = {},
        onEverything = {},
        onAmount = {},
        onMove = {},
    )

/** A mint was why nothing moved: the money is safe where it was, why is said per mint, and the move can be tried again. */
@WalletPreviews
@Composable
private fun MoveFundsMintProblemPreview() =
    MoveFundsScreen(
        moveFundsState.copy(
            result =
                MoveResult.Failed(
                    MoveFailure.Refused,
                    stayed =
                        listOf(MoveStayed(HOME, 21_000_000, StayReason.Unreachable), MoveStayed(OTHER, 4_000_000, StayReason.KeyChanged)),
                )
        ),
        onBack = {},
        onTarget = {},
        onEverything = {},
        onAmount = {},
        onMove = {},
    )

/** A move was sent without an answer: the money is out of the balance until a refresh finds out whether it moved. */
@WalletPreviews
@Composable
private fun MoveFundsPendingPreview() =
    MoveFundsScreen(
        moveFundsState.copy(result = MoveResult.Pending(listOf(MovePending(HOME, 21_000_000)))),
        onBack = {},
        onTarget = {},
        onEverything = {},
        onAmount = {},
        onMove = {},
    )

/** Part of the money moved and part stayed where it was because a mint did not answer: what stayed, why, and a way to try again. */
@WalletPreviews
@Composable
private fun MoveFundsPartlyMovedPreview() =
    MoveFundsScreen(
        moveFundsState.copy(result = MoveResult.Moved(21_000_000, stayed = listOf(MoveStayed(OTHER, 4_000_000, StayReason.Unreachable)))),
        onBack = {},
        onTarget = {},
        onEverything = {},
        onAmount = {},
        onMove = {},
    )

/** A move that cannot be whole, found out before any of it is made: the dialog asks whether what can move should. */
@WalletPreviews
@Composable
private fun MoveFundsPartialPreview() =
    MoveFundsScreen(
        moveFundsState.copy(partial = MoveCheck(21_000_000, listOf(MoveStayed(OTHER, 4_000_000, StayReason.Unreachable)))),
        onBack = {},
        onTarget = {},
        onEverything = {},
        onAmount = {},
        onMove = {},
    )
