package whitewallet.core.wallet

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * Guards how the reference mint handles several Lightning addresses registered to the same `cx1`, and that the wallet finds every payment
 * to any of them. The wallet relies on this; if a mint changes its strategy, this fails.
 *
 * What the mint does (lnurl_mint/db.py: upsert_username, claim_next_index, _id_in_use):
 * - names are keyed by name only, so any number of names can carry the same `cx1`;
 * - each name has its own `next_index` (starting at 0, published as the `text/cpub` hint `cx1:<i>`);
 * - on each invoice the mint walks that name's counter forward past every purpose-2 key ever used on the branch (outstanding, spent, or
 *   reserved by an unpaid invoice), reserves the first free one and stores counter = it + 1.
 *
 * So all names share one gap-free sequence of purpose-2 keys, except that an unpaid invoice leaves its key unused.
 *
 * Needs the stack from dev/regtest and LNURLCASH_REGTEST=1; skipped otherwise.
 */
class RegtestMultiAddressTest {
    private val host = Regtest.HOST
    private val client = LnurlcashClient()

    @BeforeEach fun requireStack() = Regtest.requireStack()

    private fun name() = Regtest.name("multi")

    private fun payRequest(name: String) = URI("http://$host/.well-known/lnurlp/$name").toURL().readText()

    /**
     * The index hint in the name's `text/cpub` metadata entry (`cx1:<i>`), read the way AddressCheck reads it. (Not through
     * MintingPayRequest: a registered name advertises no comment, since the mint picks the key itself.)
     */
    private suspend fun hint(name: String): Long {
        val pay = assertIs<LnurlResponse.PayRequest>(client.get("http://$host/.well-known/lnurlp/$name"))
        val cpub = assertNotNull(pay.metadataEntries.firstOrNull { it.first == "text/cpub" }, "$name publishes text/cpub").second
        return cpub.substringAfterLast(':').toLong()
    }

    /** Asks the mint for an invoice to [name] without paying it; the mint reserves a key for it right away. */
    private fun invoice(name: String, sats: Long): String {
        val callback = payRequest(name).substringAfter("\"callback\":\"").substringBefore('"')
        val sep = if ('?' in callback) '&' else '?'
        return URI("$callback${sep}amount=${sats * 1000}").toURL().readText().substringAfter("\"pr\":\"").substringBefore('"')
    }

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
            assertTrue(added.confirmed, "the mint publishes this account's cx1 for the added name")

            pay(bob, 2_000)
            assertEquals(1, wallet.refresh().newNotes, "a payment to the added address arrives in the same account")
            assertEquals(1_999_000, wallet.balanceMsat.first())

            val (first, second) = wallet.addresses.first().map { it.address }
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
