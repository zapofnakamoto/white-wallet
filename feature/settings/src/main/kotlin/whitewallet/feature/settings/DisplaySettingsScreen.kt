package whitewallet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.LocalAmountUnit
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.model.AmountUnit
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletRadioButton

@HiltViewModel
class DisplaySettingsViewModel @Inject constructor(private val preferences: AppPreferences, defaults: AppDefaults) : ViewModel() {
    val unit: StateFlow<AmountUnit> = preferences.amountUnit.map(defaults::amountUnit)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), defaults.defaultAmountUnit)

    fun setUnit(unit: AmountUnit) {
        viewModelScope.launch { preferences.setAmountUnit(unit) }
    }
}

@Composable
fun DisplaySettingsRoute(onBack: () -> Unit, viewModel: DisplaySettingsViewModel = hiltViewModel()) {
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    DisplaySettingsScreen(unit, onBack, viewModel::setUnit)
}

/** How amounts are shown: ₿ as whole numbers (BIP-177) or sats; the numbers are the same either way. */
@Composable
fun DisplaySettingsScreen(unit: AmountUnit, onBack: () -> Unit, onUnit: (AmountUnit) -> Unit) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_amounts), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.settings_amounts_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AmountUnit.entries.forEach { option ->
                WalletListItem(
                    leadingContent = { WalletRadioButton(selected = unit == option, onClick = { onUnit(option) }) },
                    headlineContent = { Text(stringResource(unitTitle(option))) },
                    supportingContent = { Text(unitExample(option)) },
                    modifier = Modifier.clickable { onUnit(option) },
                )
            }
            Text(
                stringResource(R.string.settings_amounts_hide_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

internal fun unitTitle(unit: AmountUnit): Int = when (unit) {
    AmountUnit.Bitcoin -> R.string.settings_unit_bitcoin
    AmountUnit.Sats -> R.string.settings_unit_sats
}

/** A sample amount in [unit], whatever unit the rest of the app currently uses. */
@Composable
internal fun unitExample(unit: AmountUnit): String {
    var example = ""
    CompositionLocalProvider(LocalAmountUnit provides unit) { example = formatAmount(EXAMPLE_MSAT) }
    return example
}

private const val EXAMPLE_MSAT = 21_000_000L
