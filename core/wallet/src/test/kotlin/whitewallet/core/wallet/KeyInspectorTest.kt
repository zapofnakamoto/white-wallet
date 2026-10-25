package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain
import whitewallet.testkit.MockMint

class KeyInspectorTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branch: CashBranch)

    private fun withMint(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "LNURLCASH_CONFORMANCE not set")
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient())
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
            Fixture(mint, store, wallet, CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)).test()
        }
    }

    private fun Fixture.credit(purpose: NotePurpose, index: Long, msat: Long) = mint.credit(Cp1.encode(branch.notePubkey(purpose, index)), msat)

    @Test
    fun `scanning further finds an address payment beyond the gap limit`() = withMint {
        credit(NotePurpose.LightningAddress, 30, 4_000)
        assertEquals(0, wallet.refresh().newNotes, "a normal refresh stops after 20 unused keys")
        assertEquals(0, wallet.keyIndexes(mint.host).addressScanFrom)

        // Scan ahead with a wider gap limit than the refresh uses reaches it.
        val result = wallet.scanKeysAhead(mint.host, NotePurpose.LightningAddress.value, gap = 40)
        assertEquals(KeyScanResult(from = 0, until = 71, checked = 71, outstanding = 1, imported = 1, stoppedEarly = null, indexAfter = 31), result,
            "it runs on until 40 unused keys follow the note at #30")
        assertEquals(4_000, wallet.balanceMsat.first())
        assertEquals(31, wallet.keyIndexes(mint.host).addressScanFrom)
        assertIs<KeyProbe.Outstanding>(wallet.keyProbes.first()[KeyId("a", mint.host, 2, 30)])
        assertIs<KeyProbe.Unused>(wallet.keyProbes.first()[KeyId("a", mint.host, 2, 29)])
    }

    @Test
    fun `an unknown wallet note is recovered and the counter moves past it`() = withMint {
        credit(NotePurpose.Wallet, 3, 2_500)
        val result = wallet.scanKeysAhead(mint.host, NotePurpose.Wallet.value, gap = 6)
        assertEquals(1, result.imported)
        assertEquals(2_500, wallet.balanceMsat.first())
        assertEquals(4, wallet.keyIndexes(mint.host).wallet, "index 3 is used, so the next new note gets 4")
    }

    @Test
    fun `rechecking a key picks up a spend made elsewhere`() = withMint {
        credit(NotePurpose.Wallet, 0, 1_000)
        wallet.scanKeysAhead(mint.host, NotePurpose.Wallet.value, gap = 1)
        assertEquals(1_000, wallet.balanceMsat.first())

        // Spend the note behind the wallet's back (as another device with the same phrase would).
        val client = LnurlcashClient()
        val withdrawUrl = assertIs<ConnectResult.Connected>(wallet.connectService(mint.host)).service.withdrawUrl
        val spend = branch.spend(NotePurpose.Wallet, 0, spendDomain(mint.host))
        val info = client.noteInfo(withdrawUrl, spend.k1)
        assertIs<MutationOutcome.Confirmed>(client.mutate(MutationType.Rotate, Requests.rotate(info.callback, spend.k1, branch.notePubkey(NotePurpose.Wallet, 7))))

        assertIs<KeyProbe.Spent>(wallet.recheckKey(mint.host, NotePurpose.Wallet.value, 0))
        assertEquals(0L, wallet.balanceMsat.first())
        assertEquals(NoteState.Spent, store.allNotes().single().state)
    }

    @Test
    fun `lookup answers survive a restart, and a lookup without an answer does not erase them`() = withMint {
        // Key 2 is spent at the service, but this wallet never held it (as after a restore).
        credit(NotePurpose.Wallet, 2, 1_000)
        val client = LnurlcashClient()
        val withdrawUrl = assertIs<ConnectResult.Connected>(wallet.connectService(mint.host)).service.withdrawUrl
        val spend = branch.spend(NotePurpose.Wallet, 2, spendDomain(mint.host))
        val info = client.noteInfo(withdrawUrl, spend.k1)
        assertIs<MutationOutcome.Confirmed>(client.mutate(MutationType.Rotate, Requests.rotate(info.callback, spend.k1, branch.notePubkey(NotePurpose.Change, 50))))
        store.setIndex("a", mint.host, NotePurpose.Wallet.value, 4)
        assertIs<KeyProbe.Unused>(wallet.recheckKey(mint.host, NotePurpose.Wallet.value, 1))
        assertIs<KeyProbe.Spent>(wallet.recheckKey(mint.host, NotePurpose.Wallet.value, 2))
        assertTrue(store.allNotes().none { it.index == 2L && it.purpose == 0 }, "no note is made for a key the wallet never held")

        // A new process: same saved state, nothing in memory.
        val restarted = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient())
        val keys = restarted.derivedKeys(mint.host, NotePurpose.Wallet.value)
        assertEquals(listOf(KeyStatus.HandedOut, KeyStatus.Skipped, KeyStatus.Spent, KeyStatus.HandedOut, KeyStatus.Next), keys.take(5).map { it.status })
        assertIs<KeyProbe.Unused>(keys[1].probe)

        // The service stops answering: the key keeps its last answer, and the failure is shown beside it.
        restarted.recordProbe(KeyId("a", mint.host, 0, 1), KeyProbe.Failed("offline", Long.MAX_VALUE))
        val after = restarted.derivedKeys(mint.host, NotePurpose.Wallet.value)[1]
        assertEquals(KeyStatus.Skipped, after.status)
        assertIs<KeyProbe.Unused>(after.probe)
        assertEquals("offline", after.failure?.reason)
    }

    @Test
    fun `scan ahead always starts at the index`() = withMint {
        wallet.recheckKey(mint.host, NotePurpose.LightningAddress.value, 40) // a lookup far ahead (unused)
        val result = wallet.scanKeysAhead(mint.host, NotePurpose.LightningAddress.value, gap = 5)
        assertEquals(0L until 5L, result.from until result.until)
        assertEquals(5, result.checked)
    }

    @Test
    fun `scan ahead moves the index past a used key it finds and keeps going past it`() = withMint {
        store.setAddressScanFrom("a", mint.host, 20)
        credit(NotePurpose.LightningAddress, 25, 1_000)
        credit(NotePurpose.LightningAddress, 44, 1_000) // beyond the first window (#20–#39), within 20 of #25
        val result = wallet.scanKeysAhead(mint.host, NotePurpose.LightningAddress.value)
        assertEquals(20L until 65L, result.from until result.until, "20 unused keys after the last find (#44)")
        assertEquals(2, result.outstanding)
        assertEquals(45, result.indexAfter)
        assertEquals(45, wallet.keyIndexes(mint.host).addressScanFrom)
        assertEquals(2_000, wallet.balanceMsat.first())
    }

    @Test
    fun `recheck all runs on past the last used key too`() = withMint {
        credit(NotePurpose.Wallet, 3, 1_000)
        credit(NotePurpose.Wallet, 8, 1_000) // past #0–#4, but within 5 of #3
        credit(NotePurpose.Wallet, 20, 1_000) // more than 5 unused keys after #8: not reached
        val result = wallet.recheckAllKeys(mint.host, NotePurpose.Wallet.value, gap = 5)
        assertEquals(0L until 14L, result.from until result.until)
        assertEquals(2, result.outstanding)
        assertEquals(9, result.indexAfter)
    }

    @Test
    fun `recheck all covers every handed out key plus the gap and never lowers the index`() = withMint {
        store.setIndex("a", mint.host, NotePurpose.Change.value, 10)
        val result = wallet.recheckAllKeys(mint.host, NotePurpose.Change.value, gap = 5)
        assertEquals(KeyScanResult(from = 0, until = 15, checked = 15, outstanding = 0, imported = 0, stoppedEarly = null, indexAfter = 10), result)
        assertEquals(15, wallet.derivedKeys(mint.host, NotePurpose.Change.value, gap = 5).size, "the list follows index + gap")
    }

    @Test
    fun `find index honors the gap limit`() = withMint {
        credit(NotePurpose.Wallet, 0, 1_000)
        credit(NotePurpose.Wallet, 8, 1_000)
        assertEquals(DiscoveryResult(next = 1, checked = 6, imported = 1, stoppedEarly = null), wallet.findKeyIndex(mint.host, 0, gap = 5))
        assertEquals(9, wallet.findKeyIndex(mint.host, 0, gap = 10).next)
    }

    @Test
    fun `the key list shows public keys, local notes and probe results, never spends`() = withMint {
        credit(NotePurpose.Wallet, 1, 3_000)
        store.upsertNotes(listOf(Note(branch.notePubkey(NotePurpose.Wallet, 0).toHex(), "a", mint.host, NoteKind.KeyPath, 0, 0, 1, null, NoteState.Spent, 0)))
        store.setIndex("a", mint.host, 0, 2)
        wallet.recheckKey(mint.host, 0, 1)

        val keys = wallet.derivedKeys(mint.host, NotePurpose.Wallet.value)
        assertEquals(2 + AddressScanner.GAP_LIMIT, keys.size, "used range plus one scan window")
        assertEquals(NoteState.Spent, keys[0].noteState)
        assertNull(keys[0].probe)
        assertIs<KeyProbe.Outstanding>(keys[1].probe)
        assertEquals(NoteState.Outstanding, keys[1].noteState)
        assertEquals(listOf(KeyStatus.Spent, KeyStatus.Funded(3_000), KeyStatus.Next, KeyStatus.Free), keys.take(4).map { it.status })
        assertEquals(Cp1.encode(branch.notePubkey(NotePurpose.Wallet, 1)), keys[1].cp1)
        assertTrue(keys.none { it.cp1.startsWith("ck1") || it.q.startsWith("ck1") })
        assertEquals(listOf(mint.host), wallet.keyHosts())
    }
}
