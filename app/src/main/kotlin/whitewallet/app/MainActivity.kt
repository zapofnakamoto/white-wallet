package whitewallet.app

import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.IncomingLink
import whitewallet.core.wallet.NotesView
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.LaunchedEffect
import android.nfc.NfcAdapter
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Optional
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.BrandScreen
import whitewallet.brand.ui.BrandScreenEntry
import whitewallet.brand.ui.DefaultBrandDesign
import whitewallet.brand.ui.LocalWalletEvents
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.LocalBuildLabel
import whitewallet.core.designsystem.LocalReleaseStage
import whitewallet.core.designsystem.WalletCircularProgress
import whitewallet.core.designsystem.WalletTheme
import whitewallet.feature.home.HomeMenuItem
import whitewallet.feature.settings.BrandPage
import whitewallet.core.designsystem.LocalAmountUnit
import androidx.compose.runtime.CompositionLocalProvider
import whitewallet.core.wallet.Wallet
import whitewallet.feature.claim.ClaimRoute
import whitewallet.feature.home.HomeRoute
import whitewallet.feature.home.NotesTabSlot
import whitewallet.feature.notes.NoteSettingsButton
import whitewallet.feature.notes.NotesRoute
import whitewallet.feature.notes.NotesTab
import whitewallet.feature.notes.NotesViewModel
import whitewallet.feature.home.MoveFundsRoute
import whitewallet.feature.onboarding.OnboardingMode
import whitewallet.feature.onboarding.OnboardingRoute
import whitewallet.feature.onboarding.RestoreRoute
import whitewallet.feature.receive.ReceiveRoute
import whitewallet.feature.send.CreateNoteRoute
import whitewallet.feature.send.SendRoute
import whitewallet.feature.send.SharedNoteRoute
import whitewallet.feature.settings.AddressesRoute
import whitewallet.feature.settings.NotesSettingsRoute
import whitewallet.feature.settings.SearchDestination
import whitewallet.feature.settings.SettingsTarget
import whitewallet.feature.settings.AccountSettingsRoute
import whitewallet.feature.settings.BackupRoute
import whitewallet.feature.settings.DeleteWalletRoute
import whitewallet.feature.settings.DisplaySettingsRoute
import whitewallet.feature.settings.DerivedKeysRoute
import whitewallet.feature.settings.DeveloperSettingsRoute
import whitewallet.feature.settings.LocalRegtestRoute
import whitewallet.feature.settings.AccountMintsRoute
import whitewallet.feature.settings.MintsRoute
import whitewallet.feature.settings.MoneyMoveMode
import whitewallet.feature.settings.MoneyMoveRoute
import whitewallet.feature.settings.RequestLogRoute
import whitewallet.feature.settings.SettingsRoute
import whitewallet.feature.settings.SecuritySettingsRoute
import whitewallet.feature.settings.AboutRoute
import whitewallet.feature.settings.MintDetailsRoute

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming.value = linkText(intent)
        setContent {
            val display: DisplayViewModel = hiltViewModel()
            val unit by display.amountUnit.collectAsStateWithLifecycle()
            val appName = stringResource(R.string.app_name)
            WalletTheme(design.orElse(DefaultBrandDesign)) {
                CompositionLocalProvider(LocalAmountUnit provides unit, LocalAppName provides appName, LocalReleaseStage provides defaults.releaseStage, LocalBuildLabel provides BuildConfig.BUILD_LABEL.ifEmpty { null }, LocalWalletEvents provides events.events) {
                    Surface(Modifier.fillMaxSize()) {
                        AppLockGate(appName) { WalletApp(incoming, BrandScreens(brandScreens), showNotesTab = defaults.notesView == NotesView.Tab, onIncomingHandled = { incoming.value = null }) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkText(intent)?.let { incoming.value = it }
    }

    private fun linkText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW, NfcAdapter.ACTION_NDEF_DISCOVERED -> intent.dataString
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
            _start.value = when {
                wallet.isSetUp() -> Routes.HOME
                // Restored, but the app closed before the address was picked.
                wallet.needsAddress() -> Routes.RESTORE_ADDRESS
                else -> Routes.ONBOARDING
            }
        }
    }
}

internal object Routes {
    const val ONBOARDING = "onboarding"
    const val RESTORE = "restore"
    const val RESTORE_ACCOUNT = "account/restore"
    const val RESTORE_ADDRESS = "restore/address"
    const val RESTORE_ADDRESS_ON = "restore/address?host={host}"
    const val HOME = "home"
    const val RECEIVE = "receive"
    const val SEND = "send"
    const val SEND_WITH = "send?input={input}"
    fun send(input: String) = "send?input=${Uri.encode(input)}"
    const val CREATE_NOTE = "send/note"
    const val NOTE = "note/{id}"
    fun note(id: String) = "note/$id"
    const val CLAIM = "claim"
    const val CLAIM_WITH = "claim?input={input}"
    fun claim(input: String) = "claim?input=${Uri.encode(input)}"
    const val SETTINGS = "settings"
    const val ACCOUNT_SETTINGS = "settings/account"
    const val BACKUP = "backup"
    const val CHANGE_ADDRESS = "address"
    const val ADDRESSES = "addresses"
    /** Note settings (strategy, collecting, auto-rotate). */
    const val NOTES = "settings/notes"

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
    const val NEW_ACCOUNT_ON = "account/new?host={host}"
    const val MOVE_FUNDS = "move"
    const val DEVELOPER = "settings/developer"
    const val BRAND = "brand/{id}"
    fun brand(id: String) = "brand/$id"
}

@Composable
private fun WalletApp(incoming: StateFlow<String?>, brand: BrandScreens, showNotesTab: Boolean, onIncomingHandled: () -> Unit, start: StartViewModel = hiltViewModel()) {
    val startRoute by start.start.collectAsStateWithLifecycle()
    val first = startRoute ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { WalletCircularProgress() }
        return
    }
    val appName = stringResource(R.string.app_name)
    val nav = rememberNavController()
    // A restored account has no address yet; picking one (if this app requires one) replaces everything before it, so
    // back can't return there.
    val afterRestore = { host: String ->
        nav.navigate(if (BuildConfig.REQUIRE_ADDRESS) "restore/address?host=$host" else Routes.HOME) { popUpTo(0) { inclusive = true } }
    }
    // Links from outside open once the wallet is set up (Home is on the back stack): a note in Claim, anything else
    // in Send. Nothing is claimed or paid until the user confirms there.
    val pending by incoming.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()
    LaunchedEffect(pending, current) {
        val text = pending ?: return@LaunchedEffect
        if (current == null || runCatching { nav.getBackStackEntry(Routes.HOME) }.isFailure) return@LaunchedEffect
        when (val link = IncomingLink.classify(text)) {
            is IncomingLink.Claim -> nav.navigate(Routes.claim(link.text)) { popUpTo(Routes.HOME) }
            is IncomingLink.Pay -> nav.navigate(Routes.send(link.text)) { popUpTo(Routes.HOME) }
            null -> Unit
        }
        onIncomingHandled()
    }
    NavHost(nav, startDestination = if (first == Routes.RESTORE_ADDRESS) Routes.RESTORE_ADDRESS_ON else first) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                appName = appName,
                logo = { Image(painterResource(R.drawable.ic_brand_logo), contentDescription = null) },
                // The brand's welcome page, if it has one, comes between setup and the wallet.
                onDone = { nav.navigate(brand.afterOnboarding?.let { Routes.brand(it.id) } ?: Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } },
                onRestore = { nav.navigate(Routes.RESTORE) },
            )
        }
        composable(Routes.RESTORE) {
            RestoreRoute(asNewAccount = false, onBack = { nav.popBackStack() }, onRestored = afterRestore)
        }
        composable(Routes.RESTORE_ACCOUNT) {
            RestoreRoute(asNewAccount = true, onBack = { nav.popBackStack() }, onRestored = afterRestore)
        }
        composable(Routes.RESTORE_ADDRESS_ON, arguments = listOf(navArgument("host") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            OnboardingRoute(
                appName, logo = {}, mode = OnboardingMode.RestoreAddress, presetHost = entry.arguments?.getString("host"),
                onDone = { nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } } },
                onRestoreCancelled = { remains -> nav.navigate(if (remains) Routes.HOME else Routes.ONBOARDING) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable(Routes.HOME) {
            HomeRoute(
                appName,
                onSend = { nav.navigate(Routes.SEND) },
                onOpenNote = { id -> nav.navigate(Routes.note(id)) },
                onOpenMint = { host -> nav.navigate(Routes.mint(host)) },
                onReceive = { nav.navigate(Routes.RECEIVE) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onBackup = { nav.navigate(Routes.BACKUP) },
                onChangeAddress = { nav.navigate(Routes.CHANGE_ADDRESS) },
                onAddAccount = { nav.navigate(Routes.NEW_ACCOUNT) },
                onMoveFunds = { nav.navigate(Routes.MOVE_FUNDS) },
                menu = brand.on(BrandScreenEntry.HomeMenu).map { screen -> HomeMenuItem(screen.title) { nav.navigate(Routes.brand(screen.id)) } },
                notesTab = if (showNotesTab) notesTab(onSettings = { nav.navigate(Routes.NOTES) }, onOpenShared = { id -> nav.navigate(Routes.note(id)) }) else null,
            )
        }
        composable(Routes.NOTE_LIST) {
            NotesRoute(onBack = { nav.popBackStack() }, onSettings = { nav.navigate(Routes.NOTES) }, onOpenShared = { id -> nav.navigate(Routes.note(id)) })
        }
        composable(Routes.NEW_ACCOUNT_ON, arguments = listOf(navArgument("host") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            OnboardingRoute(
                appName, logo = {}, onDone = { nav.popBackStack() }, mode = OnboardingMode.NewAccount, onBack = { nav.popBackStack() },
                presetHost = entry.arguments?.getString("host"),
                onRestore = { nav.navigate(Routes.RESTORE_ACCOUNT) },
            )
        }
        composable(Routes.MOVE_FUNDS) {
            MoveFundsRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.SEND_WITH, arguments = listOf(navArgument("input") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            SendRoute(onDone = { nav.popBackStack() }, onCreateNote = { nav.navigate(Routes.CREATE_NOTE) })
        }
        composable(Routes.CREATE_NOTE) {
            CreateNoteRoute(onBack = { nav.popBackStack() }, onCreated = { id -> nav.navigate(Routes.note(id)) { popUpTo(Routes.HOME) } })
        }
        composable(Routes.NOTE, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            SharedNoteRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.RECEIVE) {
            ReceiveRoute(onBack = { nav.popBackStack() }, onClaim = { nav.navigate(Routes.CLAIM) { popUpTo(Routes.HOME) } })
        }
        composable(Routes.CLAIM_WITH, arguments = listOf(navArgument("input") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            ClaimRoute(onBack = { nav.popBackStack(Routes.HOME, inclusive = false) })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                BuildConfig.VERSION_NAME,
                DeveloperPages.all,
                brandPages = brand.on(BrandScreenEntry.Settings).map { BrandPage(it.title, Routes.brand(it.id)) },
                onBack = { nav.popBackStack() },
                onNavigate = { destination ->
                    nav.navigate(
                        when (destination) {
                            is SearchDestination.Route -> destination.route
                            is SearchDestination.Page -> settingsRoute(destination.target)
                        },
                    )
                },
            )
        }
        composable(Routes.ACCOUNT_SETTINGS) {
            AccountSettingsRoute(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(settingsRoute(it)) })
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
            LocalRegtestRoute(onBack = { nav.popBackStack() }, onAddAccount = { host -> nav.navigate("account/new?host=$host") })
        }
        composable(Routes.DELETE) {
            DeleteWalletRoute(
                onBack = { nav.popBackStack() },
                onBackup = { nav.navigate(Routes.BACKUP) },
                // Nothing of the deleted account stays reachable: continue with the remaining one, or start over.
                onDeleted = { remains ->
                    nav.navigate(if (remains) Routes.HOME else Routes.ONBOARDING) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(
            Routes.MINT,
            arguments = listOf(
                navArgument("host") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("device") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            MintDetailsRoute(
                onBack = { nav.popBackStack() },
                onChangeHome = { host -> nav.navigate(Routes.changeMint(host)) },
                onMoveMoney = { host -> nav.navigate(Routes.moveMoney(host)) },
                onMoveAllHere = { nav.navigate(Routes.MOVE_HOME) },
            )
        }
        composable(Routes.CHANGE_MINT, arguments = listOf(navArgument("host") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            MoneyMoveRoute(MoneyMoveMode.ChangeHome, entry.arguments?.getString("host"), onClose = { nav.popBackStack() })
        }
        composable(Routes.MOVE_MONEY, arguments = listOf(navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            MoneyMoveRoute(MoneyMoveMode.Move, entry.arguments?.getString("from"), onClose = { nav.popBackStack() })
        }
        composable(Routes.MOVE_HOME) {
            MoneyMoveRoute(MoneyMoveMode.MoveHome, null, onClose = { nav.popBackStack() })
        }
        composable(Routes.ACCOUNT_MINTS) {
            AccountMintsRoute(onBack = { nav.popBackStack() }, onOpen = { host -> nav.navigate(Routes.mint(host)) })
        }
        composable(Routes.MINTS) {
            MintsRoute(onBack = { nav.popBackStack() }, onOpen = { host -> nav.navigate(Routes.mint(host, device = true)) })
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
            OnboardingRoute(appName, logo = {}, onDone = { nav.popBackStack() }, mode = OnboardingMode.AddAddress, onBack = { nav.popBackStack() })
        }
        composable(Routes.ADD_ADDRESS_ELSEWHERE) {
            OnboardingRoute(appName, logo = {}, onDone = { nav.popBackStack() }, mode = OnboardingMode.AddAddressElsewhere, onBack = { nav.popBackStack() })
        }
        composable(Routes.CHANGE_ADDRESS) {
            OnboardingRoute(appName, logo = {}, onDone = { nav.popBackStack() }, mode = OnboardingMode.ChangeAddress, onBack = { nav.popBackStack() })
        }
        composable(Routes.BACKUP) {
            BackupRoute(onDone = { nav.popBackStack() })
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
                },
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
        check(screens.count { it.entry == BrandScreenEntry.AfterOnboarding } <= 1) { "More than one brand screen is shown after onboarding" }
    }

    val afterOnboarding: BrandScreen? = screens.firstOrNull { it.entry == BrandScreenEntry.AfterOnboarding }

    fun on(entry: BrandScreenEntry): List<BrandScreen> = byId.values.filter { it.entry == entry }.sortedBy { it.id }
}

/** The Notes tab for Home: the notes view, its refresh (asking the mints about every note) and its settings button. */
@Composable
private fun notesTab(onSettings: () -> Unit, onOpenShared: (String) -> Unit, viewModel: NotesViewModel = hiltViewModel()): NotesTabSlot {
    val state by viewModel.state.collectAsStateWithLifecycle()
    return NotesTabSlot(
        refreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        actions = { NoteSettingsButton(onSettings) },
        content = { NotesTab(viewModel, onOpenShared) },
    )
}

private fun settingsRoute(target: SettingsTarget): String = when (target) {
    SettingsTarget.Account -> Routes.ACCOUNT_SETTINGS
    SettingsTarget.Backup -> Routes.BACKUP
    SettingsTarget.Addresses -> Routes.ADDRESSES
    SettingsTarget.Notes -> Routes.NOTES
    SettingsTarget.NoteList -> Routes.NOTE_LIST
    SettingsTarget.Mints -> Routes.MINTS
    SettingsTarget.Display -> Routes.DISPLAY
    SettingsTarget.AccountMints -> Routes.ACCOUNT_MINTS
    SettingsTarget.Security -> Routes.SECURITY
    SettingsTarget.About -> Routes.ABOUT
    SettingsTarget.Delete -> Routes.DELETE
    SettingsTarget.Developer -> Routes.DEVELOPER
}
