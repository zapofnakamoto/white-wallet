package whitewallet.app

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.wallet.AppPreferences
import whitewallet.feature.settings.DeviceAuth

/**
 * The optional app lock (Settings → Security): while on, the app asks for the phone's own lock when it starts and
 * when it comes back after [RELOCK_AFTER_MILLIS] in the background. The wallet stays composed underneath (so
 * navigation and incoming links survive) but is hidden, also from accessibility services, until unlocked.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(preferences: AppPreferences) : ViewModel() {
    private val unlocked = MutableStateFlow(false)
    private var backgroundSince: Long? = null

    /** Null until the setting is known, so nothing of the wallet flashes before the lock. */
    val locked: StateFlow<Boolean?> = combine(preferences.appLock, unlocked) { enabled, open -> enabled && !open }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun unlock() {
        unlocked.value = true
    }

    fun onStop() {
        backgroundSince = SystemClock.elapsedRealtime()
    }

    fun onStart() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (SystemClock.elapsedRealtime() - since >= RELOCK_AFTER_MILLIS) unlocked.value = false
    }

    private companion object {
        const val RELOCK_AFTER_MILLIS = 30_000L
    }
}

@Composable
fun AppLockGate(appName: String, viewModel: AppLockViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    Box(Modifier.fillMaxSize()) {
        Box(if (locked != false) Modifier.clearAndSetSemantics {} else Modifier) { content() }
        when (locked) {
            null -> Surface(Modifier.fillMaxSize()) {}
            true -> LockScreen(appName, viewModel::unlock)
            false -> Unit
        }
    }
}

@Composable
private fun LockScreen(appName: String, onUnlocked: () -> Unit) {
    val activity = LocalActivity.current as? FragmentActivity
    val title = stringResource(R.string.lock_title, appName)
    val subtitle = stringResource(R.string.lock_subtitle)
    val ask = { activity?.let { DeviceAuth.authenticate(it, title, subtitle, onSuccess = onUnlocked, onCancel = {}) } }
    // Asks right away; the button is there if the prompt was dismissed.
    LaunchedEffect(Unit) { ask() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Image(painterResource(R.drawable.ic_brand_logo), contentDescription = null, modifier = Modifier.size(96.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            PrimaryButton(stringResource(R.string.lock_unlock), { ask() })
        }
    }
}
