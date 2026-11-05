@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import java.io.IOException
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.testkit.MockMint

/**
 * Restore at the edges of the gap limit: which notes a phrase alone finds, and where the indexes end up. Every case runs against the mock
 * mint ([MockMintRestoreBoundaryTest]) and the regtest stack ([RegtestRestoreBoundaryTest]); each uses a fresh phrase, so notes left on a
 * mint by earlier runs never count.
 */
abstract class RestoreBoundaryTest {
    protected abstract val host: String

    /** Puts a note of [sats] on key [index] of [set] of [words]' keys; returns what the note is worth (a mint may take a fee). */
    protected abstract suspend fun credit(words: List<String>, set: KeySet, index: Long, sats: Long): Long

    /** A client to [host]; [intercept], if given, sees every request. */
    protected abstract fun client(intercept: Interceptor? = null): LnurlcashClient

    private val gap = AddressScanner.GAP_LIMIT.toLong()

    @Test
    fun `a phrase without notes restores an empty account with every index at 0`() =
        runBlocking<Unit> {
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(phrase()))
            assertEquals(0, restored.notes)
            assertEquals(0, restored.balanceMsat)
            assertEquals(restored.accountId, phone.store.currentAccountId())
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), phone.indexes(restored.accountId))
        }

    @Test
    fun `a note on the last key the gap limit reaches is found`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, KeySet.Wallet, gap - 1, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(1, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(gap, phone.index(restored.accountId, KeySet.Wallet))
        }

    @Test
    fun `a note one key past the gap limit is missed, and a deeper look finds it`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, KeySet.Wallet, gap, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(0, restored.notes)
            assertEquals(0, phone.index(restored.accountId, KeySet.Wallet))

            // The money is not lost: a lookup one key further reaches it.
            val found = phone.wallet.findKeyIndex(host, KeySet.Wallet, gap = AddressScanner.GAP_LIMIT + 1)
            assertEquals(gap + 1, found.next)
            assertEquals(1, found.imported)
            assertEquals(msat, phone.wallet.balanceMsat.first())
        }

    @Test
    fun `notes with one key less than the gap limit between them are both found`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, KeySet.Wallet, 0, SATS) + credit(words, KeySet.Wallet, gap, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(2, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(gap + 1, phone.index(restored.accountId, KeySet.Wallet))
        }

    @Test
    fun `notes with the gap limit between them stop the restore after the first`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, KeySet.Wallet, 0, SATS)
            credit(words, KeySet.Wallet, gap + 1, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(1, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(1, phone.index(restored.accountId, KeySet.Wallet))
        }

    @Test
    fun `each note found starts the gap over, so a chain of them is found`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = listOf(0L, gap, 2 * gap).sumOf { credit(words, KeySet.Wallet, it, SATS) }
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(3, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(2 * gap + 1, phone.index(restored.accountId, KeySet.Wallet))
        }

    @Test
    fun `a spent key counts as used, so the keys after it are looked up too`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, KeySet.Wallet, 0, SATS)
            spend(words, KeySet.Wallet, 0)
            val msat = credit(words, KeySet.Wallet, gap, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(1, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(gap + 1, phone.index(restored.accountId, KeySet.Wallet))
        }

    @Test
    fun `each chain counts its own gap`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, KeySet.Change, gap - 1, SATS) + credit(words, KeySet.LegacyAddress, gap - 1, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(2, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(listOf(0L, gap, 0L, 0L, gap), phone.indexes(restored.accountId))
        }

    @Test
    fun `legacy 2f17578 - a note on the last key the gap limit reaches is found`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, KeySet.LegacyWallet, gap - 1, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(1, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(gap, phone.index(restored.accountId, KeySet.LegacyWallet))
            assertEquals(0, phone.index(restored.accountId, KeySet.Wallet), "new keys come from the new chain, from its start")
        }

    @Test
    fun `legacy 2f17578 - each purpose counts its own gap`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, KeySet.LegacyChange, gap - 1, SATS) + credit(words, KeySet.LegacyAddress, gap - 1, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(2, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(listOf(0L, 0L, 0L, gap, gap), phone.indexes(restored.accountId))
        }

    @Test
    fun `legacy 2f17578 - notes on both schemes are found together`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, KeySet.Wallet, 0, SATS) + credit(words, KeySet.LegacyWallet, 0, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(2, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(listOf(1L, 0L, 1L, 0L, 0L), phone.indexes(restored.accountId))
        }

    @Test
    fun `a connection lost in the middle of the lookups leaves nothing behind`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, KeySet.Wallet, 0, SATS)
            credit(words, KeySet.Wallet, 1, SATS)
            val dropped = Cp1.encode(keys(words).pubkey(KeySet.Wallet, 3))
            val phone = Fresh { chain ->
                if (chain.request().url.queryParameter("p") == dropped) throw IOException("connection dropped")
                chain.proceed(chain.request())
            }
            assertIs<RestoreResult.NetworkError>(phone.restore(words))
            assertTrue(phone.store.accounts.first().isEmpty())
            assertTrue(phone.seeds.accountIds().isEmpty())
            assertTrue(phone.store.allNotes().isEmpty(), "the notes of keys 0 and 1 go with the account")
        }

    /** A phone with nothing on it yet. */
    private inner class Fresh(intercept: Interceptor? = null) {
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore()
        val wallet = Wallet(store, seeds, client(intercept), policies = NotePolicies.InMemory(ManualStrategy.ID))

        suspend fun restore(words: List<String>) = wallet.restore(words.joinToString(" "), host, "Restored")

        suspend fun index(accountId: String, set: KeySet): Long = store.indexOf(accountId, host, set)

        /** The wallet and change indexes, then the legacy wallet, change and address ones. */
        suspend fun indexes(accountId: String) = (listOf(KeySet.Wallet, KeySet.Change) + KeySet.LEGACY).map { index(accountId, it) }
    }

    /** Spends the note on that key at the mint, by rotating it to a key of another phrase. */
    private suspend fun spend(words: List<String>, set: KeySet, index: Long) {
        val client = client()
        val keys = keys(words)
        val info = client.lookup(client.servicePayRequest(host).withdrawUrl, keys.pubkey(set, index))
        val spend = keys.spend(set, index, host)
        val elsewhere = keys(phrase()).pubkey(KeySet.Wallet, 0)
        assertIs<MutationOutcome.Confirmed>(client.mutate(MutationType.Rotate, Requests.rotate(info.callback, spend.k1, elsewhere)))
    }

    internal fun keys(words: List<String>): AccountKeys = testKeys(words, host)

    private fun phrase(): List<String> = MnemonicCode.toMnemonics(ByteArray(16).also(random::nextBytes))

    private companion object {
        val random = SecureRandom()
        const val SATS = 1_000L
    }
}

class MockMintRestoreBoundaryTest : RestoreBoundaryTest() {
    private var mint: MockMint? = null

    override val host: String
        get() = mint!!.host

    @BeforeEach
    fun start() {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        mint = MockMint.start()
    }

    @AfterEach fun stop() = mint?.close() ?: Unit

    override suspend fun credit(words: List<String>, set: KeySet, index: Long, sats: Long): Long {
        mint!!.credit(Cp1.encode(keys(words).pubkey(set, index)), sats * 1000)
        return sats * 1000
    }

    override fun client(intercept: Interceptor?): LnurlcashClient =
        if (intercept == null) LnurlcashClient()
        else LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(intercept).build())
}
