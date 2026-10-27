package whitewallet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.Wallet

@HiltViewModel
class AccountMintsViewModel @Inject constructor(wallet: Wallet) : ViewModel() {
    val mints: StateFlow<List<AccountMint>> = wallet.accountMints.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun AccountMintsRoute(onBack: () -> Unit, onOpen: (host: String) -> Unit, viewModel: AccountMintsViewModel = hiltViewModel()) {
    val mints by viewModel.mints.collectAsStateWithLifecycle()
    AccountMintsScreen(mints, onBack, onOpen)
}

/**
 * The active account's mints: its home first, then wherever it has money or an address, each with its money there.
 * A mint's page from here has what the account can do on it (change home, move money); the device's list of mints is
 * Settings → Mints.
 */
@Composable
fun AccountMintsScreen(mints: List<AccountMint>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_account_mints), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxWidth()) {
            item { Text(stringResource(R.string.account_mints_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            items(mints, key = { it.host }) { mint ->
                val hint = MaterialTheme.typography.bodySmall
                WalletListItem(
                    headlineContent = { Text(mint.host) },
                    supportingContent = {
                        Column {
                            if (mint.isHome) Text(stringResource(R.string.settings_home_mint), style = hint, color = MaterialTheme.colorScheme.primary)
                            Text(pluralStringResource(R.plurals.service_balance, mint.noteCount, formatAmount(mint.balanceMsat), mint.noteCount), style = hint)
                            if (mint.addresses > 0) Text(pluralStringResource(R.plurals.account_mints_addresses, mint.addresses, mint.addresses), style = hint)
                        }
                    },
                    modifier = Modifier.clickable { onOpen(mint.host) },
                )
                HorizontalDivider()
            }
        }
    }
}
