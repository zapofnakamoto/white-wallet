package whitewallet.feature.settings

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.AccountActions
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.PREVIEW_MILLIS
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.model.Account
import whitewallet.core.model.AccountChoice
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.AmountUnit
import whitewallet.core.model.Mint
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.WalletGroup
import whitewallet.core.model.WalletRecord
import whitewallet.core.wallet.AddressEntry
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.Collected
import whitewallet.core.wallet.DerivedKey
import whitewallet.core.wallet.KeyId
import whitewallet.core.wallet.KeyProbe
import whitewallet.core.wallet.KeySet
import whitewallet.core.wallet.KeyStatus
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.MintDetails
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.MintMoveResult
import whitewallet.core.wallet.MoneyMoved
import whitewallet.core.wallet.MovePreview
import whitewallet.core.wallet.NoHome
import whitewallet.core.wallet.NotCollected
import whitewallet.core.wallet.NoteProblem
import whitewallet.core.wallet.PolicyRun
import whitewallet.core.wallet.RotateResult
import whitewallet.core.wallet.SingleNoteStrategy
import whitewallet.core.wallet.TrustDefault
import whitewallet.lnurlcash.client.LogEntry

// The settings pages in their everyday state, and their success screens (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only; every
// text is the page's own.

private const val HOME = "mint.example"
private const val OTHER = "cash.example"

private val single = SingleNoteStrategy(R.string.settings_strategy_single, R.string.settings_strategy_single_hint)
private val manual = ManualStrategy(R.string.settings_strategy_manual, R.string.settings_strategy_manual_hint)

private val satoshi = Account("a1", "Satoshi", PREVIEW_MILLIS, walletId = "w1", homeHost = HOME)
private val hal = Account("a2", "Hal", PREVIEW_MILLIS, walletId = "w1", index = 1, homeHost = HOME)

internal val previewWallet =
    WalletGroup(
        WalletRecord("w1", name = null, createdAtMillis = PREVIEW_MILLIS),
        number = 1,
        accounts = listOf(AccountSummary(satoshi, 21_000_000, used = true), AccountSummary(hal, 5_000_000, used = true)),
    )

/** A second wallet, not in use: the list shows which one is. */
private val otherWallet =
    WalletGroup(
        WalletRecord("w2", name = null, createdAtMillis = PREVIEW_MILLIS),
        number = 2,
        accounts = listOf(AccountSummary(Account("a3", "Satoshi", PREVIEW_MILLIS, walletId = "w2", homeHost = HOME), 0)),
    )

/** An account with an address, a verified backup and two trusted mints. */
internal val settingsState =
    SettingsState(
        accountId = satoshi.id,
        accountName = satoshi.name,
        address = "satoshi@$HOME",
        backupVerified = true,
        addressStatus = AddressStatus.Ours,
        noteStrategy = single,
        homeHost = HOME,
    )

internal val walletsState =
    WalletsState(
        wallets = listOf(previewWallet, otherWallet),
        activeAccountId = satoshi.id,
        places = mapOf(satoshi.id to 0, hal.id to 1, "a3" to 2),
        canAddWallet = true,
        canAddAccount = true,
    )

/** The Mints page: Satoshi's home with money, another mint with some, one Satoshi doesn't use; the account mark (two accounts). */
internal val mintsState =
    MintsPageState(
        home =
            MintCard(HOME, isHome = true, 21_000_000, noteCount = 3, addresses = 1, trusted = true, keyChanged = false, answering = true),
        others =
            listOf(
                MintCard(OTHER, isHome = false, 4_000_000, noteCount = 1, addresses = 0, trusted = true, false, answering = true),
                MintCard("old.example", isHome = false, 0, noteCount = 0, addresses = 0, trusted = false, false, answering = true),
            ),
        canChangeHome = true,
        accounts =
            AccountChoice(
                listOf(AccountSummary(satoshi, 21_000_000, used = true), AccountSummary(hal, 5_000_000, used = true)),
                satoshi.id,
                emptyMap(),
                shown = true,
            ),
    )

