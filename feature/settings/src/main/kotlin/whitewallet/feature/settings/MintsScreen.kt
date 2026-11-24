package whitewallet.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Page
import whitewallet.core.designsystem.AccountActions
import whitewallet.core.designsystem.AccountSwitcher
import whitewallet.core.designsystem.BrandBody
import whitewallet.core.designsystem.EmptyState
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.WalletAccountButton
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.wallet.NoHome

@Composable
fun MintsRoute(
    onBack: () -> Unit,
    onOpen: (host: String) -> Unit,
    onAdd: () -> Unit,
    onNoHome: (NoHome) -> Unit,
    onChangeHome: () -> Unit,
    onManageAccounts: () -> Unit,
    onMoveFunds: () -> Unit,
    viewModel: MintsPageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MintsScreen(
        state,
        onBack,
        onOpen,
        onAdd,
        onNoHome,
        onChangeHome,
        AccountActions(viewModel::switchAccount, onManageAccounts, onMoveFunds),
    )
}

/**
 * The wallet's mints as the account in use sees them: its home as a card on top with the way to change it, the other mints as quiet cards
 * below, and the way to add one. The account's mark in the top bar switches account, as on Home. Nothing until everything is read.
 */
@Composable
fun MintsScreen(
    state: MintsPageState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onNoHome: (NoHome) -> Unit,
    onChangeHome: () -> Unit,
    accountActions: AccountActions,
) {
    var showAccounts by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            WalletTopBar(stringResource(R.string.settings_mints), onBack) {
                if (state.loaded) state.accounts?.let { WalletAccountButton(it) { showAccounts = true } }
            }
        }
    ) { padding ->
        if (!pageLoaded(state.loaded, Modifier.padding(padding))) return@Scaffold
        BrandBody(Page.Mints, if (state.empty) WalletStatus.Empty else WalletStatus.Idle) {
            LazyColumn(Modifier.padding(padding).imePadding().fillMaxWidth()) {
                if (state.empty) {
                    item {
                        EmptyState(
                            stringResource(R.string.mints_empty),
                            action = if (state.canAdd) ({ PrimaryButton(stringResource(R.string.mints_add), onAdd) }) else null,
                        )
                    }
                    return@LazyColumn
                }
                item(key = "home") {
                    when {
                        state.home != null -> HomeMintCard(state.home, onOpen, onChangeHome.takeIf { state.canChangeHome })
                        state.noHome != null -> NoHomeCard(state.noHome, onNoHome)
                    }
                }
                items(state.others, key = { it.host }) { OtherMintCard(it, onOpen) }
                if (state.canAdd)
                    item {
                        WalletButton(kind = ButtonKind.Secondary, onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(stringResource(R.string.mints_add))
                        }
                    }
            }
        }
    }
    val accounts = state.accounts
    if (showAccounts && accounts != null) {
        AccountSwitcher(
            accounts = accounts.accounts,
            activeId = accounts.activeId,
            walletNames = accounts.walletNames,
            onSelect = {
                showAccounts = false
                accountActions.onSwitch(it)
            },
            onManage = {
                showAccounts = false
                accountActions.onManage()
            },
            onMove = {
                showAccounts = false
                accountActions.onMove()
            },
            onDismiss = { showAccounts = false },
        )
    }
}

/** In the home card's place while the account has none: what is missing, and the way to it, as on Receive. */
@Composable
private fun NoHomeCard(missing: NoHome, onNoHome: (NoHome) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.settings_no_home), style = MaterialTheme.typography.bodyLarge)
        PrimaryButton(stringResource(R.string.settings_no_home_action), { onNoHome(missing) }, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}
