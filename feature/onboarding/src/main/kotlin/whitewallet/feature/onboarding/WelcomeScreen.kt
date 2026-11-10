package whitewallet.feature.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.BrandTitle
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.WithReleaseBadge
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.releaseNotice
import whitewallet.core.wallet.CreateAccountResult
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.ScanProgress
import whitewallet.core.wallet.Wallet

data class CreateState(
    val busy: Boolean = false,
    /** The most wallets (on the welcome screen) or accounts (adding one) the app allows, once reached. */
    val limit: Int? = null,
    val created: Boolean = false,
    /** The account made, once [created]. */
    val accountId: String? = null,
    /** Adding an account: how far looking for what it already holds is. */
    val scan: ScanProgress.AccountScan? = null,
    /** Adding an account: why that look stopped. The account wasn't added. */
    val scanFailed: String? = null,
)

/**
 * Creates a wallet (welcome screen), contacting no mint, or an account of the active wallet (account name screen), which is looked for on
 * each of the wallet's mints first.
 */
@HiltViewModel
class CreateViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val _state = MutableStateFlow(CreateState())
    val state: StateFlow<CreateState> = _state

    fun createWallet(name: String) = create { wallet.createWallet(name) }

    fun createAccount(name: String) = create {
        wallet.createAccount(name.trim()) { progress -> _state.update { it.copy(scan = progress.accounts.firstOrNull()) } }
    }

    private fun create(block: suspend () -> CreateAccountResult) {
        if (_state.value.busy || _state.value.created) return
        _state.update { it.copy(busy = true, limit = null, scan = null, scanFailed = null) }
        viewModelScope.launch {
            when (val result = block()) {
                is CreateAccountResult.Created -> _state.update { it.copy(busy = false, created = true, accountId = result.accountId) }
                is CreateAccountResult.AccountLimitReached -> _state.update { it.copy(busy = false, limit = result.limit) }
                is CreateAccountResult.ScanFailed ->
                    _state.update { it.copy(busy = false, scan = null, scanFailed = failureReason(result.result)) }
            }
        }
    }
}

/**
 * Where the app starts without a wallet, and where Settings adds one ([onBack] then): the brand's header, and two ways on. Creating makes
 * the wallet right away ([onCreated]); its mint and address come next.
 */
@Composable
fun WelcomeRoute(
    appName: String,
    logo: @Composable () -> Unit,
    onCreated: () -> Unit,
    onRestore: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: CreateViewModel = hiltViewModel(),
) {
    val name = stringResource(R.string.default_account_name)
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.created) { if (state.created) onCreated() }
    WelcomeScreen(appName, logo, state, onCreate = { viewModel.createWallet(name) }, onRestore, onBack)
}

@Composable
fun WelcomeScreen(
    appName: String,
    logo: @Composable () -> Unit,
    state: CreateState,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val content: @Composable (Modifier) -> Unit = { modifier ->
        ScreenColumn(modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            BrandDecoration(Anchor.OnboardingHeader) { logo() }
            WithReleaseBadge {
                BrandTitle(Anchor.OnboardingTitle, appName) { Text(appName, style = MaterialTheme.typography.headlineMedium) }
            }
            Text(
                stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            state.limit?.let {
                Text(
                    pluralStringResource(R.plurals.onboarding_error_account_limit, it, it),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            PrimaryButton(stringResource(R.string.welcome_create), onCreate, enabled = state.limit == null, busy = state.busy)
            WalletButton(kind = ButtonKind.Secondary, onClick = onRestore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.welcome_restore))
            }
            releaseNotice()?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (onBack == null) Surface(Modifier.fillMaxSize()) { content(Modifier.safeDrawingPadding()) }
    else Scaffold(topBar = { WalletTopBar("", onBack.takeIf { !state.busy }) }) { padding -> content(Modifier.padding(padding)) }
}

/**
 * Adding an account to the active wallet: only its name, prefilled. It exists on every mint of the wallet at once, so what it already holds
 * there is looked for before it opens ([onCreated], with its id).
 */
@Composable
fun AccountNameRoute(onBack: () -> Unit, onCreated: (accountId: String) -> Unit, viewModel: CreateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.accountId) { state.accountId?.let(onCreated) }
    AccountNameScreen(state, stringResource(R.string.new_account_default_name), onBack, viewModel::createAccount)
}

@Composable
fun AccountNameScreen(state: CreateState, defaultName: String, onBack: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.onboarding_new_account_title), onBack.takeIf { !state.busy }) }) { padding ->
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.account_name_body), textAlign = TextAlign.Center)
            WalletTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.onboarding_account_name)) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            state.limit?.let {
                Text(
                    pluralStringResource(R.plurals.account_name_limit, it, it),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            state.scanFailed?.let {
                Text(
                    stringResource(R.string.account_name_scan_failed, it),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            PrimaryButton(
                stringResource(if (state.scanFailed != null) R.string.add_mint_try_again else R.string.onboarding_create_account),
                { onCreate(name) },
                enabled = name.isNotBlank() && state.limit == null,
                busy = state.busy,
            )
            if (state.busy) {
                Hint(stringResource(R.string.account_name_looking))
                state.scan?.let { scan ->
                    val keys = pluralStringResource(R.plurals.add_mint_keys_checked, scan.keysChecked, scan.keysChecked)
                    Hint(if (scan.foundMsat > 0) stringResource(R.string.account_name_found, keys, formatAmount(scan.foundMsat)) else keys)
                }
            }
        }
    }
}

/** Why a scan stopped, in the mint's or the network's words. */
internal fun failureReason(result: OnboardResult): String =
    when (result) {
        is OnboardResult.MintInvalid -> result.reason
        is OnboardResult.NetworkError -> result.reason
        else -> result.toString()
    }
