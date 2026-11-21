package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
            // The refresh's first request is held back, as on a bad connection: it keeps the wallet until the test lets it go.
            val gate = Gate()
            val client = Regtest.client { chain ->
                gate.pass()
                chain.proceed(chain.request())
            }
            val alice = Regtest.phone(client = client)
            val wallet = alice.wallet
            assertIs<ConnectResult.Connected>(wallet.connectMint(HOST3))
            alice.fund(1000)
            val savings = assertIs<CreateAccountResult.Created>(wallet.createAccount("Savings")).accountId

            gate.hold()
            val refresh = async(Dispatchers.IO) { wallet.refresh() }
            gate.awaitHeld()
            try {
                // The question opens at once; it doesn't wait for the refresh.
                assertNull(withTimeout(5_000) { wallet.accountRemoval() }.blocked)
                // Started here, each runs until it waits for the wallet: asked for, and waiting, by the next line.
                val mint = launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) { wallet.removeMint(HOST3) }
                val account = launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) { wallet.removeAccount() }
                assertTrue(refresh.isActive && mint.isActive && account.isActive, "the removals wait for the refresh")
                // The user gave up waiting and went back.
                mint.cancel()
                account.cancel()
                gate.release()
                refresh.await()
                mint.join()
                account.join()
            } finally {
                gate.release()
            }

            assertTrue(wallet.mints.first().none { it.host == HOST3 }, "the mint is removed")
            assertTrue(wallet.accounts.first().none { it.account.id == savings }, "the account is removed")
            assertEquals(HOST, wallet.homeHost.first())
        }
}
