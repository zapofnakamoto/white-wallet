package whitewallet.app

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.BrandScreen
import whitewallet.brand.ui.BrandScreenEntry
import whitewallet.brand.ui.DefaultBrandDesign
import whitewallet.brand.ui.LocalWalletEvents
import whitewallet.core.designsystem.LoadingCarry
import whitewallet.core.designsystem.LoadingTimes
import whitewallet.core.designsystem.LocalAmountUnit
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.LocalBuildLabel
import whitewallet.core.designsystem.LocalLoadingCarry
import whitewallet.core.designsystem.LocalLoadingTimes
import whitewallet.core.designsystem.LocalReleaseStage
import whitewallet.core.designsystem.WalletTheme
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.designsystem.screenLoaded
import whitewallet.core.model.SetupStep
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.BackupRequired
import whitewallet.core.wallet.IncomingLink
import whitewallet.core.wallet.NoHome
import whitewallet.core.wallet.NotesView
import whitewallet.core.wallet.Wallet
import whitewallet.core.wallet.WalletSettings
import whitewallet.feature.claim.ClaimRoute
import whitewallet.feature.home.HomeMenuItem
import whitewallet.feature.home.HomeRoute
import whitewallet.feature.home.MoveFundsRoute
import whitewallet.feature.home.NotesTabSlot
import whitewallet.feature.notes.ArchiveRoute
import whitewallet.feature.notes.NotesRoute
import whitewallet.feature.notes.NotesTab
import whitewallet.feature.notes.NotesViewModel
import whitewallet.feature.onboarding.AccountNameRoute
import whitewallet.feature.onboarding.AddMintFinish
import whitewallet.feature.onboarding.AddMintRoute
import whitewallet.feature.onboarding.OnboardingMode
import whitewallet.feature.onboarding.OnboardingRoute
import whitewallet.feature.onboarding.RestoreRoute
import whitewallet.feature.onboarding.ScanRoute
import whitewallet.feature.onboarding.WalletReadyRoute
import whitewallet.feature.onboarding.WalletReadyState
import whitewallet.feature.onboarding.WelcomeRoute
import whitewallet.feature.receive.ReceiveRoute
import whitewallet.feature.send.CreateNoteRoute
import whitewallet.feature.send.ScanRoute
import whitewallet.feature.send.SendRoute
import whitewallet.feature.send.SharedNoteRoute
import whitewallet.feature.settings.AboutRoute
import whitewallet.feature.settings.AccountSettingsRoute
import whitewallet.feature.settings.AddressesRoute
import whitewallet.feature.settings.BackupRoute
import whitewallet.feature.settings.BrandPage
import whitewallet.feature.settings.DeleteWalletRoute
import whitewallet.feature.settings.DerivedKeysRoute
import whitewallet.feature.settings.DeveloperSettingsRoute
import whitewallet.feature.settings.DisplaySettingsRoute
import whitewallet.feature.settings.LocalRegtestRoute
import whitewallet.feature.settings.MintDetailsRoute
import whitewallet.feature.settings.MintsRoute
import whitewallet.feature.settings.MoneyMoveMode
import whitewallet.feature.settings.MoneyMoveRoute
import whitewallet.feature.settings.NotesSettingsRoute
import whitewallet.feature.settings.RequestLogRoute
import whitewallet.feature.settings.SecuritySettingsRoute
import whitewallet.feature.settings.SettingsDestination
import whitewallet.feature.settings.SettingsRoute
import whitewallet.feature.settings.SettingsTarget
import whitewallet.feature.settings.WalletRoute
import whitewallet.feature.settings.WalletsRoute

@AndroidEntryPoint
// FragmentActivity because the recovery phrase screen uses BiometricPrompt.
class MainActivity : FragmentActivity() {
    /** Text from a deep link, a share or an NFC tag, waiting to be opened (see [IncomingLink]). */
    private val incoming = MutableStateFlow<String?>(null)