internal val mintDetailsState =
    MintDetailsState(
        details =
            MintDetails(
                host = HOME,
                mint = Mint("w1", HOME, "https://$HOME/withdraw", "02a1b2c3", 1_000, 1_000_000_000, 1_000, 5_000),
                reachability = MintDetails.Reachability.Reachable,
                description = null,
                balanceMsat = 21_000_000,
                noteCount = 3,
                isHome = true,
            ),
        loading = false,
        canChangeHome = true,
        host = HOME,
        canReceive = true,
        mint = MintInfo(HOME, "02a1b2c3", trusted = true, TrustDefault.Home, isHome = true),
        accounts = mintsState.accounts,
    )

internal val addressesState =
    AddressesState(
        entries =
            listOf(
                AddressEntry(RegisteredAddress("satoshi", HOME, PREVIEW_MILLIS, share = 1), primary = true, status = AddressStatus.Ours),
                AddressEntry(RegisteredAddress("tips", HOME, PREVIEW_MILLIS, share = 2), primary = false, status = AddressStatus.Ours),
            ),
        limit = 3,
        homeHost = HOME,
    )

/** The words on show: a backup's main moment. */
internal val backupState =
    BackupState(
        step =
            BackupStep.Display(
                listOf("lamp", "orbit", "velvet", "canyon", "frost", "ladder", "mercy", "pilot", "solar", "tunnel", "violin", "whale")
            )
    )

internal val notesSettingsState =
    NotesSettingsState(
        noteStrategy = single,
        strategies = listOf(single, manual),
        autoRotate = true,
        collectOffered = true,
        collectAtHome = true,
        collectFeeCap = 1,
        collectFeeCaps = listOf(1, 2, 5),
        homeHost = HOME,
    )

internal val deleteWalletState =
    DeleteWalletState(
        address = RegisteredAddress("satoshi", HOME, PREVIEW_MILLIS, share = 1),
        host = HOME,
        balanceMsat = 21_000_000,
        backupVerified = true,
    )

internal val derivedKeysState =
    DerivedKeysState(hosts = listOf(HOME, OTHER), homeHost = HOME, host = HOME, indexes = mapOf(KeySet.Wallet to 3L, KeySet.Change to 1L))

/** Changing the home mint: the money there would move along, for a fee. */
internal val moneyMoveState =
    MoneyMoveState(
        targets = listOf(HOME, OTHER),
        target = OTHER,
        preview = MovePreview(OTHER, listOf(MovePreview.Line(HOME, 21_000_000, MovePreview.Quote.Ok(20_990_000, 10_000))), 1),
    )

internal val localRegtestState =
    LocalRegtestState(
        listOf(
            LocalMint(
                "mint.regtest.local",
                "regtest",
                LocalMintStatus.Reachable(1_000, 1_000_000_000, 0, 0, "02a1b2c3"),
                "satoshi@mint.regtest.local",
            )
        )
    )

internal val requestLog =
    listOf(
        LogEntry(2, PREVIEW_MILLIS + 1_000, "GET", "https://$HOME/withdraw?k1=…", 200, 140, "{\"status\":\"OK\"}", null),
        LogEntry(1, PREVIEW_MILLIS, "GET", "https://$HOME/.well-known/lnurlp/satoshi", 200, 95, "{\"tag\":\"payRequest\"}", null),
    )

@WalletPreviews
@Composable
private fun SettingsPreview() = SettingsScreen(settingsState, versionName = "1.0.0", onBack = {}, onNavigate = {})

@WalletPreviews @Composable private fun WalletsPreview() = WalletsScreen(walletsState, onBack = {}, onOpen = {}, onAdd = {})

@WalletPreviews
@Composable
private fun WalletPagePreview() =
    WalletScreen(previewWallet, walletsState, settingsState, onBack = {}, onNavigate = {}, onAddAccount = {}, onRename = {}, onUse = {})

@WalletPreviews @Composable private fun AccountSettingsPreview() = AccountSettingsScreen(settingsState, onBack = {}, open = {})

@WalletPreviews
@Composable
private fun AccountNotInUsePreview() = AccountNotInUseScreen(AccountSummary(hal, 0), place = 1, onBack = {}, onUse = {})

@WalletPreviews @Composable private fun MintsPreview() = MintsWith(mintsState)

@Composable
private fun MintsWith(state: MintsPageState) =
    MintsScreen(state, onBack = {}, onOpen = {}, onAdd = {}, onNoHome = {}, onChangeHome = {}, accountActions = AccountActions({}, {}, {}))

