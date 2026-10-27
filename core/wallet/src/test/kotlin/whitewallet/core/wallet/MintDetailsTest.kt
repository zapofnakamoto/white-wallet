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

class MintDetailsTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private fun withWallet(test: suspend (MockMint, InMemoryWalletStore, Wallet) -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start("--baseFeeMsat=1000").use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
            test(mint, store, wallet)
        }
    }

    @Test
    fun `details show fees, limits, the pinned key and the account's money there`() = withWallet { mint, _, wallet ->
        val branch = CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 0)), 21_000)
        wallet.refresh()
        val details = wallet.mintDetails(mint.host)
        assertEquals(MintDetails.Reachability.Reachable, details.reachability)
        assertEquals(1_000, details.mint?.feeBaseMsat)
        assertNotNull(details.mint?.mintPubkey)
        assertEquals(21_000, details.balanceMsat)
        assertEquals(1, details.noteCount)
        assertEquals(true, details.isHome)
        assertEquals(branch.cx1(), wallet.watchOnlyKey(mint.host))
    }

    @Test
    fun `a changed signing key and an unreachable mint are told apart`() = withWallet { mint, store, wallet ->
        val mint = assertNotNull(store.mint(mint.host))
        store.saveMint(mint.copy(mintPubkey = "02" + "11".repeat(32)))
        assertEquals(MintDetails.Reachability.KeyChanged, wallet.mintDetails(mint.host).reachability)
        assertEquals(MintDetails.Reachability.Unreachable, wallet.mintDetails("localhost:1").reachability)
        assertEquals(MintDetails.Reachability.Invalid, wallet.mintDetails("{host}").reachability)
    }
}
