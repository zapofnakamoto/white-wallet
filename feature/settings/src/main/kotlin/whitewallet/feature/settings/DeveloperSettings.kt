package whitewallet.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletSwitch

/** A page that is only reachable while developer mode is on. Registered by the app module. */
data class DeveloperPage(@StringRes val title: Int, @StringRes val description: Int, val route: String)

@HiltViewModel
class DeveloperSettingsViewModel @Inject constructor(private val preferences: AppPreferences, defaults: AppDefaults) : ViewModel() {
    val enabled: StateFlow<Boolean> = preferences.developerMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The developer limit, starting from the brand's until changed here. */
    private val brandLimit = defaults.maxAddresses.coerceIn(1, MAX_ADDRESSES)
    val maxAddresses: StateFlow<Int> = preferences.maxAddressesPerMint.map { it ?: brandLimit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), brandLimit)

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { preferences.setDeveloperMode(value) }
    }

    fun setMaxAddresses(value: Int) {
        viewModelScope.launch { preferences.setMaxAddressesPerMint(value.coerceIn(1, MAX_ADDRESSES)) }
    }

    companion object {
        const val MAX_ADDRESSES = 20
    }
}

@Composable
fun DeveloperSettingsRoute(
    pages: List<DeveloperPage>,
    onBack: () -> Unit,
    onOpenPage: (route: String) -> Unit,
    viewModel: DeveloperSettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val maxAddresses by viewModel.maxAddresses.collectAsStateWithLifecycle()
    DeveloperSettingsScreen(enabled, pages, onBack, viewModel::setEnabled, onOpenPage, maxAddresses, viewModel::setMaxAddresses)
}

@Composable
fun DeveloperSettingsScreen(
    enabled: Boolean,
    pages: List<DeveloperPage>,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenPage: (route: String) -> Unit,
    maxAddresses: Int = 1,
    onMaxAddresses: (Int) -> Unit = {},
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.developer_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            WalletListItem(
                headlineContent = { Text(stringResource(R.string.developer_mode)) },
                supportingContent = { Text(stringResource(R.string.developer_mode_hint)) },
                trailingContent = { WalletSwitch(checked = enabled, onCheckedChange = onToggle) },
                modifier = Modifier.clickable { onToggle(!enabled) },
            )
            if (enabled) {
                HorizontalDivider()
                WalletListItem(
                    headlineContent = { Text(stringResource(R.string.developer_max_addresses)) },
                    supportingContent = { Text(stringResource(R.string.developer_max_addresses_hint)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val fewer = stringResource(R.string.developer_max_addresses_fewer)
                            val more = stringResource(R.string.developer_max_addresses_more)
                            WalletButton(
                                kind = ButtonKind.Text,
                                onClick = { onMaxAddresses(maxAddresses - 1) },
                                enabled = maxAddresses > 1,
                                modifier = Modifier.semantics { contentDescription = fewer },
                            ) { Text("−") }
                            Text(NumberFormat.getIntegerInstance().format(maxAddresses), style = MaterialTheme.typography.titleMedium)
                            WalletButton(
                                kind = ButtonKind.Text,
                                onClick = { onMaxAddresses(maxAddresses + 1) },
                                enabled = maxAddresses < DeveloperSettingsViewModel.MAX_ADDRESSES,
                                modifier = Modifier.semantics { contentDescription = more },
                            ) { Text("+") }
                        }
                    },
                )
                HorizontalDivider()
                if (pages.isEmpty()) {
                    Text(
                        stringResource(R.string.developer_no_pages),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                pages.forEach { page ->
                    WalletListItem(
                        headlineContent = { Text(stringResource(page.title)) },
                        supportingContent = { Text(stringResource(page.description)) },
                        modifier = Modifier.clickable { onOpenPage(page.route) },
                    )
                }
            }
        }
    }
}
