package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * Several Lightning addresses of one account on the reference mint, each on a share of its own (LUD-26): the wallet adds, confirms,
 * unregisters and switches the primary one, and payments to any of them reach the account.
 *
 * Needs the stack from dev/regtest and LNURLCASH_REGTEST=1; skipped otherwise.
 */
class RegtestMultiAddressTest {
    private val host = Regtest.HOST
    private val client = LnurlcashClient()

    @BeforeEach fun requireStack() = Regtest.requireStack()

    private fun name() = Regtest.name("multi")

    private fun pay(name: String, sats: Long) = Regtest.pay("$name@$host", sats)

    @Test
    fun `the wallet adds, uses and unregisters extra addresses on the real mint`() =
        runBlocking<Unit> {
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(), client, policies = NotePolicies.InMemory(ManualStrategy.ID))
            val alice = name()
            val bob = name()
            assertIs<OnboardResult.Ready>(wallet.onboard(alice, host))
            assertEquals(OnboardResult.LimitReached(1), wallet.addAddress(bob, limit = 1))
            val added = assertIs<OnboardResult.Ready>(wallet.addAddress(bob, limit = 2))
            assertTrue(added.confirmed, "the mint takes the account's proof for the added name")

            pay(bob, 2_000)
            assertEquals(1, wallet.refresh().newNotes, "a payment to the added address arrives in the same account")
            assertEquals(1_999_000, wallet.balanceMsat.first())

            val second = wallet.addresses.first().single { it.address.username == bob }.address
            assertEquals(UnregisterResult.Unregistered, wallet.unregisterAddress(second), "the mint accepts the wallet's unregister proof")
            assertEquals(AddressStatus.Available, wallet.checkAddress(bob, host), "the name is free again")
            assertEquals(listOf(alice), wallet.addresses.first().map { it.address.username })
            assertEquals(1_999_000, wallet.balanceMsat.first(), "money already received stays")
        }

    @Test
    fun `the mint confirms every address is ours, and another one can become the primary`() =
        runBlocking<Unit> {
            val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), client, policies = NotePolicies.InMemory(ManualStrategy.ID))
            val alice = name()
            val bob = name()
            assertIs<OnboardResult.Ready>(wallet.onboard(alice, host))
            assertIs<OnboardResult.Ready>(wallet.addAddress(bob, limit = 2))

            wallet.checkAddresses()
            assertEquals(listOf(AddressStatus.Ours, AddressStatus.Ours), wallet.addresses.first().map { it.status })
            assertEquals(AddressStatus.Ours, wallet.addressStatus.first())

            val second = wallet.addresses.first().single { it.address.username == bob }.address
            wallet.setPrimaryAddress(second)
            assertEquals(bob, wallet.primaryAddress.first()?.username)
            assertEquals(listOf(alice to false, bob to true), wallet.addresses.first().map { it.address.username to it.primary })

            // The old primary is now an extra address the mint lets go; payments to the new one keep arriving.
            val first = wallet.addresses.first().single { it.address.username == alice }.address
            assertEquals(UnregisterResult.Unregistered, wallet.unregisterAddress(first))
            pay(bob, 2_000)
            assertEquals(1, wallet.refresh().newNotes)
            assertEquals(AddressStatus.Ours, wallet.addressStatus.first())
        }
}
