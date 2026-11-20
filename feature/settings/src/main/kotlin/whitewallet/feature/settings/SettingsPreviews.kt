package whitewallet.feature.settings

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.PREVIEW_MILLIS
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary
import whitewallet.core.model.AmountUnit
import whitewallet.core.model.Mint
import whitewallet.core.model.MintScan
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.WalletGroup
import whitewallet.core.model.WalletRecord
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.AddressEntry
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.KeySet
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.MintDetails
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.MovePreview
import whitewallet.core.wallet.SingleNoteStrategy
import whitewallet.core.wallet.TrustDefault
import whitewallet.lnurlcash.client.LogEntry

// The settings pages in their everyday state (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only; every text is the page's own.

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
        mintCount = 2,
        trustedCount = 2,
    )

internal val walletsState =
    WalletsState(
        wallets = listOf(previewWallet),
        activeAccountId = satoshi.id,
        places = mapOf(satoshi.id to 0, hal.id to 1),
        canAddWallet = true,
        canAddAccount = true,
    )

internal val mintsState =
    MintsState(
        mints =
            listOf(
                MintInfo(HOME, "02a1b2c3", trusted = true, TrustDefault.Home, isHome = true, scan = MintScan.Finished),
                MintInfo(OTHER, "03d4e5f6", trusted = true, TrustDefault.Brand, removable = true, scan = MintScan.Never),
            )
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
    )

internal val accountMints =
    listOf(
        AccountMint(HOME, isHome = true, balanceMsat = 21_000_000, noteCount = 3, addresses = 1, trusted = true),
        AccountMint(OTHER, isHome = false, balanceMsat = 4_000_000, noteCount = 1, addresses = 0, trusted = true),
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
    WalletScreen(previewWallet, walletsState, settingsState, onBack = {}, onNavigate = {}, onAddAccount = {}, onRename = {})

@WalletPreviews @Composable private fun AccountSettingsPreview() = AccountSettingsScreen(settingsState, onBack = {}, open = {})

@WalletPreviews
@Composable
private fun AccountNotInUsePreview() = AccountNotInUseScreen(AccountSummary(hal, 0), place = 1, onBack = {}, onUse = {})

@WalletPreviews
@Composable
private fun MintsPreview() = MintsScreen(mintsState, onBack = {}, onOpen = {}, onTrusted = { _, _ -> }, onAdd = {})

@WalletPreviews @Composable private fun MintDetailsPreview() = MintDetailsScreen(mintDetailsState, onBack = {}, onRefresh = {})

@WalletPreviews @Composable private fun AccountMintsPreview() = AccountMintsScreen(accountMints, onBack = {}, onOpen = {})

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