@WalletPreviews @Composable private fun MintDetailsPreview() = MintDetailsScreen(mintDetailsState, onBack = {}, onRefresh = {})

@WalletPreviews
@Composable
private fun AddressesPreview() =
    AddressesScreen(
        addressesState,
        onBack = {},
        onAdd = {},
        onChangePrimary = {},
        onCheckAll = {},
        actions = AddressActions(onCheck = {}, onMakePrimary = {}, onUnregister = {}),
        onConfirmUnregister = {},
        onDismissUnregister = {},
    )

@WalletPreviews
@Composable
private fun BackupPreview() =
    BackupScreen(
        backupState,
        onBack = {},
        onShow = {},
        onContinueWithoutLock = {},
        onCopy = {},
        onWroteItDown = {},
        onTap = {},
        onSkipVerify = {},
    )

@WalletPreviews
@Composable
private fun NotesSettingsPreview() = NotesSettingsScreen(notesSettingsState, onBack = {}, onNoteStrategy = {}, onAutoRotate = {})

@WalletPreviews
@Composable
private fun DeleteWalletPreview() =
    DeleteWalletScreen(
        deleteWalletState,
        onBack = {},
        onBackup = {},
        onAcknowledge = {},
        onContinue = {},
        onDismissConfirm = {},
        onConfirm = {},
    )

@WalletPreviews
@Composable
private fun DerivedKeysPreview() =
    DerivedKeysScreen(
        derivedKeysState,
        onBack = {},
        onHost = {},
        onSet = {},
        onToggle = {},
        onRecheck = {},
        index = IndexActions({}, {}, {}, {}, {}, {}, {}, {}),
        rotate = RotateActions({}, {}, {}, {}),
    )

@WalletPreviews @Composable private fun MoneyMovePreview() = MoneyMoveScreen(moneyMoveState, onClose = {}, actions = MoneyMoveActions())

@WalletPreviews @Composable private fun DisplaySettingsPreview() = DisplaySettingsScreen(AmountUnit.Bitcoin, onBack = {}, onUnit = {})

@WalletPreviews
@Composable
private fun SecuritySettingsPreview() = SecuritySettingsScreen(appLock = true, available = true, onBack = {}, onAppLock = {})

@WalletPreviews
@Composable
private fun AboutPreview() = AboutScreen(LocalAppName.current, versionName = "1.0.0", supportUrl = "https://support.example", onBack = {})

@WalletPreviews
@Composable
private fun DeveloperSettingsPreview() =
    DeveloperSettingsScreen(
        enabled = true,
        pages =
            listOf(
                DeveloperPage(R.string.logs_title, R.string.logs_description, "logs"),
                DeveloperPage(R.string.regtest_title, R.string.regtest_description, "regtest"),
            ),
        onBack = {},
        onToggle = {},
        onOpenPage = {},
    )

@WalletPreviews @Composable private fun LocalRegtestPreview() = LocalRegtestScreen(localRegtestState, onBack = {}, onRecheck = {})

@WalletPreviews @Composable private fun RequestLogPreview() = RequestLogScreen(requestLog, onBack = {}, onClear = {})

/** All the money moved, a new home with its money, and a new home that left the money where it was. */
internal val moneyMovedStates =
    listOf(
        MoneyMoveState(
            mode = MoneyMoveMode.Move,
            from = HOME,
            target = OTHER,
            outcome = MoneyMoveOutcome(false, listOf(MoneyMoved(HOME, 21_000_000, MintMoveResult.Moved(20_990_000, 10_000)))),
        ),
        MoneyMoveState(
            target = OTHER,
            outcome = MoneyMoveOutcome(true, listOf(MoneyMoved(HOME, 21_000_000, MintMoveResult.Moved(20_990_000, 10_000)))),
        ),
        MoneyMoveState(target = OTHER, outcome = MoneyMoveOutcome(true, emptyList())),
    )

@WalletPreviews
@Moment
@Composable
private fun MoneyMovedPreview() = MoneyMoveScreen(moneyMovedStates[0], onClose = {}, actions = MoneyMoveActions())

