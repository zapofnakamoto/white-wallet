package whitewallet.feature.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletSwitch
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.WholeDuration
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppLockPolicy
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.Timings
import whitewallet.core.wallet.Wallet

@HiltViewModel
class SecuritySettingsViewModel
@Inject
constructor(private val preferences: AppPreferences, private val wallet: Wallet, defaults: AppDefaults) : ViewModel() {
    /** Null until the setting is read. */
    val appLock: StateFlow<Boolean?> =
        preferences.appLock.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The brand's `security.appLock=always`: on, with no switch. */
    val always = defaults.appLock == AppLockPolicy.Always
    val relockAfterMillis = defaults.timings.relockAfterMillis

    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch { preferences.setAppLock(enabled) }
    }

    /** Whether the brand offers "Allow mints without a signing key" (`mints.withoutSigningKey.setting`). */
    val withoutSigningKeyOffered = wallet.offersMintsWithoutSigningKey

    /** The active wallet's choice; null until read. */
    val withoutSigningKey: StateFlow<Boolean?> =
        wallet.allowsMintsWithoutSigningKey
            .map<Boolean, Boolean?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setWithoutSigningKey(allowed: Boolean) {
        viewModelScope.launch { wallet.setAllowsMintsWithoutSigningKey(allowed) }
    }
}

@Composable
fun SecuritySettingsRoute(onBack: () -> Unit, viewModel: SecuritySettingsViewModel = hiltViewModel()) {
    val appLock by viewModel.appLock.collectAsStateWithLifecycle()
    val withoutKey by viewModel.withoutSigningKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val available = DeviceAuth.isAvailable(context)
    val title = stringResource(R.string.security_app_lock)
    val subtitle = stringResource(R.string.security_confirm)
    SecuritySettingsScreen(
        appLock,
        available,
        onBack,
        viewModel.always,
        viewModel.relockAfterMillis,
        withoutSigningKey =
            if (viewModel.withoutSigningKeyOffered) MintsWithoutKeyChoice(withoutKey, viewModel::setWithoutSigningKey) else null,
    ) { enabled ->
        // Both ways ask for the phone's lock: whoever holds the unlocked phone can't quietly switch it off.
        DeviceAuth.authenticate(
            activity ?: return@SecuritySettingsScreen,
            title,
            subtitle,
            onSuccess = { viewModel.setAppLock(enabled) },
            onCancel = {},
        )
    }
}

/** The active wallet's "Allow mints without a signing key": [allowed] is null until read. */
class MintsWithoutKeyChoice(val allowed: Boolean?, val onChange: (Boolean) -> Unit)

/**
 * App lock: opening the app (and returning to it after a while) asks for the phone's own lock. And, where the brand offers it, whether the
 * wallet may use mints without a signing key ([withoutSigningKey]).
 */
@Composable
fun SecuritySettingsScreen(
    appLock: Boolean?,
    available: Boolean,
    onBack: () -> Unit,
    always: Boolean = false,
    relockAfterMillis: Long = Timings().relockAfterMillis,
    withoutSigningKey: MintsWithoutKeyChoice? = null,
    onAppLock: (Boolean) -> Unit,
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.security_title), onBack) }) { padding ->
        // Not read yet (a moment): nothing, rather than a switch that is off first.
        val loaded = appLock != null && (withoutSigningKey == null || withoutSigningKey.allowed != null)
        if (!pageLoaded(loaded, Modifier.padding(padding))) return@Scaffold
        appLock ?: return@Scaffold
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            WalletListItem(
                headlineContent = { Text(stringResource(R.string.security_app_lock)) },
                supportingContent = {
                    Column {
                        Text(if (available) appLockHint(relockAfterMillis) else stringResource(R.string.security_no_screen_lock))
                        if (always) Text(stringResource(R.string.security_app_lock_on_always))
                    }
                },
                trailingContent =
                    if (always) null
                    else {
                        { WalletSwitch(checked = appLock, onCheckedChange = onAppLock, enabled = available || appLock) }
                    },
            )
            withoutSigningKey?.allowed?.let { allowed ->
                WalletListItem(
                    headlineContent = { Text(stringResource(R.string.security_mints_without_key)) },
                    supportingContent = { Text(stringResource(R.string.security_mints_without_key_hint)) },
                    trailingContent = { WalletSwitch(checked = allowed, onCheckedChange = withoutSigningKey.onChange) },
                )
            }
            Text(
                stringResource(R.string.security_encrypted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** What the lock does, with the brand's `security.appLock.relockAfter` in its largest whole unit. */
@Composable
private fun appLockHint(relockAfterMillis: Long): String {
    if (relockAfterMillis == 0L) return stringResource(R.string.security_app_lock_hint_always)
    val after =
        WholeDuration.exact(relockAfterMillis, listOf(WholeDuration.Unit.Seconds, WholeDuration.Unit.Minutes, WholeDuration.Unit.Hours))
    val id =
        when (after.unit) {
            WholeDuration.Unit.Hours -> R.plurals.security_app_lock_hint_hours
            WholeDuration.Unit.Minutes -> R.plurals.security_app_lock_hint_minutes
            else -> R.plurals.security_app_lock_hint_seconds
        }
    return pluralStringResource(id, after.count, after.count)
}
