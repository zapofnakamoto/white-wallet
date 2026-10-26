package whitewallet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.feature.settings.DeveloperPage
import whitewallet.feature.settings.R as SettingsR

/**
 * Pages listed under Settings > Developer options while developer mode is on. To add one:
 * 1. add a [DeveloperPage] here with a route under `developer/`, and
 * 2. register its screen in the nav graph with [developerComposable], so it can't be opened while the mode is off.
 */
object DeveloperPages {
    const val REQUEST_LOG = "developer/logs"
    const val DERIVED_KEYS = "developer/keys"
    const val LOCAL_REGTEST = "developer/regtest"

    val all: List<DeveloperPage> = listOf(
        DeveloperPage(SettingsR.string.logs_title, SettingsR.string.logs_description, REQUEST_LOG),
        DeveloperPage(SettingsR.string.keys_title, SettingsR.string.keys_description, DERIVED_KEYS),
        DeveloperPage(SettingsR.string.regtest_title, SettingsR.string.regtest_description, LOCAL_REGTEST),
    )
}

@HiltViewModel
class DeveloperGateViewModel @Inject constructor(preferences: AppPreferences, defaults: AppDefaults) : ViewModel() {
    /** Developer mode is on and the brand lets users reach it; null until loaded. */
    val enabled: StateFlow<Boolean?> = combine(preferences.developerMode, preferences.developerOptionsUnlocked) { mode, unlocked ->
        mode && defaults.developerOptionsVisible(mode, unlocked)
    }.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/** A destination that only renders while developer mode is on; otherwise it calls [onLocked] (usually: go back). */
fun NavGraphBuilder.developerComposable(route: String, onLocked: () -> Unit, content: @Composable () -> Unit) {
    composable(route) {
        val gate: DeveloperGateViewModel = hiltViewModel()
        val enabled by gate.enabled.collectAsStateWithLifecycle()
        when (enabled) {
            true -> content()
            false -> LaunchedEffect(Unit) { onLocked() }
            null -> Unit
        }
    }
}
