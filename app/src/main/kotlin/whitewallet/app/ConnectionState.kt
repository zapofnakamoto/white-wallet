package whitewallet.app

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import whitewallet.core.wallet.Connection

/**
 * The connection the wallet goes by: the system's, or offline while a developer chose so ([NetworkSimulation]). Both write it at once, so
 * it is offline before the first simulated drop: the wallet never counts that drop as a mint that doesn't answer.
 */
@Singleton
class ConnectionState @Inject constructor() {
    private val state = MutableStateFlow(Connection.Online)
    private var system = Connection.Online
    private var simulatedOffline = false

    val connection: StateFlow<Connection> = state.asStateFlow()

    @Synchronized
    fun system(connection: Connection) {
        system = connection
        update()
    }

    @Synchronized
    fun simulateOffline(offline: Boolean) {
        simulatedOffline = offline
        update()
    }

    private fun update() {
        state.value = if (simulatedOffline) Connection.Offline else system
    }
}
