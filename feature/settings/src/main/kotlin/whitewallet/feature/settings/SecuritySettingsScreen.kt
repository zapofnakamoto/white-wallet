package whitewallet.feature.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.wallet.AppPreferences

@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(private val preferences: AppPreferences) : ViewModel() {
    val appLock: StateFlow<Boolean> = preferences.appLock.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch { preferences.setAppLock(enabled) }
    }
}

@Composable
fun SecuritySettingsRoute(onBack: () -> Unit, viewModel: SecuritySettingsViewModel = hiltViewModel()) {
    val appLock by viewModel.appLock.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val available = DeviceAuth.isAvailable(context)
    val title = stringResource(R.string.security_app_lock)
    val subtitle = stringResource(R.string.security_confirm)
    SecuritySettingsScreen(appLock, available, onBack) { enabled ->
        // Both ways ask for the phone's lock: whoever holds the unlocked phone can't quietly switch it off.
        DeviceAuth.authenticate(activity ?: return@SecuritySettingsScreen, title, subtitle, onSuccess = { viewModel.setAppLock(enabled) }, onCancel = {})
    }
}

/** App lock: opening the app (and returning to it after a while) asks for the phone's own lock. */
@Composable
fun SecuritySettingsScreen(appLock: Boolean, available: Boolean, onBack: () -> Unit, onAppLock: (Boolean) -> Unit) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.security_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.security_app_lock)) },
                supportingContent = {
                    Text(stringResource(if (available) R.string.security_app_lock_hint else R.string.security_no_screen_lock))
                },
                trailingContent = { Switch(checked = appLock, onCheckedChange = onAppLock, enabled = available || appLock) },
            )
            Text(
                stringResource(R.string.security_encrypted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
