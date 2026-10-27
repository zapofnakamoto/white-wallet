package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/** Key indexes: guards, automatic discovery and the manual tools of the Derived keys page. */
class KeyIndexTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branch: CashBranch)

    private fun withMint(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
            Fixture(mint, store, wallet, CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)).test()
        }
    }

    private fun Fixture.credit(purpose: NotePurpose, index: Long, msat: Long) = mint.credit(Cp1.encode(branch.notePubkey(purpose, index)), msat)

    @Test
    fun `rotate all still works after the wallet counter was forced back to 0`() = withMint {
        // Three address payments, rotated onto wallet keys #0..#2.
        (0L..2L).forEach { credit(NotePurpose.LightningAddress, it, 1_000 * (it + 1)) }
        wallet.refresh()
        assertEquals(3, wallet.rotateAllKeys(mint.host, 2).rotated)
        assertEquals(listOf(0L, 1L, 2L), store.allNotes().filter { it.purpose == 0 }.mapNotNull { it.index }.sorted())

        store.setIndex("a", mint.host, 0, 0) // what "Reset index" used to do

        val result = wallet.rotateAllKeys(mint.host, 0)
        assertEquals(RotateAllResult(rotated = 3, failed = 0, pending = 0, totalMsat = 6_000, stoppedEarly = null), result)
        assertEquals(6_000, wallet.balanceMsat.first())
        assertEquals(3, store.allNotes().count { it.state == NoteState.Outstanding })
    }

    /** A second wallet on the same seed (another device): its own fresh store, same keys. */
    private suspend fun Fixture.otherDevice(): Pair<InMemoryWalletStore, Wallet> {
        val store2 = InMemoryWalletStore()
        val wallet2 = Wallet(store2, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        store2.addAccount("a", "Main", Profile("alice", mint.host))
        assertIs<ConnectResult.Connected>(wallet2.connectMint(mint.host))
        return store2 to wallet2
    }

    @Test
    fun `keys used by another device are discovered when the mint says already in use`() = withMint {
        // The other device used wallet keys #0..#4: three outstanding, two spent (rotated onward to #3, #4).
        (0L..2L).forEach { credit(NotePurpose.Wallet, it, 1_000) }
        val (store2, wallet2) = otherDevice()
        store2.setIndex("a", mint.host, 0, 3)
        assertEquals(3, wallet2.recheckAllKeys(mint.host, 0).imported)
        wallet2.rotateKey(mint.host, 0, 0)
        wallet2.rotateKey(mint.host, 0, 1)

        // This device only knows one incoming address note, and its counter says 0 (it has used #0 before).
        credit(NotePurpose.LightningAddress, 0, 7_000)
        wallet.refresh()
        store.setIndex("a", mint.host, 0, 0)

        val rotated = assertIs<RotateResult.Rotated>(wallet.rotateKey(mint.host, 2, 0))
        assertEquals(5, rotated.toIndex, "the first free wallet key after the other device's #0..#4")
        assertEquals(7_000 + 3_000, wallet.balanceMsat.first(), "discovery imported the other device's outstanding notes")
        assertEquals(6, wallet.keyIndexes(mint.host).wallet)
    }

    @Test
    fun `a fresh store discovers the index before its first claim`() = withMint {
        (0L..3L).forEach { credit(NotePurpose.Wallet, it, 500) }
        val (store2, wallet2) = otherDevice()
        assertEquals(null, store2.currentIndexOrNull("a", mint.host, 0))
        val bearer = whitewallet.lnurlcash.Spend.Bearer(fr.acinq.bitcoin.ByteVector32(fr.acinq.secp256k1.Hex.decode("55".repeat(32))))
        mint.credit(bearer.h.toHex(), 2_000)
        val preview = assertIs<ClaimPreview.Ready>(wallet2.previewClaim("lnurlw://${mint.host}/w?k1=${bearer.k1}"))
        assertIs<ClaimResult.Claimed>(wallet2.claim(preview))
        assertEquals(1, store2.journal.size, "no request was refused as already in use")
        assertEquals(4L, store2.allNotes().single { it.state == NoteState.Outstanding && it.amountMsat == 2_000L }.index)
        assertEquals(4 * 500 + 2_000, wallet2.balanceMsat.first())
    }

    @Test
    fun `find index sets the frontier and marks it verified`() = withMint {
        credit(NotePurpose.Wallet, 0, 1_000)
        credit(NotePurpose.Wallet, 6, 1_000)
        store.setIndex("a", mint.host, 0, 0)
        val result = wallet.findKeyIndex(mint.host, 0)
        assertEquals(DiscoveryResult(next = 7, checked = 27, imported = 2, stoppedEarly = null), result)
        val health = wallet.keyIndexHealth(mint.host, 0)
        assertEquals(IndexHealth(next = 7, highestUsed = 6, lastVerifiedAtMillis = health.lastVerifiedAtMillis), health)
        kotlin.test.assertNotNull(health.lastVerifiedAtMillis)
        kotlin.test.assertFalse(health.behind)
    }

    @Test
    fun `set index refuses values below known used keys`() = withMint {
        credit(NotePurpose.Wallet, 0, 1_000)
        credit(NotePurpose.Wallet, 1, 1_000)
        wallet.findKeyIndex(mint.host, 0)
        assertEquals(SetIndexResult.BelowFloor(2), wallet.setKeyIndex(mint.host, 0, 1))
        assertEquals(2, wallet.keyIndexes(mint.host).wallet, "nothing changed")
        assertEquals(SetIndexResult.Applied(10), wallet.setKeyIndex(mint.host, 0, 10))
        assertEquals(10, wallet.keyIndexes(mint.host).wallet)
        store.setIndex("a", mint.host, 0, 0)
        kotlin.test.assertTrue(wallet.keyIndexHealth(mint.host, 0).behind, "health flags a counter behind used keys")
    }

    @Test
    fun `recheck all syncs every listed key`() = withMint {
        credit(NotePurpose.Wallet, 0, 1_000)
        credit(NotePurpose.Wallet, 2, 3_000)
        store.setIndex("a", mint.host, 0, 3)
        val result = wallet.recheckAllKeys(mint.host, 0)
        assertEquals(3 + AddressScanner.GAP_LIMIT, result.checked)
        assertEquals(2, result.imported)
        assertEquals(4_000, wallet.balanceMsat.first())
    }
}
