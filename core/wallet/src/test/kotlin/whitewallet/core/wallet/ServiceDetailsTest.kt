package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

class ServiceDetailsTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private fun withWallet(test: suspend (MockMint, InMemoryWalletStore, Wallet) -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start("--baseFeeMsat=1000").use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
            test(mint, store, wallet)
        }
    }

    @Test
    fun `details show fees, limits, the pinned key and the account's money there`() = withWallet { mint, _, wallet ->
        val branch = CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 0)), 21_000)
        wallet.refresh()
        val details = wallet.serviceDetails(mint.host)
        assertEquals(ServiceDetails.Reachability.Reachable, details.reachability)
        assertEquals(1_000, details.service?.feeBaseMsat)
        assertNotNull(details.service?.mintPubkey)
        assertEquals(21_000, details.balanceMsat)
        assertEquals(1, details.noteCount)
        assertEquals(true, details.isHome)
        assertEquals(branch.cx1(), wallet.watchOnlyKey(mint.host))
    }

    @Test
    fun `a changed signing key and an unreachable service are told apart`() = withWallet { mint, store, wallet ->
        val service = assertNotNull(store.service(mint.host))
        store.saveService(service.copy(mintPubkey = "02" + "11".repeat(32)))
        assertEquals(ServiceDetails.Reachability.KeyChanged, wallet.serviceDetails(mint.host).reachability)
        assertEquals(ServiceDetails.Reachability.Unreachable, wallet.serviceDetails("localhost:1").reachability)
        assertEquals(ServiceDetails.Reachability.Invalid, wallet.serviceDetails("{host}").reachability)
    }
}