@WalletPreviews
@Moment
@Composable
private fun HomeChangedMovedPreview() = MoneyMoveScreen(moneyMovedStates[1], onClose = {}, actions = MoneyMoveActions())

@WalletPreviews
@Moment
@Composable
private fun HomeChangedNonePreview() = MoneyMoveScreen(moneyMovedStates[2], onClose = {}, actions = MoneyMoveActions())

@WalletPreviews
@Moment
@Composable
private fun BackupDonePreview() =
    BackupScreen(
        BackupState(step = BackupStep.Success),
        onBack = {},
        onShow = {},
        onContinueWithoutLock = {},
        onCopy = {},
        onWroteItDown = {},
        onTap = {},
        onSkipVerify = {},
    )

/** The mint doesn't answer on the way to a new home: the error stands under the picker. */
@WalletPreviews
@Composable
private fun MoneyMoveErrorPreview() =
    MoneyMoveScreen(moneyMoveState.copy(preview = null, error = MoneyMoveError.Network), onClose = {}, actions = MoneyMoveActions())

/** The phone is offline: the page says so before anything is tried. */
@WalletPreviews
@Composable
private fun MoneyMoveOfflinePreview() =
    MoneyMoveScreen(moneyMoveState.copy(preview = null, offline = true), onClose = {}, actions = MoneyMoveActions())

/** The money was paid but hasn't shown up on the other mint yet. */
@WalletPreviews
@Composable
private fun MoneyMovePendingPreview() =
    MoneyMoveScreen(
        MoneyMoveState(
            mode = MoneyMoveMode.Move,
            from = HOME,
            target = OTHER,
            outcome = MoneyMoveOutcome(false, listOf(MoneyMoved(HOME, 21_000_000, MintMoveResult.Pending))),
        ),
        onClose = {},
        actions = MoneyMoveActions(),
    )

/** The money stayed where it was, with the reason, and the page offers another try. */
@WalletPreviews
@Composable
private fun MoneyMoveFailedPreview() =
    MoneyMoveScreen(
        MoneyMoveState(
            mode = MoneyMoveMode.Move,
            from = HOME,
            target = OTHER,
            outcome = MoneyMoveOutcome(false, listOf(MoneyMoved(HOME, 21_000_000, MintMoveResult.Failed(NoteProblem.Unreachable)))),
        ),
        onClose = {},
        actions = MoneyMoveActions(),
    )

/** A word tapped that isn't the next one: the backup's check says which word number was meant. */
@WalletPreviews
@Composable
private fun BackupWrongWordPreview() =
    BackupScreen(
        BackupState(
            step =
                BackupStep.Verify(
                    words = (backupState.step as BackupStep.Display).words,
                    choices = listOf(3, 0, 7, 1),
                    progress = listOf(0),
                    wrongNumber = 2,
                )
        ),
        onBack = {},
        onShow = {},
        onContinueWithoutLock = {},
        onCopy = {},
        onWroteItDown = {},
        onTap = {},
        onSkipVerify = {},
    )

@Composable
private fun DerivedKeysPage(state: DerivedKeysState) =
    DerivedKeysScreen(
        state,
        onBack = {},
        onHost = {},
        onSet = {},
        onToggle = {},
        onRecheck = {},
        index = IndexActions({}, {}, {}, {}, {}, {}, {}, {}),
        rotate = RotateActions({}, {}, {}, {}),
    )

/** A key's renewal got no answer: the page says it failed, under the index. */
@WalletPreviews
@Composable
private fun DerivedKeysRenewFailedPreview() =
    DerivedKeysPage(derivedKeysState.copy(lastRotate = RotateOutcome.One(RotateResult.Failed("timeout", noAnswer = true))))

/** The mint refused a lookup: the key's row says so in red. */
@WalletPreviews
@Composable
private fun DerivedKeysLookupRefusedPreview() =
    DerivedKeysPage(
        derivedKeysState.copy(
            keys =
                listOf(
                    DerivedKey(
                        id = KeyId(satoshi.id, HOME, KeySet.Wallet, 2),
                        q = "5c3f1b7e9a2d4c6e8f0a1b3d5e7f9a2c4e6b8d0f1a3c5e7b9d2f4a6c8e0b1d3f",
                        cp1 = "02a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
                        probe = KeyProbe.Refused(null, PREVIEW_MILLIS),
                        failure = null,
                        noteState = null,
                        noteAmountMsat = null,
                        status = KeyStatus.HandedOut,
                    )
                )
        )
    )

