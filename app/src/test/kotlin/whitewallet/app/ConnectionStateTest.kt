package whitewallet.app

import kotlin.test.Test
import kotlin.test.assertEquals
import whitewallet.core.wallet.Connection

class ConnectionStateTest {
    @Test
    fun `the system's connection, offline while simulated, and the system's again after`() {
        val state = ConnectionState()
        state.system(Connection.Unverified)
        assertEquals(Connection.Unverified, state.connection.value)
        state.simulateOffline(true)
        assertEquals(Connection.Offline, state.connection.value)
        state.system(Connection.Online)
        assertEquals(Connection.Offline, state.connection.value, "the system coming back doesn't end the simulation")
        state.simulateOffline(false)
        assertEquals(Connection.Online, state.connection.value)
    }
}
