package whitewallet.core.wallet

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/** Removing a mint or an account, and looking at a mint, while the wallet works on something else (a refresh that waits for a mint). */
class RemoveWhileBusyTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    /** Holds the first request back once [hold] is called, until [release]: a refresh then keeps the wallet's lock. Others go through. */
    private class Gate {
        @Volatile private var holding = false
        private val claimed = AtomicBoolean(false)
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun pass() {
            if (!holding || !claimed.compareAndSet(false, true)) return
            entered.countDown()
            released.await(30, TimeUnit.SECONDS)
        }

        fun hold() {
            holding = true
        }

        /** Until a request is held back. */
        fun awaitHeld() = check(entered.await(10, TimeUnit.SECONDS)) { "nothing was sent" }

        fun release() = released.countDown()
    }

    private class Fixture(
        val store: InMemoryWalletStore,
        val wallet: Wallet,
        val gate: Gate,
        val home: String,
        val other: String,
        val savings: String,
    )

    private fun withBusyWallet(test: suspend Fixture.() -> Unit) =
        runBlocking<Unit> {
            assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
            MockMint.start().use { mint ->
                val gate = Gate()
                val http =
                    LnurlcashClient.defaultHttpClient()
                        .newBuilder()
                        .addInterceptor { chain ->
                            gate.pass()
                            chain.proceed(chain.request())
                        }
                        .build()
                val store = InMemoryWalletStore()
                val wallet =
                    Wallet(
                        store,
                        InMemorySeedStore(mapOf("a" to words)),
                        LnurlcashClient(http = http),
                        policies = NotePolicies.InMemory(ManualStrategy.ID),
                    )
                // An address: a refresh asks the mint about it.
                store.addAccount("a", "Main", PrimaryAddress("alice", mint.host), host = mint.host)
                assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
                store.useAccount("a")
                val savings = assertIs<CreateAccountResult.Created>(wallet.createAccount("Savings")).accountId
                // A mint nothing ties an account to: it can be removed.
                val other = "other.example"
                store.saveMint(checkNotNull(store.mint(mint.host)).copy(host = other))
                Fixture(store, wallet, gate, mint.host, other, savings).test()
            }
        }

    /** Runs [block] while a refresh waits for the mint's answer, holding the wallet's lock. */
    private suspend fun Fixture.duringRefresh(block: suspend CoroutineScope.() -> Unit) = coroutineScope {
        gate.hold()
        val refresh = async(Dispatchers.IO) { wallet.refresh() }
        gate.awaitHeld()
        try {
            block()
        } finally {
            gate.release()
        }
        refresh.await()
    }

    @Test
    fun `what removing the account would meet is told at once, without waiting for the refresh`() = withBusyWallet {
        duringRefresh {
            val removal = withTimeout(5_000) { wallet.accountRemoval() }
            assertNull(removal.blocked)
        }
    }

    @Test
    fun `a mint's page shows without waiting for the refresh`() = withBusyWallet {
        duringRefresh {
            val details = withTimeout(5_000) { wallet.mintDetails(home) }
            assertEquals(MintDetails.Reachability.Reachable, details.reachability)
            assertEquals(1_000, details.mint?.minSendableMsat)
        }
    }

    @Test
    fun `a removal goes through once the refresh is done, also when the screen that asked was left`() = withBusyWallet {
        duringRefresh {
            val mint = launch(Dispatchers.IO) { wallet.removeMint(other) }
            val account = launch(Dispatchers.IO) { wallet.removeAccount() }
            delay(200)
            // The user gave up waiting and went back: the screen's work is cancelled.
            mint.cancel()
            account.cancel()
            gate.release()
            mint.join()
            account.join()
        }
        assertNull(store.mint(other), "the mint is removed")
        assertTrue(wallet.accounts.first().none { it.account.id == savings }, "the account is removed")
        assertEquals("a", store.currentAccountId())
    }
}