/** The mint's signing key isn't the pinned one: the page says nothing is looked up there. */
@WalletPreviews @Composable private fun DerivedKeysKeyChangedPreview() = DerivedKeysPage(derivedKeysState.copy(keyChanged = true))

/** The mint doesn't answer: its page says so where it says it is reachable. */
@WalletPreviews
@Composable
private fun MintDetailsUnreachablePreview() =
    MintDetailsScreen(
        mintDetailsState.copy(details = checkNotNull(mintDetailsState.details).copy(reachability = MintDetails.Reachability.Unreachable)),
        onBack = {},
        onRefresh = {},
    )

/** The mint presents another signing key than the pinned one: the key card asks what to do. */
@WalletPreviews
@Composable
private fun MintDetailsKeyChangedPreview() =
    MintDetailsScreen(
        mintDetailsState.copy(
            details =
                checkNotNull(mintDetailsState.details)
                    .copy(
                        reachability = MintDetails.Reachability.KeyChanged,
                        presentedKey = "03d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5",
                    ),
            canReceive = false,
        ),
        onBack = {},
        onRefresh = {},
    )

/** Another mint whose signing key doesn't match: its card turns the error colour and says so in place of its notes. */
@WalletPreviews
@Composable
private fun MintsKeyChangedPreview() =
    MintsWith(mintsState.copy(others = mintsState.others.map { if (it.host == OTHER) it.copy(keyChanged = true, trusted = false) else it }))

/** The home's signing key doesn't match: the home card turns the error colour, and stays the home card with its button. */
@WalletPreviews
@Composable
private fun MintsHomeKeyChangedPreview() = MintsWith(mintsState.copy(home = mintsState.home?.copy(keyChanged = true, trusted = false)))

/** No home: the way to choose one in the home card's place, the mints below. */
@WalletPreviews
@Composable
private fun MintsNoHomePreview() =
    MintsWith(
        mintsState.copy(
            home = null,
            noHome = NoHome.PickHome,
            others = listOf(checkNotNull(mintsState.home).copy(isHome = false)) + mintsState.others,
        )
    )

/** The addresses' checks: one that the mint can't be asked about, and a refused unregister said on the page. */
@WalletPreviews
@Composable
private fun AddressesProblemPreview() =
    AddressesScreen(
        addressesState.copy(
            entries =
                listOf(
                    addressesState.entries[0],
                    addressesState.entries[1].copy(status = AddressStatus.CheckFailed("no answer")),
                ),
            message = AddressesMessage.Unknown("tips@$HOME"),
        ),
        onBack = {},
        onAdd = {},
        onChangePrimary = {},
        onCheckAll = {},
        actions = AddressActions(onCheck = {}, onMakePrimary = {}, onUnregister = {}),
        onConfirmUnregister = {},
        onDismissUnregister = {},
    )

/** What collecting did at the last run: a move still waiting for its note, and notes that stayed where they were. */
@WalletPreviews
@Composable
private fun NotesSettingsCollectProblemPreview() =
    NotesSettingsScreen(
        notesSettingsState.copy(
            lastPolicyRun =
                PolicyRun(
                    rotated = 0,
                    merged = 0,
                    failed = 0,
                    stoppedEarly = null,
                    atMillis = PREVIEW_MILLIS,
                    collected = listOf(Collected(OTHER, 4_000_000, 10_000, pending = true)),
                    notCollected = listOf(NotCollected("third.example", 2_000_000, NotCollected.Reason.Failed(NoteProblem.Unreachable))),
                )
        ),
        onBack = {},
        onNoteStrategy = {},
        onAutoRotate = {},
    )

/** Notes the wallet can't tell are settled: the page says how many, in red, above the button. */
@WalletPreviews
@Composable
private fun DeleteWalletUnresolvedPreview() =
    DeleteWalletScreen(
        deleteWalletState.copy(unresolved = 2),
        onBack = {},
        onBackup = {},
        onAcknowledge = {},
        onContinue = {},
        onDismissConfirm = {},
        onConfirm = {},
    )
