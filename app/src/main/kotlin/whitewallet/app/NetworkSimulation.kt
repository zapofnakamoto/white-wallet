package whitewallet.app

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import whitewallet.core.wallet.AppPreferences
import whitewallet.lnurlcash.client.NetworkConditions

/** Keeps [NetworkConditions] as the developer chose them, and normal whenever developer mode is off or in a release build. */
class NetworkSimulation @Inject constructor(private val conditions: NetworkConditions, private val preferences: AppPreferences) {
    fun start() {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(preferences.developerMode, preferences.networkProfile) { on, profile -> if (on && BuildConfig.DEBUG) profile else NetworkConditions.Profile.Normal }
                .collect { conditions.profile = it }
        }
    }
}
