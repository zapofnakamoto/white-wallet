package whitewallet.core.wallet

import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.AddressProof
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.RegistrationOutcome
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.spendDomain

/**
 * Guards how the reference mint handles several Lightning addresses registered to the same `cx1`, and that the
 * wallet finds every payment to any of them. The wallet relies on this; if a mint changes its strategy, this fails.
 *
 * What the mint does (lnurl_mint/db.py: upsert_username, claim_next_index, _id_in_use):
 * - names are keyed by name only, so any number of names can carry the same `cx1`;
 * - each name has its own `next_index` (starting at 0, published as the `text/cpub` hint `cx1:<i>`);
 * - on each invoice the mint walks that name's counter forward past every purpose-2 key ever used on the branch
 *   (outstanding, spent, or reserved by an unpaid invoice), reserves the first free one and stores counter = it + 1.
 *
 * So all names share one gap-free sequence of purpose-2 keys, except that an unpaid invoice leaves its key unused.
 *
 * Needs the stack from dev/regtest and LNURLCASH_REGTEST=1; skipped otherwise.
 */
class RegtestMultiAddressTest {
    private val host = "localhost:8111"
    private val script = File("../../dev/regtest/regtest.sh").canonicalFile
    private val random = SecureRandom()
    private val client = LnurlcashClient()

    @BeforeEach
    fun requireStack() = assumeTrue(System.getenv("LNURLCASH_REGTEST") == "1", "LNURLCASH_REGTEST not set")

    private fun regtest(vararg args: String): String {
        val process = ProcessBuilder(listOf(script.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "regtest.sh ${args.joinToString(" ")} failed:\n$output" }
        return output
    }

    private fun name() = "multi" + (1..6).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    private fun payRequest(name: String) = URI("http://$host/.well-known/lnurlp/$name").toURL().readText()

    /**
     * The index hint in the name's `text/cpub` metadata entry (`cx1:<i>`), read the way AddressCheck reads it. (Not
     * through MintingPayRequest: a registered name advertises no comment, since the mint picks the key itself.)
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

    private fun pay(name: String, sats: Long) = regtest("pay", "$name@$host", sats.toString())

    @Test
    fun `several addresses on one cx1 share one sequence of address keys, and the wallet finds every payment`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), client, policies = NotePolicies.InMemory(ManualStrategy.ID))

        val alice = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(alice, host))
        val account = assertNotNull(store.currentAccountId())
        val branch = wallet.branch(account, host)
        val withdrawUrl = assertNotNull(store.mint(host)).withdrawUrl
        suspend fun register(name: String) {
            val sig = AddressProof.sign(branch, AddressProof.Action.Register, spendDomain(host), name)
            assertEquals(RegistrationOutcome.Registered, client.register(host, name, branch.cx1(), sig), "registering $name")
            assertEquals(AddressStatus.Ours, wallet.checkAddress(name, host), "$name publishes this wallet's cx1")
        }

        /** What the mint holds on purpose-2 key [index]: the note's value, or null if it holds no note there. */
        suspend fun noteAt(index: Long): Long? = try {
            client.lookup(withdrawUrl, branch.notePubkey(NotePurpose.LightningAddress, index)).maxWithdrawableMsat
        } catch (_: NoteLookupError) {
            null
        }

        // Two payments to the first name: keys #0 and #1.
        pay(alice, 1_000)
        pay(alice, 2_000)
        assertEquals(2, hint(alice))

        // A second name on the same cx1 starts its own counter at 0...
        val bob = name()
        register(bob)
        assertEquals(0, hint(bob), "a new name's counter starts at 0")
        // ...but its first payment skips the keys already used on the branch.
        pay(bob, 3_000)
        assertEquals(3, hint(bob))

        // An unpaid invoice reserves a key (#3) that never gets a note.
        invoice(alice, 7_000)
        assertEquals(4, hint(alice))
        pay(alice, 4_000) // #4
        pay(bob, 5_000) // bob's counter (3) skips the reserved #3 and alice's #4: #5
        assertEquals(5, hint(alice))
        assertEquals(6, hint(bob))

        // Each payment landed on the key the strategy predicts (amounts are net of the mint's 1 sat base fee).
        assertEquals(listOf(999_000L, 1_999_000L, 2_999_000L, null, 3_999_000L, 4_999_000L), (0L..5L).map { noteAt(it) })

        // The wallet finds all of them in one refresh: the reserved gap is far below the gap limit.
        val first = wallet.refresh()
        assertEquals(5, first.newNotes)
        assertEquals(14_995_000, wallet.balanceMsat.first())
        assertEquals(6, store.addressScanFrom(account, host))
        assertTrue(wallet.recheckKey(host, NotePurpose.LightningAddress.value, 3).let { it !is KeyProbe.Outstanding && it !is KeyProbe.Spent },
            "the reserved key is not a note and does not count as used")

        // A spent key is never reused either: spend #0, then a third name (counter 0) must land past everything.
        assertIs<RotateResult.Rotated>(wallet.rotateKey(host, NotePurpose.LightningAddress.value, 0))
        val carol = name()
        register(carol)
        pay(carol, 1_000)
        assertEquals(7, hint(carol))
        assertEquals(999_000L, noteAt(6))
        assertEquals(1, wallet.refresh().newNotes)
        assertEquals(7, store.addressScanFrom(account, host))
        assertEquals(14_995_000 + 999_000, wallet.balanceMsat.first())
    }

    @Test
    fun `the wallet adds, uses and unregisters extra addresses on the real mint`() = runBlocking<Unit> {
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
        assertEquals(UnregisterResult.IsPrimary, wallet.unregisterAddress(first))
        assertEquals(UnregisterResult.Unregistered, wallet.unregisterAddress(second), "the mint accepts the wallet's unregister proof")
        assertEquals(AddressStatus.Available, wallet.checkAddress(bob, host), "the name is free again")
        assertEquals(listOf(alice), wallet.addresses.first().map { it.address.username })
        assertEquals(1_999_000, wallet.balanceMsat.first(), "money already received stays")
    }

    @Test
    fun `the mint confirms every address is ours, and another one can become the primary`() = runBlocking<Unit> {
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
        assertEquals(bob, wallet.profile.first()?.username)
        assertEquals(listOf(alice to false, bob to true), wallet.addresses.first().map { it.address.username to it.primary })

        // The old primary is now an extra address the mint lets go; payments to the new one keep arriving.
        val first = wallet.addresses.first().single { it.address.username == alice }.address
        assertEquals(UnregisterResult.IsPrimary, wallet.unregisterAddress(second))
        assertEquals(UnregisterResult.Unregistered, wallet.unregisterAddress(first))
        pay(bob, 2_000)
        assertEquals(1, wallet.refresh().newNotes)
        assertEquals(AddressStatus.Ours, wallet.addressStatus.first())
    }
}
