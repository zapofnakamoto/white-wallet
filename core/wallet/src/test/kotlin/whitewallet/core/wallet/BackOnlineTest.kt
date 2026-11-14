package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * When the wallet stops saying the phone is offline ([Wallet.offline]): once its network is found to reach the internet, or after a wait.
 * One state for every screen: one that starts looking during the wait sees what the others see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackOnlineTest {
    private val wait = WalletSettings().backOnlineVerifiedWaitMillis
    private val connection = MutableStateFlow(Connection.Offline)

    private fun TestScope.wallet() =
        Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), connection = connection, scope = backgroundScope).also {
            runCurrent()
        }

    /** Every value [Wallet.offline] took for a screen that looks from now on. */
    private fun TestScope.seen(wallet: Wallet): List<Boolean> {
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { wallet.offline.collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test
    fun `offline lasts until the network is found to reach the internet, and is said at once`() = runTest {
        val wallet = wallet()
        val seen = seen(wallet)
        connection.value = Connection.Unverified
        advanceTimeBy(wait / 2)
        assertEquals(listOf(true), seen, "still offline: the network may not carry requests yet")
        connection.value = Connection.Online
        runCurrent()
        assertEquals(listOf(true, false), seen)
        connection.value = Connection.Offline
        runCurrent()
        assertEquals(listOf(true, false, true), seen)
    }

    @Test
    fun `a network never found to reach the internet ends offline after the wait all the same`() = runTest {
        val wallet = wallet()
        connection.value = Connection.Unverified
        advanceTimeBy(wait - 1)
        assertEquals(true, wallet.offline.value)
        advanceTimeBy(2)
        assertEquals(false, wallet.offline.value)
    }

    @Test
    fun `offline again during the wait starts it over`() = runTest {
        val wallet = wallet()
        val seen = seen(wallet)
        connection.value = Connection.Unverified
        advanceTimeBy(wait / 2)
        connection.value = Connection.Offline
        runCurrent()
        connection.value = Connection.Unverified
        advanceTimeBy(wait / 2 + 1)
        assertEquals(listOf(true), seen, "the first wait didn't carry over")
        advanceTimeBy(wait)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `a screen that starts looking during the wait sees offline too`() = runTest {
        val wallet = wallet()
        connection.value = Connection.Unverified
        advanceTimeBy(wait / 2)
        assertEquals(listOf(true), seen(wallet))
    }

    @Test
    fun `online from the start, a network not found to work later is no reason to say offline`() = runTest {
        connection.value = Connection.Online
        val wallet = wallet()
        connection.value = Connection.Unverified
        advanceTimeBy(wait * 2)
        assertEquals(false, wallet.offline.value)
    }
}
