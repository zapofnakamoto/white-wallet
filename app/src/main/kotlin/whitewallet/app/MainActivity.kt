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
import androidx.navigation.compose.composable
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
import whitewallet.core.wallet.NotesView
import whitewallet.core.wallet.Wallet
import whitewallet.core.wallet.WalletSettings
import whitewallet.feature.claim.ClaimRoute
import whitewallet.feature.home.HomeMenuItem
import whitewallet.feature.home.HomeRoute
import whitewallet.feature.home.MoveFundsRoute
import whitewallet.feature.home.NotesTabSlot
import whitewallet.feature.notes.ArchiveRoute
import whitewallet.feature.notes.NoteSettingsButton
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
import whitewallet.feature.onboarding.WelcomeRoute
import whitewallet.feature.receive.NoHome
import whitewallet.feature.receive.ReceiveRoute
import whitewallet.feature.send.CreateNoteRoute
import whitewallet.feature.send.ScanRoute
import whitewallet.feature.send.SendRoute
import whitewallet.feature.send.SharedNoteRoute
import whitewallet.feature.settings.AboutRoute
import whitewallet.feature.settings.AccountMintsRoute
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

    const val ADD_MINT = "mint/add"
    /** Looking through the wallet's keys at its mints; [scanNotes] with start: it begins at once. */
    const val SCAN_NOTES = "mint/scan?start={start}"

    fun scanNotes(start: Boolean) = "mint/scan?start=$start"

    const val HOME = "home"
    const val RECEIVE = "receive"
    const val SEND = "send"
    const val SEND_WITH = "send?input={input}"

    fun send(input: String) = "send?input=${Uri.encode(input)}"

    const val CREATE_NOTE = "send/note"
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
    const val MINTS = "settings/mints"
    const val DISPLAY = "settings/display"
    const val MINT = "settings/mint?host={host}&device={device}"
    const val ACCOUNT_MINTS = "settings/account/mints"
    const val SECURITY = "settings/security"
    const val ABOUT = "settings/about"

    fun mint(host: String, device: Boolean = false) = "settings/mint?host=${Uri.encode(host)}&device=$device"

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
    // Then the brand's welcome page, if it has one, before the wallet; a new wallet's backup first with backup.required=setup.
    val finishSetup = { backedUp: Boolean ->
        val next = brand.afterOnboarding?.let { Routes.brand(it.id) } ?: Routes.HOME
        replaceAll(if (backupAtSetup && !backedUp) Routes.backupAtSetup(next) else next)
    }
    val leftSetup = { remains: Boolean -> replaceAll(if (remains) Routes.HOME else Routes.WELCOME) }
    // Links from outside open once the wallet is set up (Home is on the back stack): a note in Claim, anything else
    // in Send. Nothing is claimed or paid until the user confirms there.
    val pending by incoming.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()
    // A link the app can't use: said on Home.
    var unreadableLink by remember { mutableStateOf(false) }
    LaunchedEffect(pending, current) {
        val text = pending ?: return@LaunchedEffect
        if (current == null || runCatching { nav.getBackStackEntry(Routes.HOME) }.isFailure) return@LaunchedEffect
        when (val link = IncomingLink.classify(text)) {
            is IncomingLink.Claim -> nav.navigate(Routes.claim(link.text)) { popUpTo(Routes.HOME) }
            is IncomingLink.Pay -> nav.navigate(Routes.send(link.text)) { popUpTo(Routes.HOME) }
            null -> {
                unreadableLink = true
                nav.popBackStack(Routes.HOME, inclusive = false)
            }
        }
        onIncomingHandled()
    }
    val logo = @Composable { Image(painterResource(R.drawable.ic_brand_logo), contentDescription = null) }
    NavHost(nav, startDestination = first) {
        composable(Routes.WELCOME) {
            WelcomeRoute(appName, logo, onCreated = { replaceAll(Routes.SETUP_MINT) }, onRestore = { nav.navigate(Routes.RESTORE) })
        }
        composable(Routes.ADD_WALLET) {
            WelcomeRoute(
                appName,
                logo,
                onCreated = { replaceAll(Routes.SETUP_MINT) },
                onRestore = { nav.navigate(Routes.RESTORE) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.RESTORE) {
            RestoreRoute(onBack = { nav.popBackStack() }, onRestored = { replaceAll(Routes.SETUP_MINT) })
        }
        composable(Routes.SETUP_MINT) {
            AddMintRoute(
                setup = true,
                onFinish = { finish ->
                    when (finish) {
                        is AddMintFinish.Address -> replaceAll(Routes.setupAddress(finish.host))
                        is AddMintFinish.Done -> finishSetup(finish.backedUp ?: true)
                        is AddMintFinish.Abandoned -> leftSetup(finish.remains)
                    }
                },
            )
        }
        composable(
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
        composable(Routes.ADD_MINT) {
            AddMintRoute(setup = false, onFinish = { nav.popBackStack() })
        }
        composable(Routes.SCAN_NOTES, arguments = listOf(navArgument("start") { defaultValue = false })) { entry ->
            ScanRoute(start = entry.arguments?.getBoolean("start") == true, onBack = { nav.popBackStack() })
        }
        composable(Routes.HOME) {
            HomeRoute(
                appName,
                onSend = { nav.navigate(Routes.SEND) },
                onScan = { nav.navigate(Routes.SCAN) },
                onOpenNote = { id -> nav.navigate(Routes.note(id)) },
                onOpenMint = { host -> nav.navigate(Routes.mint(host)) },
                onClaim = { link -> nav.navigate(Routes.claim(link)) { popUpTo(Routes.HOME) } },
                unreadableLink = unreadableLink,
                onUnreadableLinkShown = { unreadableLink = false },
                onReceive = { nav.navigate(Routes.RECEIVE) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onBackup = { nav.navigate(Routes.BACKUP) },
                onOpenAddresses = { nav.navigate(Routes.ADDRESSES) },
                onManageWallets = { nav.navigate(Routes.WALLETS) },
                onMoveFunds = { nav.navigate(Routes.MOVE_FUNDS) },
                onAddMint = { nav.navigate(Routes.ADD_MINT) },
                onScanMints = { nav.navigate(Routes.scanNotes(start = true)) },
                onOpenMints = { nav.navigate(Routes.ACCOUNT_MINTS) },
                menu =
                    brand.on(BrandScreenEntry.HomeMenu).map { screen ->
                        HomeMenuItem(screen.title) { nav.navigate(Routes.brand(screen.id)) }
                    },
                notesTab =
                    if (showNotesTab)
                        notesTab(
                            onSettings = { nav.navigate(Routes.NOTES) },
                            onOpenShared = { id -> nav.navigate(Routes.note(id)) },
                            onOpenArchive = { nav.navigate(Routes.ARCHIVE) },
                            onClaim = { link -> nav.navigate(Routes.claim(link)) { popUpTo(Routes.HOME) } },
                        )
                    else null,
            )
        }
        composable(Routes.NOTE_LIST) {
            NotesRoute(
                onBack = { nav.popBackStack() },
                onSettings = { nav.navigate(Routes.NOTES) },
                onOpenShared = { id -> nav.navigate(Routes.note(id)) },
                onOpenArchive = { nav.navigate(Routes.ARCHIVE) },
                onClaim = { link -> nav.navigate(Routes.claim(link)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.ARCHIVE) { ArchiveRoute(onBack = { nav.popBackStack() }) }
        // The new account is in use: its page opens over the wallet's.
        composable(Routes.NEW_ACCOUNT) {
            AccountNameRoute(
                onBack = { nav.popBackStack() },
                onCreated = { id ->
                    nav.popBackStack()
                    nav.navigate(Routes.account(id))
                },
            )
        }
        composable(Routes.MOVE_FUNDS) {
            MoveFundsRoute(onBack = { nav.popBackStack() }, onMint = { host -> nav.navigate(Routes.mint(host)) })
        }
        composable(
            Routes.SEND_WITH,
            arguments =
                listOf(
                    navArgument("input") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
        ) {
            SendRoute(
                onDone = { nav.popBackStack() },
                onCreateNote = { nav.navigate(Routes.CREATE_NOTE) },
                onClaim = { nav.navigate(Routes.claim(it)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.SCAN) {
            ScanRoute(onBack = { nav.popBackStack() }) { link ->
                nav.navigate(if (link is IncomingLink.Claim) Routes.claim(link.text) else Routes.send(link.text)) { popUpTo(Routes.HOME) }
            }
        }
        composable(Routes.CREATE_NOTE) {
            CreateNoteRoute(onBack = { nav.popBackStack() }, onCreated = { id -> nav.navigate(Routes.note(id)) { popUpTo(Routes.HOME) } })
        }
        composable(Routes.NOTE, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            SharedNoteRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.RECEIVE) {
            ReceiveRoute(
                onBack = { nav.popBackStack() },
                onClaim = { nav.navigate(Routes.CLAIM) { popUpTo(Routes.HOME) } },
                onNoHome = { noHome ->
                    nav.navigate(if (noHome == NoHome.NoMint) Routes.ADD_MINT else Routes.changeMint(null)) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(
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
                onBack = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onPay = { nav.navigate(Routes.send(it)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                versionName = BuildConfig.VERSION_NAME,
                brandPages = brand.on(BrandScreenEntry.Settings).map { BrandPage(it.title, Routes.brand(it.id), it.icon) },
                onBack = { nav.popBackStack() },
                onNavigate = { nav.navigate(settingsRoute(it)) },
            )
        }
        composable(Routes.WALLETS) {
            WalletsRoute(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate(Routes.wallet(id)) },
                onAdd = { nav.navigate(Routes.ADD_WALLET) },
                onNavigate = { nav.navigate(settingsRoute(it)) },
                onAddAccount = { nav.navigate(Routes.NEW_ACCOUNT) },
            )
        }
        composable(Routes.WALLET, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            WalletRoute(
                walletId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onNavigate = { nav.navigate(settingsRoute(it)) },
                onAddAccount = { nav.navigate(Routes.NEW_ACCOUNT) },
            )
        }
        composable(Routes.ACCOUNT_SETTINGS, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            AccountSettingsRoute(
                accountId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(settingsRoute(it)) },
            )
        }
        composable(Routes.DEVELOPER) {
            DeveloperSettingsRoute(DeveloperPages.all, onBack = { nav.popBackStack() }, onOpenPage = { nav.navigate(it) })
        }
        // Developer-only pages: each registered with developerComposable, so it can't be opened while the mode is off.
        developerComposable(DeveloperPages.REQUEST_LOG, onLocked = { nav.popBackStack() }) {
            RequestLogRoute(onBack = { nav.popBackStack() })
        }
        developerComposable(DeveloperPages.DERIVED_KEYS, onLocked = { nav.popBackStack() }) {
            DerivedKeysRoute(onBack = { nav.popBackStack() })
        }
        developerComposable(DeveloperPages.LOCAL_REGTEST, onLocked = { nav.popBackStack() }) {
            LocalRegtestRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.DELETE) {
            DeleteWalletRoute(
                onBack = { nav.popBackStack() },
                onBackup = { nav.navigate(Routes.BACKUP) },
                // Nothing of the deleted account stays reachable: continue with the remaining one, or start over.
                onDeleted = { remains -> replaceAll(if (remains) Routes.HOME else Routes.WELCOME) },
            )
        }
        composable(
            Routes.MINT,
            arguments =
                listOf(
                    navArgument("host") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("device") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
        ) {
            MintDetailsRoute(
                onBack = { nav.popBackStack() },
                onChangeHome = { host -> nav.navigate(Routes.changeMint(host)) },
                onMoveMoney = { host -> nav.navigate(Routes.moveMoney(host)) },
                onMoveAllHere = { nav.navigate(Routes.MOVE_HOME) },
            )
        }
        composable(
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
            MoneyMoveRoute(MoneyMoveMode.ChangeHome, entry.arguments?.getString("host"), onClose = { nav.popBackStack() })
        }
        composable(
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
            MoneyMoveRoute(MoneyMoveMode.Move, entry.arguments?.getString("from"), onClose = { nav.popBackStack() })
        }
        composable(Routes.MOVE_HOME) {
            MoneyMoveRoute(MoneyMoveMode.MoveHome, null, onClose = { nav.popBackStack() })
        }
        composable(Routes.ACCOUNT_MINTS) {
            AccountMintsRoute(onBack = { nav.popBackStack() }, onOpen = { host -> nav.navigate(Routes.mint(host)) })
        }
        composable(Routes.MINTS) {
            MintsRoute(
                onBack = { nav.popBackStack() },
                onOpen = { host -> nav.navigate(Routes.mint(host, device = true)) },
                onAdd = { nav.navigate(Routes.ADD_MINT) },
            )
        }
        composable(Routes.SECURITY) {
            SecuritySettingsRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutRoute(appName, BuildConfig.VERSION_NAME, BuildConfig.SUPPORT_URL, onBack = { nav.popBackStack() })
        }
        composable(Routes.DISPLAY) {
            DisplaySettingsRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.NOTES) {
            NotesSettingsRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.ADDRESSES) {
            AddressesRoute(
                onBack = { nav.popBackStack() },
                onAdd = { nav.navigate(Routes.ADD_ADDRESS) },
                onChangePrimary = { nav.navigate(Routes.CHANGE_ADDRESS) },
                onAddElsewhere = { nav.navigate(Routes.ADD_ADDRESS_ELSEWHERE) },
            )
        }
        composable(Routes.ADD_ADDRESS) {
            OnboardingRoute(
                onDone = { nav.popBackStack() },
                mode = OnboardingMode.AddAddress,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.ADD_ADDRESS_ELSEWHERE) {
            OnboardingRoute(
                onDone = { nav.popBackStack() },
                mode = OnboardingMode.AddAddressElsewhere,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CHANGE_ADDRESS) {
            OnboardingRoute(
                onDone = { nav.popBackStack() },
                mode = OnboardingMode.ChangeAddress,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.BACKUP) {
            BackupRoute(onDone = { nav.popBackStack() })
        }
        // A new account's backup during setup: no way back, then on to [next] (the wallet, or the brand's welcome page), or back where
        // "Add account" was opened.
        composable(
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
                    if (next == null) nav.popBackStack() else nav.navigate(next) { popUpTo(Routes.BACKUP_AT_SETUP) { inclusive = true } }
                },
            )
        }
        composable(Routes.BRAND, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val screen = brand.byId.getValue(checkNotNull(entry.arguments?.getString("id")))
            screen.Content(
                onBack = {
                    if (screen.entry == BrandScreenEntry.AfterOnboarding) {
                        nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                }
            )
        }
    }
}

/** The brand's own pages, checked once: ids unique and usable in a route, at most one welcome page. */
private class BrandScreens(screens: Set<BrandScreen>) {
    val byId: Map<String, BrandScreen> = screens.associateBy { it.id }

    init {
        screens.forEach { check(it.id.matches(Regex("[a-z0-9-]+"))) { "Brand screen id '${it.id}': use lowercase letters, digits and -" } }
        check(byId.size == screens.size) { "Two brand screens share an id: ${screens.map { it.id }}" }
        check(screens.count { it.entry == BrandScreenEntry.AfterOnboarding } <= 1) {
            "More than one brand screen is shown after onboarding"
        }
    }

    val afterOnboarding: BrandScreen? = screens.firstOrNull { it.entry == BrandScreenEntry.AfterOnboarding }

    fun on(entry: BrandScreenEntry): List<BrandScreen> = byId.values.filter { it.entry == entry }.sortedBy { it.id }
}

/** The Notes tab for Home: the notes view, its refresh (asking the mints about every note) and its settings button. */
@Composable
private fun notesTab(
    onSettings: () -> Unit,
    onOpenShared: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onClaim: (String) -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
): NotesTabSlot {
    val state by viewModel.state.collectAsStateWithLifecycle()
    return NotesTabSlot(
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        actions = { NoteSettingsButton(onSettings) },
        content = { NotesTab(viewModel, onOpenShared, onOpenArchive, onClaim) },
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
        SettingsTarget.AccountMints -> Routes.ACCOUNT_MINTS
        SettingsTarget.Security -> Routes.SECURITY
        SettingsTarget.About -> Routes.ABOUT
        SettingsTarget.Delete -> Routes.DELETE
        SettingsTarget.Developer -> Routes.DEVELOPER
        SettingsTarget.Keys -> DeveloperPages.DERIVED_KEYS
    }
