@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

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
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.testkit.MockMint

class KeyInspectorTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val mine: AccountKeys)

    private fun withMint(test: suspend Fixture.() -> Unit) =
        runBlocking<Unit> {
            assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
            MockMint.start().use { mint ->
                val store = InMemoryWalletStore()
                val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient())
                store.addAccount("a", "Main", Profile("alice", mint.host))
                assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
                Fixture(mint, store, wallet, testKeys(words, mint.host)).test()
            }
        }

    private fun Fixture.credit(set: KeySet, index: Long, msat: Long) = mint.credit(Cp1.encode(mine.pubkey(set, index)), msat)

    @Test
    fun `scanning further finds an address payment beyond the gap limit`() = withMint {
        credit(KeySet.share(0), 30, 4_000)
        assertEquals(0, wallet.refresh().newNotes, "a normal refresh stops after 20 unused keys")
        assertEquals(0, wallet.keyIndex(mint.host, KeySet.share(0)))

        // Scan ahead with a wider gap limit than the refresh uses reaches it.
        val result = wallet.scanKeysAhead(mint.host, KeySet.share(0), gap = 40)
        assertEquals(
            KeyScanResult(from = 0, until = 71, checked = 71, outstanding = 1, imported = 1, stoppedEarly = null, indexAfter = 31),
            result,
            "it runs on until 40 unused keys follow the note at #30",
        )
        assertEquals(4_000, wallet.balanceMsat.first())
        assertEquals(31, wallet.keyIndex(mint.host, KeySet.share(0)))
        assertIs<KeyProbe.Outstanding>(wallet.keyProbes.first()[KeySet.share(0).key("a", mint.host, 30)])
        assertIs<KeyProbe.Unused>(wallet.keyProbes.first()[KeySet.share(0).key("a", mint.host, 29)])
    }

    @Test
    fun `an unknown wallet note is recovered and the counter moves past it`() = withMint {
        credit(KeySet.Wallet, 3, 2_500)
        val result = wallet.scanKeysAhead(mint.host, KeySet.Wallet, gap = 6)
        assertEquals(1, result.imported)
        assertEquals(2_500, wallet.balanceMsat.first())
        assertEquals(4, wallet.keyIndex(mint.host, KeySet.Wallet), "index 3 is used, so the next new note gets 4")
    }

    @Test
    fun `rechecking a key picks up a spend made elsewhere`() = withMint {
        credit(KeySet.Wallet, 0, 1_000)
        wallet.scanKeysAhead(mint.host, KeySet.Wallet, gap = 1)
        assertEquals(1_000, wallet.balanceMsat.first())

        // Spend the note behind the wallet's back (as another device with the same phrase would).
        val client = LnurlcashClient()
        val withdrawUrl = assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host)).mint.withdrawUrl
        val spend = mine.spend(KeySet.Wallet, 0, mint.host)
        val info = client.lookup(withdrawUrl, spend.q)
        assertIs<MutationOutcome.Confirmed>(
            client.mutate(MutationType.Rotate, Requests.rotate(info.callback, spend.k1, mine.pubkey(KeySet.Wallet, 7)))
        )

        assertIs<KeyProbe.Spent>(wallet.recheckKey(mint.host, KeySet.Wallet, 0))
        assertEquals(0L, wallet.balanceMsat.first())
        assertEquals(NoteState.Spent, store.allNotes().single().state)
    }

    @Test
    fun `lookup answers survive a restart, and a lookup without an answer does not erase them`() = withMint {
        // Key 2 is spent at the mint, but this wallet never held it (as after a restore).
        credit(KeySet.Wallet, 2, 1_000)
        val client = LnurlcashClient()
        val withdrawUrl = assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host)).mint.withdrawUrl
        val spend = mine.spend(KeySet.Wallet, 2, mint.host)
        val info = client.lookup(withdrawUrl, spend.q)
        assertIs<MutationOutcome.Confirmed>(
            client.mutate(MutationType.Rotate, Requests.rotate(info.callback, spend.k1, mine.pubkey(KeySet.Change, 50)))
        )
        store.setIndexOf("a", mint.host, KeySet.Wallet, 4)
        assertIs<KeyProbe.Unused>(wallet.recheckKey(mint.host, KeySet.Wallet, 1))
        assertIs<KeyProbe.Spent>(wallet.recheckKey(mint.host, KeySet.Wallet, 2))
        assertTrue(store.allNotes().none { it.index == 2L && it.purpose == 0 }, "no note is made for a key the wallet never held")

        // A new process: same saved state, nothing in memory.
        val restarted = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient())
        val keys = restarted.derivedKeys(mint.host, KeySet.Wallet)
        assertEquals(
            listOf(KeyStatus.HandedOut, KeyStatus.Skipped, KeyStatus.Spent, KeyStatus.HandedOut, KeyStatus.Next),
            keys.take(5).map { it.status },
        )
        assertIs<KeyProbe.Unused>(keys[1].probe)

        // The mint stops answering: the key keeps its last answer, and the failure is shown beside it.
        restarted.recordProbe(KeySet.Wallet.key("a", mint.host, 1), KeyProbe.Failed("offline", Long.MAX_VALUE))
        val after = restarted.derivedKeys(mint.host, KeySet.Wallet)[1]
        assertEquals(KeyStatus.Skipped, after.status)
        assertIs<KeyProbe.Unused>(after.probe)
        assertEquals("offline", after.failure?.reason)
    }

    @Test
    fun `scan ahead always starts at the index`() = withMint {
        wallet.recheckKey(mint.host, KeySet.share(0), 40) // a lookup far ahead (unused)
        val result = wallet.scanKeysAhead(mint.host, KeySet.share(0), gap = 5)
        assertEquals(0L until 5L, result.from until result.until)
        assertEquals(5, result.checked)
    }

    @Test
    fun `scan ahead moves the index past a used key it finds and keeps going past it`() = withMint {
        store.setShareScanFrom("a", mint.host, 0, 20)
        credit(KeySet.share(0), 25, 1_000)
        credit(KeySet.share(0), 44, 1_000) // beyond the first window (#20–#39), within 20 of #25
        val result = wallet.scanKeysAhead(mint.host, KeySet.share(0))
        assertEquals(20L until 65L, result.from until result.until, "20 unused keys after the last find (#44)")
        assertEquals(2, result.outstanding)
        assertEquals(45, result.indexAfter)
        assertEquals(45, wallet.keyIndex(mint.host, KeySet.share(0)))
        assertEquals(2_000, wallet.balanceMsat.first())
    }

    @Test
    fun `recheck all runs on past the last used key too`() = withMint {
        credit(KeySet.Wallet, 3, 1_000)
        credit(KeySet.Wallet, 8, 1_000) // past #0–#4, but within 5 of #3
        credit(KeySet.Wallet, 20, 1_000) // more than 5 unused keys after #8: not reached
        val result = wallet.recheckAllKeys(mint.host, KeySet.Wallet, gap = 5)
        assertEquals(0L until 14L, result.from until result.until)
        assertEquals(2, result.outstanding)
        assertEquals(9, result.indexAfter)
    }

    @Test
    fun `recheck all covers every handed out key plus the gap and never lowers the index`() = withMint {
        store.setIndexOf("a", mint.host, KeySet.Change, 10)
        val result = wallet.recheckAllKeys(mint.host, KeySet.Change, gap = 5)
        assertEquals(
            KeyScanResult(from = 0, until = 15, checked = 15, outstanding = 0, imported = 0, stoppedEarly = null, indexAfter = 10),
            result,
        )
        assertEquals(15, wallet.derivedKeys(mint.host, KeySet.Change, gap = 5).size, "the list follows index + gap")
    }

    @Test
    fun `find index honors the gap limit`() = withMint {
        credit(KeySet.Wallet, 0, 1_000)
        credit(KeySet.Wallet, 8, 1_000)
        assertEquals(
            DiscoveryResult(next = 1, checked = 6, imported = 1, stoppedEarly = null),
            wallet.findKeyIndex(mint.host, KeySet.Wallet, gap = 5),
        )
        assertEquals(9, wallet.findKeyIndex(mint.host, KeySet.Wallet, gap = 10).next)
    }

    @Test
    fun `the key list shows public keys, local notes and probe results, never spends`() = withMint {
        credit(KeySet.Wallet, 1, 3_000)
        store.upsertNotes(
            listOf(
                Note(
                    mine.pubkey(KeySet.Wallet, 0).toHex(),
                    "a",
                    mint.host,
                    NoteKind.KeyPath,
                    0,
                    0,
                    1,
                    null,
                    NoteState.Spent,
                    0,
                )
            )
        )
        store.setIndexOf("a", mint.host, KeySet.Wallet, 2)
        wallet.recheckKey(mint.host, KeySet.Wallet, 1)

        val keys = wallet.derivedKeys(mint.host, KeySet.Wallet)
        assertEquals(2 + ScanLimits().keyGap, keys.size, "used range plus one scan window")
        assertEquals(NoteState.Spent, keys[0].noteState)
        assertNull(keys[0].probe)
        assertIs<KeyProbe.Outstanding>(keys[1].probe)
        assertEquals(NoteState.Outstanding, keys[1].noteState)
        assertEquals(listOf(KeyStatus.Spent, KeyStatus.Funded(3_000), KeyStatus.Next, KeyStatus.Free), keys.take(4).map { it.status })
        assertEquals(Cp1.encode(mine.pubkey(KeySet.Wallet, 1)), keys[1].cp1)
        assertTrue(keys.none { it.cp1.startsWith("ck1") || it.q.startsWith("ck1") })
        assertEquals(listOf(mint.host), wallet.keyHosts())
    }
}