    // The brand's design, if its code module provides one, and its own pages (docs/WHITE_LABEL.md, "Design").
    @Inject lateinit var design: Optional<BrandDesign>
    @Inject lateinit var brandScreens: Set<@JvmSuppressWildcards BrandScreen>
    @Inject lateinit var events: WalletEventHub
    @Inject lateinit var defaults: AppDefaults

    /** When a page says that it is loading: wallet.properties. */
    private val loadingCarry = LoadingCarry()
    private val loadingTimes =
        WalletSettings().let { LoadingTimes(it.loadingIndicatorShownAfterMillis, it.loadingIndicatorShownAtLeastMillis) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming.value = linkText(intent)
        setContent {
            val display: DisplayViewModel = hiltViewModel()
            val shownUnit by display.amountUnit.collectAsStateWithLifecycle()
            val appName = stringResource(R.string.app_name)
            WalletTheme(design.orElse(DefaultBrandDesign)) {
                CompositionLocalProvider(LocalLoadingTimes provides loadingTimes, LocalLoadingCarry provides loadingCarry) {
                    // Nothing of the wallet until the unit of its amounts is known (a moment).
                    if (!screenLoaded(shownUnit != null)) return@CompositionLocalProvider
                    val unit = shownUnit ?: return@CompositionLocalProvider
                    CompositionLocalProvider(
                        LocalAmountUnit provides unit,
                        LocalAppName provides appName,
                        LocalReleaseStage provides defaults.releaseStage,
                        LocalBuildLabel provides BuildConfig.BUILD_LABEL.ifEmpty { null },
                        LocalWalletEvents provides events.events,
                    ) {
                        Surface(Modifier.fillMaxSize()) {
                            AppLockGate(appName) {
                                WalletApp(
                                    incoming,
                                    brand = BrandScreens(brandScreens),
                                    showNotesTab = defaults.notesView == NotesView.Tab,
                                    notesPage = defaults.notesView == NotesView.Settings,
                                    backupAtSetup = defaults.backupRequired == BackupRequired.Setup,
                                    onIncomingHandled = { incoming.value = null },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkText(intent)?.let { incoming.value = it }
    }

    private fun linkText(intent: Intent?): String? =
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW,
            NfcAdapter.ACTION_NDEF_DISCOVERED -> intent.dataString
            else -> null
        }
}

@HiltViewModel
class StartViewModel @Inject constructor(wallet: Wallet) : ViewModel() {
    /** null while loading, then where the app starts. */
    private val _start = MutableStateFlow<String?>(null)
    val start: StateFlow<String?> = _start

    init {
        viewModelScope.launch {
            // A wallet whose setup the app left half way opens on the step it was at.
            _start.value =
                when {
                    wallet.activeAccount.first() == null -> Routes.WELCOME
                    else ->
                        when (wallet.setupStep.first()) {
                            SetupStep.Mint -> Routes.SETUP_MINT
                            SetupStep.Address -> Routes.SETUP_ADDRESS
                            null -> Routes.HOME
                        }
                }
        }
    }
}

internal object Routes {
    /** Where the app starts without a wallet: create or restore one. */
    const val WELCOME = "setup/welcome"
    /** The same, from Settings, with a way back. */
    const val ADD_WALLET = "wallet/new"
    const val RESTORE = "setup/restore"
    /** A wallet's setup: its mint, then its address (see [SetupStep]). */
    const val SETUP_MINT = "setup/mint"
    const val SETUP_ADDRESS = "setup/address?host={host}"

    fun setupAddress(host: String) = "setup/address?host=${Uri.encode(host)}"

    /** Where a wallet's setup ends ([walletReady]): with the address it added, if it did. */
    const val WALLET_READY = "setup/ready?address={address}"

    fun walletReady(address: String?) = if (address == null) "setup/ready" else "setup/ready?address=${Uri.encode(address)}"

    const val ADD_MINT = "mint/add"
    /** Looking through the wallet's keys at its mints; [scanNotes] with start: it begins at once. */
    const val SCAN_NOTES = "mint/scan?start={start}"

    fun scanNotes(start: Boolean) = "mint/scan?start=$start"

    const val HOME = "home"
    const val RECEIVE = "receive"
    /** Receive on the mint given ([receiveOn]); without one, as the wallet chooses. */
    const val RECEIVE_ON = "receive?on={on}"

    fun receiveOn(host: String) = "receive?on=${Uri.encode(host)}"

    const val SEND = "send"
    /** Send with the input given ([send]), or from the mint given ([sendFrom]). */
    const val SEND_WITH = "send?input={input}&from={from}"

    fun send(input: String) = "send?input=${Uri.encode(input)}"

    fun sendFrom(host: String) = "send?from=${Uri.encode(host)}"

    const val CREATE_NOTE = "send/note?from={from}"

    fun createNote(from: String?) = if (from == null) "send/note" else "send/note?from=${Uri.encode(from)}"

    const val SCAN = "scan"
    const val NOTE = "note/{id}"

    fun note(id: String) = "note/$id"

    const val CLAIM = "claim"
    const val CLAIM_WITH = "claim?input={input}"

    fun claim(input: String) = "claim?input=${Uri.encode(input)}"

    const val SETTINGS = "settings"
    /** Every wallet on the phone; a wallet's page and an account's page. */
    const val WALLETS = "settings/wallets"
    const val WALLET = "settings/wallet/{id}"

    fun wallet(id: String) = "settings/wallet/${Uri.encode(id)}"

    const val ACCOUNT_SETTINGS = "settings/account/{id}"

    fun account(id: String) = "settings/account/${Uri.encode(id)}"

    const val BACKUP = "backup"
    const val BACKUP_AT_SETUP = "backup/setup?next={next}"

    fun backupAtSetup(next: String?) = if (next == null) "backup/setup" else "backup/setup?next=${Uri.encode(next)}"

    const val CHANGE_ADDRESS = "address"
    const val ADDRESSES = "addresses"
    /** Note settings (strategy, collecting, auto-rotate). */
    const val NOTES = "settings/notes"
    const val ARCHIVE = "notes/archive"

    /** The notes view on its own page, for brands without a Notes tab. */
    const val NOTE_LIST = "notes"
    /** The same, opened on its page of what needs the user ([NOTE_LIST_ATTENTION]) or not. */
    const val NOTE_LIST_WITH = "notes?attention={attention}"
    const val NOTE_LIST_ATTENTION = "notes?attention=true"
    const val MINTS = "settings/mints"
    const val DISPLAY = "settings/display"
    const val MINT = "settings/mint?host={host}"
    const val SECURITY = "settings/security"
    const val ABOUT = "settings/about"

    fun mint(host: String) = "settings/mint?host=${Uri.encode(host)}"

    const val ADD_ADDRESS = "addresses/add"
    const val ADD_ADDRESS_ELSEWHERE = "addresses/add-elsewhere"
    const val CHANGE_MINT = "settings/mint/change?host={host}"

    fun changeMint(host: String?) = if (host == null) "settings/mint/change" else "settings/mint/change?host=${Uri.encode(host)}"

    const val MOVE_MONEY = "settings/mint/move?from={from}"

    fun moveMoney(from: String) = "settings/mint/move?from=${Uri.encode(from)}"

    const val MOVE_HOME = "settings/mint/move-home"
    const val DELETE = "delete"
    const val NEW_ACCOUNT = "account/new"
    const val MOVE_FUNDS = "move"
    const val DEVELOPER = "settings/developer"
    const val BRAND = "brand/{id}"

    fun brand(id: String) = "brand/$id"
}

@Composable
private fun WalletApp(
    incoming: StateFlow<String?>,
    brand: BrandScreens,
    showNotesTab: Boolean,
    /** The notes view is a page of its own (in the settings), not a tab. */
    notesPage: Boolean,
    backupAtSetup: Boolean,
    onIncomingHandled: () -> Unit,
    start: StartViewModel = hiltViewModel(),
) {
    val startRoute by start.start.collectAsStateWithLifecycle()
    // Where the app opens isn't known yet (a moment).
    if (!pageLoaded(startRoute != null)) return
    val first = startRoute ?: return
    val appName = stringResource(R.string.app_name)
    val nav = rememberNavController()
    // Each setup step replaces everything before it: back can't return to a step that is done.
    val replaceAll = { route: String -> nav.navigate(route) { popUpTo(0) { inclusive = true } } }
    // A setup ends on the wallet's ready screen, with the address it added.
    val finishSetup = { address: String? -> replaceAll(Routes.walletReady(address)) }
    // Then the brand's page after a setup, if it has one for this wallet, before the wallet; a new wallet's backup first with
    // backup.required=setup.
    val openWallet = { ready: WalletReadyState ->
        val next = brand.afterSetup(firstWallet = !ready.several)?.let { Routes.brand(it.id) } ?: Routes.HOME
        replaceAll(if (backupAtSetup && !ready.backedUp) Routes.backupAtSetup(next) else next)
    }
    val leftSetup = { remains: Boolean -> replaceAll(if (remains) Routes.HOME else Routes.WELCOME) }
    // Claim and Send open over Home, whatever led there.
    val openClaim = { link: String -> nav.navigate(Routes.claim(link)) { popUpTo(Routes.HOME) } }
    val openLink = { link: IncomingLink ->
        when (link) {
            is IncomingLink.Claim -> openClaim(link.text)
            is IncomingLink.Pay -> nav.navigate(Routes.send(link.text)) { popUpTo(Routes.HOME) }
        }
    }
    // Links from outside open once the wallet is set up (Home is on the back stack): a note in Claim, anything else
    // in Send. Nothing is claimed or paid until the user confirms there.
    val pending by incoming.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()
    // A link the app can't use: said on Home.
    var unreadableLink by remember { mutableStateOf(false) }
    LaunchedEffect(pending, current) {
        val text = pending ?: return@LaunchedEffect
        if (current == null || runCatching { nav.getBackStackEntry(Routes.HOME) }.isFailure) return@LaunchedEffect
        val link = IncomingLink.classify(text)
        if (link != null) openLink(link)
        else {
            unreadableLink = true
            nav.popBackStack(Routes.HOME, inclusive = false)
        }
        onIncomingHandled()
    }
    val logo = @Composable { Image(painterResource(R.drawable.ic_brand_logo), contentDescription = null) }
    NavHost(nav, startDestination = first) {
        screen(nav, Routes.WELCOME) {
            WelcomeRoute(appName, logo, onCreated = { replaceAll(Routes.SETUP_MINT) }, onRestore = { open(Routes.RESTORE) })
        }
        screen(nav, Routes.ADD_WALLET) {
            WelcomeRoute(
                appName,
                logo,
                onCreated = { replaceAll(Routes.SETUP_MINT) },
                onRestore = { open(Routes.RESTORE) },
                onBack = { back() },
            )
        }
        screen(nav, Routes.RESTORE) {
            RestoreRoute(onBack = { back() }, onRestored = { replaceAll(Routes.SETUP_MINT) })
        }
        screen(nav, Routes.SETUP_MINT) {
            AddMintRoute(
                setup = true,
                onFinish = { finish ->
                    when (finish) {
                        is AddMintFinish.Address -> replaceAll(Routes.setupAddress(finish.host))
                        is AddMintFinish.Done -> finishSetup(null)
                        is AddMintFinish.Abandoned -> leftSetup(finish.remains)
                    }
                },
            )
        }
        screen(
            nav,
            Routes.SETUP_ADDRESS,
            arguments =
                listOf(
                    navArgument("host") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) { entry ->
            OnboardingRoute(
                onDone = finishSetup,
                mode = OnboardingMode.Setup,
                presetHost = entry.arguments?.getString("host"),
                onAbandoned = leftSetup,
            )
        }
        screen(
            nav,
            Routes.WALLET_READY,
            arguments =
                listOf(
                    navArgument("address") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) { entry ->
            WalletReadyRoute(entry.arguments?.getString("address"), onOpen = openWallet)
        }
        screen(nav, Routes.ADD_MINT) {
            AddMintRoute(setup = false, onFinish = { back() })
        }
        screen(nav, Routes.SCAN_NOTES, arguments = listOf(navArgument("start") { defaultValue = false })) { entry ->
            ScanRoute(start = entry.arguments?.getBoolean("start") == true, onBack = { back() })
        }
        screen(nav, Routes.HOME) {
            HomeRoute(
                appName,
                onSend = { open(Routes.SEND) },
                onSendFrom = { host -> open(Routes.sendFrom(host)) },
                onReceiveOn = { host -> open(Routes.receiveOn(host)) },
                onScan = { open(Routes.SCAN) },
                onOpenNote = { id -> open(Routes.note(id)) },
                onOpenMint = { host -> open(Routes.mint(host)) },
                onClaim = openClaim,
                unreadableLink = unreadableLink,
                onUnreadableLinkShown = { unreadableLink = false },
                onReceive = { open(Routes.RECEIVE) },
                onSettings = { open(Routes.SETTINGS) },
                onBackup = { open(Routes.BACKUP) },
                onManageWallets = { open(Routes.WALLETS) },
                onMoveFunds = { open(Routes.MOVE_FUNDS) },
                onAddMint = { open(Routes.ADD_MINT) },
                onScanMints = { open(Routes.scanNotes(start = true)) },
                onOpenMints = { open(Routes.MINTS) },
                onOpenNotesAttention = if (notesPage) ({ open(Routes.NOTE_LIST_ATTENTION) }) else null,
                menu =
                    brand.on(BrandScreenEntry.HomeMenu).map { screen ->
                        HomeMenuItem(screen.title) { open(Routes.brand(screen.id)) }
                    },
                notesTab =
                    if (showNotesTab)
                        notesTab(
                            onSettings = { open(Routes.NOTES) },
                            onOpenShared = { id -> open(Routes.note(id)) },
                            onOpenArchive = { open(Routes.ARCHIVE) },
                            onClaim = openClaim,
                            onReceive = { open(Routes.RECEIVE) },
                            onOpenMint = { host -> open(Routes.mint(host)) },
                        )
                    else null,
            )
        }
        screen(
            nav,
            Routes.NOTE_LIST_WITH,
            arguments =
                listOf(
                    navArgument("attention") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                ),
        ) {
            NotesRoute(
                onBack = { back() },
                onSettings = { open(Routes.NOTES) },
                onOpenShared = { id -> open(Routes.note(id)) },
                onOpenArchive = { open(Routes.ARCHIVE) },
                onClaim = openClaim,
                onReceive = { open(Routes.RECEIVE) },
                onOpenMint = { host -> open(Routes.mint(host)) },
            )
        }
        screen(nav, Routes.ARCHIVE) { ArchiveRoute(onBack = { back() }) }
        // The new account is in use: its page opens over the wallet's.
        screen(nav, Routes.NEW_ACCOUNT) {
            AccountNameRoute(
                onBack = { back() },
                onCreated = { id ->
                    open(Routes.account(id)) { popUpTo(Routes.NEW_ACCOUNT) { inclusive = true } }
                },
            )
        }
        screen(nav, Routes.MOVE_FUNDS) {
            MoveFundsRoute(onBack = { back() }, onMint = { host -> open(Routes.mint(host)) })
        }
        screen(
            nav,
            Routes.SEND_WITH,
            arguments =
                listOf(
                    navArgument("input") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("from") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) {
            SendRoute(
                onDone = { back() },
                onCreateNote = { from -> open(Routes.createNote(from)) },
                onClaim = openClaim,
                onOpenMint = { host -> open(Routes.mint(host)) },
            )
        }
        screen(nav, Routes.SCAN) {
            ScanRoute(onBack = { back() }, onScanned = openLink)
        }
        screen(
            nav,
            Routes.CREATE_NOTE,
            arguments =
                listOf(
                    navArgument("from") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) {
            CreateNoteRoute(
                onBack = { back() },
                onCreated = { id -> open(Routes.note(id)) { popUpTo(Routes.HOME) } },
                onOpenMint = { host -> open(Routes.mint(host)) },
            )
        }
        screen(nav, Routes.NOTE, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            SharedNoteRoute(onBack = { back() })
        }
        screen(
            nav,
            Routes.RECEIVE_ON,
            arguments =
                listOf(
                    navArgument("on") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) {
            ReceiveRoute(
                onBack = { back() },
                onClaim = { open(Routes.CLAIM) { popUpTo(Routes.HOME) } },
                onNoHome = { noHome ->
                    open(if (noHome == NoHome.NoMint) Routes.ADD_MINT else Routes.changeMint(null)) { popUpTo(Routes.HOME) }
                },
                onOpenMint = { host -> open(Routes.mint(host)) },
                onOpenAddresses = { open(Routes.ADDRESSES) },
            )
        }
        screen(
            nav,
            Routes.CLAIM_WITH,
            arguments =
                listOf(
                    navArgument("input") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) {
            ClaimRoute(
                onBack = { backTo(Routes.HOME) },
                onPay = { open(Routes.send(it)) { popUpTo(Routes.HOME) } },
            )
        }
        screen(nav, Routes.SETTINGS) {
            SettingsRoute(
                versionName = BuildConfig.VERSION_NAME,
                brandPages = brand.on(BrandScreenEntry.Settings).map { BrandPage(it.title, Routes.brand(it.id), it.icon) },
                onBack = { back() },
                onNavigate = { open(settingsRoute(it)) },
            )
        }
        screen(nav, Routes.WALLETS) {
            WalletsRoute(
                onBack = { back() },
                onOpen = { id -> open(Routes.wallet(id)) },
                onAdd = { open(Routes.ADD_WALLET) },
                onNavigate = { open(settingsRoute(it)) },
                onAddAccount = { open(Routes.NEW_ACCOUNT) },
            )
        }
        screen(nav, Routes.WALLET, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            WalletRoute(
                walletId = entry.arguments?.getString("id").orEmpty(),
                onBack = { back() },
                onNavigate = { open(settingsRoute(it)) },
                onAddAccount = { open(Routes.NEW_ACCOUNT) },
            )
        }
        screen(nav, Routes.ACCOUNT_SETTINGS, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            AccountSettingsRoute(
                accountId = entry.arguments?.getString("id").orEmpty(),
                onBack = { back() },
                onOpen = { open(settingsRoute(it)) },
            )
        }
        screen(nav, Routes.DEVELOPER) {
            DeveloperSettingsRoute(DeveloperPages.all, onBack = { back() }, onOpenPage = { open(it) })
        }
        // Developer-only pages: each registered with developerComposable, so it can't be opened while the mode is off.
        developerComposable(nav, DeveloperPages.REQUEST_LOG) {
            RequestLogRoute(onBack = { back() })
        }
        developerComposable(nav, DeveloperPages.DERIVED_KEYS) {
            DerivedKeysRoute(onBack = { back() })
        }
        developerComposable(nav, DeveloperPages.LOCAL_REGTEST) {
            LocalRegtestRoute(onBack = { back() })
        }
        screen(nav, Routes.DELETE) {
            DeleteWalletRoute(
                onBack = { back() },
                onBackup = { open(Routes.BACKUP) },
                // Nothing of the deleted account stays reachable: continue with the remaining one, or start over.
                onDeleted = { remains -> replaceAll(if (remains) Routes.HOME else Routes.WELCOME) },
            )
        }
        screen(
            nav,
            Routes.MINT,
            arguments =
                listOf(
                    navArgument("host") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) {
            MintDetailsRoute(
                onBack = { back() },
                onChangeHome = { host -> open(Routes.changeMint(host)) },
                onSend = { host -> open(Routes.sendFrom(host)) },
                onReceive = { host -> open(Routes.receiveOn(host)) },
                onMoveMoney = { host -> open(Routes.moveMoney(host)) },
                onMoveAllHere = { open(Routes.MOVE_HOME) },
                onManageAccounts = { open(Routes.WALLETS) },
                onMoveFunds = { open(Routes.MOVE_FUNDS) },
            )
        }
        screen(
            nav,
            Routes.CHANGE_MINT,
            arguments =
                listOf(
                    navArgument("host") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) { entry ->
            MoneyMoveRoute(
                MoneyMoveMode.ChangeHome,
                entry.arguments?.getString("host"),
                onClose = { back() },
                onAddMint = { open(Routes.ADD_MINT) },
            )
        }
        screen(
            nav,
            Routes.MOVE_MONEY,
            arguments =
                listOf(
                    navArgument("from") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) { entry ->
            MoneyMoveRoute(
                MoneyMoveMode.Move,
                entry.arguments?.getString("from"),
                onClose = { back() },
                onAddMint = { open(Routes.ADD_MINT) },
            )
        }
        screen(nav, Routes.MOVE_HOME) {
            MoneyMoveRoute(MoneyMoveMode.MoveHome, null, onClose = { back() })
        }
        screen(nav, Routes.MINTS) {
            MintsRoute(
                onBack = { back() },
                onOpen = { host -> open(Routes.mint(host)) },
                onAdd = { open(Routes.ADD_MINT) },
                onNoHome = { noHome -> open(if (noHome == NoHome.NoMint) Routes.ADD_MINT else Routes.changeMint(null)) },
                onChangeHome = { open(Routes.changeMint(null)) },
                onManageAccounts = { open(Routes.WALLETS) },
                onMoveFunds = { open(Routes.MOVE_FUNDS) },
            )
        }
        screen(nav, Routes.SECURITY) {
            SecuritySettingsRoute(onBack = { back() })
        }
        screen(nav, Routes.ABOUT) {
            AboutRoute(appName, BuildConfig.VERSION_NAME, BuildConfig.SUPPORT_URL, onBack = { back() })
        }
        screen(nav, Routes.DISPLAY) {
            DisplaySettingsRoute(onBack = { back() })
        }
        screen(nav, Routes.NOTES) {
            NotesSettingsRoute(onBack = { back() })
        }
        screen(nav, Routes.ADDRESSES) {
            AddressesRoute(
                onBack = { back() },
                onAdd = { open(Routes.ADD_ADDRESS) },
                onChangePrimary = { open(Routes.CHANGE_ADDRESS) },
                onAddElsewhere = { open(Routes.ADD_ADDRESS_ELSEWHERE) },
                onNoHome = { noHome -> open(if (noHome == NoHome.NoMint) Routes.ADD_MINT else Routes.changeMint(null)) },
            )
        }
        screen(nav, Routes.ADD_ADDRESS) {
            OnboardingRoute(
                onDone = { back() },
                mode = OnboardingMode.AddAddress,
                onBack = { back() },
            )
        }
        screen(nav, Routes.ADD_ADDRESS_ELSEWHERE) {
            OnboardingRoute(
                onDone = { back() },
                mode = OnboardingMode.AddAddressElsewhere,
                onBack = { back() },
            )
        }
        screen(nav, Routes.CHANGE_ADDRESS) {
            OnboardingRoute(
                onDone = { back() },
                mode = OnboardingMode.ChangeAddress,
                onBack = { back() },
            )
        }
        screen(nav, Routes.BACKUP) {
            BackupRoute(onDone = { back() })
        }
        // A new account's backup during setup: no way back, then on to [next] (the wallet, or the brand's welcome page), or back where
        // "Add account" was opened.
        screen(
            nav,
            Routes.BACKUP_AT_SETUP,
            arguments =
                listOf(
                    navArgument("next") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) { entry ->
            val next = entry.arguments?.getString("next")
            BackupRoute(
                required = true,
                onDone = {
                    if (next == null) back() else open(next) { popUpTo(Routes.BACKUP_AT_SETUP) { inclusive = true } }
                },
            )
        }
        screen(nav, Routes.BRAND, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val screen = brand.byId.getValue(checkNotNull(entry.arguments?.getString("id")))
            screen.Content(
                onBack = {
                    if (screen.entry.afterSetup) {
                        open(Routes.HOME) { popUpTo(0) { inclusive = true } }
                    } else {
                        back()
                    }
                }
            )
        }
    }
}

/** The brand's own pages, checked once: ids unique and usable in a route, at most one page after a setup. */
internal class BrandScreens(screens: Set<BrandScreen>) {
    val byId: Map<String, BrandScreen> = screens.associateBy { it.id }

    private val page: BrandScreen? = screens.firstOrNull { it.entry.afterSetup }

    init {
        screens.forEach { check(it.id.matches(Regex("[a-z0-9-]+"))) { "Brand screen id '${it.id}': use lowercase letters, digits and -" } }
        check(byId.size == screens.size) { "Two brand screens share an id: ${screens.map { it.id }}" }
        check(screens.count { it.entry.afterSetup } <= 1) { "More than one brand screen is shown after a setup" }
    }

    /** The page to show after a wallet's setup: [firstWallet] when it is the only wallet on the phone. */
    fun afterSetup(firstWallet: Boolean): BrandScreen? = page?.takeIf { firstWallet || it.entry == BrandScreenEntry.AfterEverySetup }

    fun on(entry: BrandScreenEntry): List<BrandScreen> = byId.values.filter { it.entry == entry }.sortedBy { it.id }
}

/** Shown between a wallet's setup and the wallet. */
private val BrandScreenEntry.afterSetup: Boolean
    get() = this == BrandScreenEntry.AfterOnboarding || this == BrandScreenEntry.AfterEverySetup

/**
 * The Notes tab for Home: the notes view (its note settings in a row at the bottom) and its refresh (asking the mints about every note).
 */
@Composable
private fun notesTab(
    onSettings: () -> Unit,
    onOpenShared: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onClaim: (String) -> Unit,
    onReceive: () -> Unit,
    onOpenMint: (String) -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
): NotesTabSlot {
    val state by viewModel.state.collectAsStateWithLifecycle()
    return NotesTabSlot(
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        content = { NotesTab(viewModel, onOpenShared, onOpenArchive, onClaim, onReceive, onSettings, onOpenMint) },
        onOpenAttention = viewModel::openAttention,
    )
}

private fun settingsRoute(destination: SettingsDestination): String =
    when (destination) {
        is SettingsDestination.Route -> destination.route
        is SettingsDestination.Page -> settingsRoute(destination.target)
        is SettingsDestination.Wallet -> Routes.wallet(destination.walletId)
        is SettingsDestination.Account -> Routes.account(destination.accountId)
    }

private fun settingsRoute(target: SettingsTarget): String =
    when (target) {
        SettingsTarget.Wallets -> Routes.WALLETS
        SettingsTarget.Backup -> Routes.BACKUP
        SettingsTarget.Addresses -> Routes.ADDRESSES
        SettingsTarget.Notes -> Routes.NOTES
        SettingsTarget.NoteList -> Routes.NOTE_LIST
        SettingsTarget.Mints -> Routes.MINTS
        SettingsTarget.Scan -> Routes.SCAN_NOTES
        SettingsTarget.Display -> Routes.DISPLAY
        SettingsTarget.Security -> Routes.SECURITY
        SettingsTarget.About -> Routes.ABOUT
        SettingsTarget.Delete -> Routes.DELETE
        SettingsTarget.Developer -> Routes.DEVELOPER
        SettingsTarget.Keys -> DeveloperPages.DERIVED_KEYS
    }
