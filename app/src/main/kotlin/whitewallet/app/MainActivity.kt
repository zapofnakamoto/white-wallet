package whitewallet.app

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
import androidx.compose.material3.CircularProgressIndicator
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
import whitewallet.core.designsystem.WalletTheme
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
import whitewallet.feature.settings.MintsRoute
import whitewallet.feature.settings.RequestLogRoute
import whitewallet.feature.settings.SettingsRoute
import whitewallet.feature.settings.SecuritySettingsRoute
import whitewallet.feature.settings.AboutRoute
import whitewallet.feature.settings.ServiceDetailsRoute

@AndroidEntryPoint
// FragmentActivity because the recovery phrase screen uses BiometricPrompt.
class MainActivity : FragmentActivity() {
    /** Text from a deep link, a share or an NFC tag, waiting to be opened (see [IncomingLink]). */
    private val incoming = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming.value = linkText(intent)
        setContent {
            val display: DisplayViewModel = hiltViewModel()
            val unit by display.amountUnit.collectAsStateWithLifecycle()
            WalletTheme {
                CompositionLocalProvider(LocalAmountUnit provides unit) {
                    Surface(Modifier.fillMaxSize()) {
                        AppLockGate(stringResource(R.string.app_name)) { WalletApp(incoming, onIncomingHandled = { incoming.value = null }) }
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
    const val SERVICE = "settings/service?host={host}"
    const val HOME_SERVICE = "settings/service"
    const val SECURITY = "settings/security"
    const val ABOUT = "settings/about"
    fun service(host: String) = "settings/service?host=${Uri.encode(host)}"
    const val ADD_ADDRESS = "addresses/add"
    const val ADD_ADDRESS_ELSEWHERE = "addresses/add-elsewhere"
    const val DELETE = "delete"
    const val NEW_ACCOUNT = "account/new"
    const val NEW_ACCOUNT_ON = "account/new?host={host}"
    const val MOVE_FUNDS = "move"
    const val DEVELOPER = "settings/developer"
}

@Composable
private fun WalletApp(incoming: StateFlow<String?>, onIncomingHandled: () -> Unit, start: StartViewModel = hiltViewModel()) {
    val startRoute by start.start.collectAsStateWithLifecycle()
    val first = startRoute ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
                onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } },
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
                onOpenService = { host -> nav.navigate(Routes.service(host)) },
                onReceive = { nav.navigate(Routes.RECEIVE) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onBackup = { nav.navigate(Routes.BACKUP) },
                onChangeAddress = { nav.navigate(Routes.CHANGE_ADDRESS) },
                onAddAccount = { nav.navigate(Routes.NEW_ACCOUNT) },
                onMoveFunds = { nav.navigate(Routes.MOVE_FUNDS) },
                notesTab = if (NotesView.fromConfig(BuildConfig.NOTES_VIEW) == NotesView.Tab) notesTab(onSettings = { nav.navigate(Routes.NOTES) }, onOpenShared = { id -> nav.navigate(Routes.note(id)) }) else null,
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
        composable(Routes.SERVICE, arguments = listOf(navArgument("host") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            ServiceDetailsRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.MINTS) {
            MintsRoute(onBack = { nav.popBackStack() }, onOpen = { host -> nav.navigate(Routes.service(host)) })
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
    }
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
    SettingsTarget.Service -> Routes.HOME_SERVICE
    SettingsTarget.Security -> Routes.SECURITY
    SettingsTarget.About -> Routes.ABOUT
    SettingsTarget.Delete -> Routes.DELETE
    SettingsTarget.Developer -> Routes.DEVELOPER
}
