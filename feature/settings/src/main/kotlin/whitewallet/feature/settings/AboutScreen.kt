package whitewallet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.DeveloperOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import whitewallet.core.designsystem.WalletTopBar

/** A library shipped in the app, with its license. Names are proper nouns and stay untranslated. */
data class License(val library: String, val license: String, val url: String)

/** Every third-party library in the release APK (keep in sync with gradle/libs.versions.toml). */
val shippedLicenses = listOf(
    License("AndroidX (Activity, Biometric, CameraX, Compose, DataStore, Fragment, Lifecycle, Navigation, Room, WorkManager)", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    License("Material Components for Compose", "Apache License 2.0", "https://developer.android.com/jetpack/compose"),
    License("Dagger Hilt", "Apache License 2.0", "https://dagger.dev/hilt/"),
    License("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0", "https://kotlinlang.org"),
    License("OkHttp, Okio", "Apache License 2.0", "https://square.github.io/okhttp/"),
    License("bitcoin-kmp", "Apache License 2.0", "https://github.com/ACINQ/bitcoin-kmp"),
    License("secp256k1-kmp (libsecp256k1)", "Apache License 2.0 / MIT", "https://github.com/ACINQ/secp256k1-kmp"),
    License("ZXing", "Apache License 2.0", "https://github.com/zxing/zxing"),
    License("SQLCipher for Android", "BSD-3-Clause", "https://www.zetetic.net/sqlcipher/"),
)

/** Unlocks Developer options from About, for brands with `developerOptions=unlock`. */
@HiltViewModel
class AboutViewModel @Inject constructor(private val preferences: AppPreferences, defaults: AppDefaults) : ViewModel() {
    /** Tapping the version still has something to unlock. */
    val canUnlock: StateFlow<Boolean> = combine(preferences.developerMode, preferences.developerOptionsUnlocked) { mode, unlocked ->
        defaults.developerOptions == DeveloperOptions.Unlock && !defaults.developerOptionsVisible(mode, unlocked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun unlock() {
        viewModelScope.launch { preferences.setDeveloperOptionsUnlocked() }
    }
}

@Composable
fun AboutRoute(appName: String, versionName: String, supportUrl: String, onBack: () -> Unit, viewModel: AboutViewModel = hiltViewModel()) {
    val canUnlock by viewModel.canUnlock.collectAsStateWithLifecycle()
    AboutScreen(appName, versionName, supportUrl, onBack, onUnlock = viewModel::unlock.takeIf { canUnlock })
}

/** Taps on the version that unlock Developer options, as on Android itself. */
const val UNLOCK_TAPS = 7

/**
 * About: the app, its version, where to get help, its own license, and the libraries it ships with. With [onUnlock],
 * tapping the version [UNLOCK_TAPS] times unlocks Developer options; the last few taps count down.
 */
@Composable
fun AboutScreen(appName: String, versionName: String, supportUrl: String, onBack: () -> Unit, onUnlock: (() -> Unit)? = null) {
    val uri = LocalUriHandler.current
    var taps by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.about_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            val left = UNLOCK_TAPS - taps
            ListItem(
                headlineContent = { Text(appName, style = MaterialTheme.typography.titleLarge) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.about_version, versionName))
                        when {
                            taps >= UNLOCK_TAPS -> Text(stringResource(R.string.about_developer_unlocked), color = MaterialTheme.colorScheme.primary)
                            onUnlock != null && taps > 2 -> Text(pluralStringResource(R.plurals.about_developer_taps, left, left))
                        }
                    }
                },
                modifier = if (onUnlock != null || taps >= UNLOCK_TAPS) {
                    Modifier.clickable {
                        if (onUnlock != null && taps < UNLOCK_TAPS) {
                            taps++
                            if (taps == UNLOCK_TAPS) onUnlock()
                        }
                    }
                } else {
                    Modifier
                },
            )
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_support)) },
                supportingContent = { Text(supportUrl) },
                modifier = Modifier.clickable { runCatching { uri.openUri(supportUrl) } },
            )
            ListItem(headlineContent = { Text(stringResource(R.string.about_app_license)) }, supportingContent = { Text(stringResource(R.string.about_app_license_value)) })
            HorizontalDivider()
            Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
            shippedLicenses.forEach { item ->
                ListItem(
                    headlineContent = { Text(item.library) },
                    supportingContent = { Text(item.license) },
                    modifier = Modifier.clickable { runCatching { uri.openUri(item.url) } },
                )
            }
        }
    }
}
