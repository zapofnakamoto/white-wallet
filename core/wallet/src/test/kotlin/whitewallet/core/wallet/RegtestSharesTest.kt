package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.client.MintDialect

/**
 * Lightning addresses on a LUD-26 mint (luds 92e430f, lnurl-mint 0.13): each address is registered for its own share `S/a'/2/n'`, its
 * payments land on that share's notes, and whether a name is the account's is proven to the mint with the share's key.
 */
class RegtestSharesTest {
    @BeforeEach fun requireStack() = Regtest.requireStack(MintDialect.Current)

    @Test
    fun `each address gets its own share, and the payments to each are found on it`() =
        runBlocking<Unit> {
            val phone = Regtest.phone()
            val second = Regtest.name()
            assertIs<OnboardResult.Ready>(phone.wallet.addAddress(second, limit = 5))
            val accountId = phone.wallet.activeId()
            assertEquals(
                mapOf(phone.address.substringBefore('@') to 0L, second to 1L),
                phone.store.addresses(accountId).first().associate { it.username to it.share },
            )

            Regtest.pay(phone.address, 1000)
            Regtest.pay("$second@$HOST", 2000)
            Regtest.pay("$second@$HOST", 3000)
            phone.wallet.refresh()
            val received = phone.store.notes(accountId).first().filter { it.purpose == 2 }
            assertEquals(mapOf(0L to 1, 1L to 2), received.groupingBy { it.share!! }.eachCount(), "$received")
            assertTrue(received.all { it.scheme == KeyScheme.Bip32 })
            assertEquals(received.map { it.amountMsat }.sum(), phone.balance())
        }

    @Test
    fun `the mint takes the account's proof for its own address, and nobody else's`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            val bob = Regtest.phone()
            val name = alice.address.substringBefore('@')
            assertEquals(AddressStatus.Ours, alice.wallet.checkAddress(name, HOST))
            assertEquals(AddressStatus.Taken, bob.wallet.checkAddress(name, HOST))
            assertEquals(AddressStatus.Taken, alice.wallet.checkAddress(name, HOST, forNewAccount = true))
            assertEquals(AddressStatus.Available, bob.wallet.checkAddress(Regtest.name(), HOST))
            assertEquals(AddressStatus.Unconfirmed, bob.wallet.checkAddress("mint", HOST), "the mint's own name is nobody's to claim")
            assertIs<OnboardResult.UsernameTaken>(bob.wallet.addAddress("mint", limit = 5))
            // Asking changed nothing: the address is still alice's, on its share, and still paid there.
            Regtest.pay(alice.address, 1000)
            alice.wallet.refresh()
            assertTrue(alice.balance() > 0)
        }

    @Test
    fun `a registration whose answer is lost is settled by asking the mint about its share`() =
        runBlocking<Unit> {
            var lose = true
            val client = Regtest.client { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                if (lose && request.method == "POST" && request.url.encodedPath.startsWith("/.well-known/lnurlp/")) {
                    lose = false
                    response.close()
                    Regtest.lost()
                }
                response
            }
            val phone = Regtest.phone(client = client)
            assertTrue(!lose, "the registration's answer was lost")
            val address = phone.store.addresses(phone.wallet.activeId()).first().single()
            assertEquals(0L, address.share, "the share the lost registration was sent with")
            assertEquals(AddressStatus.Ours, phone.wallet.checkAddress(address.username, HOST))
        }

    @Test
    fun `an address unregistered with its share's proof is free again, and a new registration takes the next share`() =
        runBlocking<Unit> {
            val phone = Regtest.phone()
            val extra = Regtest.name()
            assertIs<OnboardResult.Ready>(phone.wallet.addAddress(extra, limit = 5))
            val accountId = phone.wallet.activeId()
            val registered = phone.store.addresses(accountId).first().single { it.username == extra }
            assertEquals(UnregisterResult.Unregistered, phone.wallet.unregisterAddress(registered))
            assertEquals(AddressStatus.Available, phone.wallet.checkAddress(extra, HOST))

            // A share the mint accepted is never registered again: the name comes back on the next one.
            assertIs<OnboardResult.Ready>(phone.wallet.addAddress(extra, limit = 5))
            assertEquals(2L, phone.store.addresses(accountId).first().single { it.username == extra }.share)
            Regtest.pay("$extra@$HOST", 1000)
            phone.wallet.refresh()
            assertEquals(NoteState.Outstanding, phone.store.notes(accountId).first().single { it.share == 2L }.state)
        }
}
