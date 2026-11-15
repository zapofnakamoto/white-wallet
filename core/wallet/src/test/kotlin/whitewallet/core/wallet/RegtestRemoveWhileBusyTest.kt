package whitewallet.core.wallet

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.core.wallet.Regtest.HOST3

/** Removing mints and accounts on the real reference mints (dev/regtest, LNURLCASH_REGTEST=1), also while the wallet is busy. */
@ExtendWith(ThirdMintLock::class)
class RegtestRemoveWhileBusyTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    @Test
    fun `a home mint that was removed stays removed when its page looks at it again`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            assertIs<ConnectResult.Connected>(alice.wallet.setHomeMint(HOST2).result)
            assertEquals(RemoveMintResult.Removed, alice.wallet.removeMint(HOST2))
            // The page of the home that went reloads as the home changes.
            val details = alice.wallet.mintDetails(HOST2)
            assertEquals(MintDetails.Reachability.Reachable, details.reachability)
            assertNull(details.mint)
            assertTrue(alice.wallet.mints.first().none { it.host == HOST2 }, "still removed")
            assertNull(alice.wallet.homeHost.first())
        }

    @Test
    fun `removals wait for a slow refresh, and go through also when the screen that asked was left`() =
        runBlocking<Unit> {
            val slow = AtomicBoolean(false)
            // Only the home mint answers slowly, as on a bad connection to it.
            val client = Regtest.client { chain ->
                if (slow.get() && chain.request().url.port == 8111) Thread.sleep(500)
                chain.proceed(chain.request())
            }
            val alice = Regtest.phone(client = client)
            val wallet = alice.wallet
            assertIs<ConnectResult.Connected>(wallet.connectMint(HOST3))
            alice.fund(1000)
            val savings = assertIs<CreateAccountResult.Created>(wallet.createAccount("Savings")).accountId

            slow.set(true)
            val refresh = async(Dispatchers.IO) { wallet.refresh() }
            delay(300)
            // The question opens at once; it doesn't wait for the refresh.
            assertNull(withTimeout(1_000) { wallet.accountRemoval() }.blocked)
            val mint = launch(Dispatchers.IO) { wallet.removeMint(HOST3) }
            val account = launch(Dispatchers.IO) { wallet.removeAccount() }
            delay(300)
            assertTrue(refresh.isActive, "the refresh still holds the wallet")
            // The user gave up waiting and went back.
            mint.cancel()
            account.cancel()
            refresh.await()
            mint.join()
            account.join()

            assertTrue(wallet.mints.first().none { it.host == HOST3 }, "the mint is removed")
            assertTrue(wallet.accounts.first().none { it.account.id == savings }, "the account is removed")
            assertEquals(HOST, wallet.homeHost.first())
        }
}
