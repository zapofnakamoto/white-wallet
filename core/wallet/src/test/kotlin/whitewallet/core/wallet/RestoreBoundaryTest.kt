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
import whitewallet.core.model.KeyCounter
import whitewallet.core.wallet.legacy.LegacyAddress
import whitewallet.core.wallet.legacy.LegacyChange
import whitewallet.core.wallet.legacy.LegacyWallet
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain
import whitewallet.testkit.MockMint

/**
 * Restore at the edges of the gap limit: which notes a phrase alone finds, and where the indexes end up. Every case runs against the mock
 * mint ([MockMintRestoreBoundaryTest]) and the regtest stack ([RegtestRestoreBoundaryTest]); each uses a fresh phrase, so notes left on a
 * mint by earlier runs never count.
 */
abstract class RestoreBoundaryTest {
    protected abstract val host: String

    /**
     * Puts a note of [sats] on key [index] of [set] of account [account] of [words]; returns what the note is worth (a mint may take a
     * fee).
     */
    protected abstract suspend fun credit(words: List<String>, set: KeySet, index: Long, sats: Long, account: Long = 0): Long

    /** A client to [host]; [intercept], if given, sees every request. */
    protected abstract fun client(intercept: Interceptor? = null): LnurlcashClient

    private val gap = WalletSettings().scanStopAfterUnusedKeys.toLong()

    @Test
    fun `a phrase without notes restores an empty account with every index at 0`() =
        runBlocking<Unit> {
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(phrase()))
            assertEquals(0, restored.notes)
            assertEquals(0, restored.balanceMsat)
            assertEquals(restored.accountId, phone.store.currentAccountId())
            assertEquals(listOf(0L, 0L, 0L), phone.indexes(restored.accountId))
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
            val found = phone.wallet.findKeyIndex(host, KeySet.Wallet, gap = WalletSettings().scanStopAfterUnusedKeys + 1)
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
            val msat = credit(words, KeySet.Change, gap - 1, SATS) + credit(words, KeySet.share(0), gap - 1, SATS)
            val phone = Fresh()
            val restored = assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(2, restored.notes)
            assertEquals(msat, restored.balanceMsat)
            assertEquals(listOf(0L, gap, gap), phone.indexes(restored.accountId))
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
            assertEquals(listOf(gap, gap), listOf(KeySet.LegacyChange, KeySet.LegacyAddress).map { phone.index(restored.accountId, it) })
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
            assertEquals(listOf(1L, 0L, 0L), phone.indexes(restored.accountId))
            assertEquals(1L, phone.index(restored.accountId, KeySet.LegacyWallet))
        }

    @Test
    fun `a connection lost in the middle of the lookups leaves nothing behind`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, KeySet.Wallet, 0, SATS)
            credit(words, KeySet.Wallet, 1, SATS)
            val dropped = Cp1.encode(keys(words).pubkey(KeySet.Wallet, 3))
            val phone =
                Fresh(
                    Interceptor { chain ->
                        if (chain.request().url.queryParameter("p") == dropped) throw IOException("connection dropped")
                        chain.proceed(chain.request())
                    }
                )
            assertIs<RestoreResult.NetworkError>(phone.restore(words))
            assertTrue(phone.store.accounts.first().isEmpty())
            assertTrue(phone.seeds.accountIds().isEmpty())
            assertTrue(phone.store.allNotes().isEmpty(), "the notes of keys 0 and 1 go with the account")
        }

    @Test
    fun `every account of a phrase is found, with what each of its chains and shares holds up to their gap limits`() =
        runBlocking<Unit> {
            val words = phrase()
            val first =
                credit(words, KeySet.Wallet, gap - 1, SATS) +
                    credit(words, KeySet.share(0), gap - 1, SATS) +
                    credit(words, KeySet.share(1), 0, SATS)
            val second =
                credit(words, KeySet.Change, 0, SATS, account = 1) +
                    credit(words, KeySet.share(0), 3, SATS, account = 1) +
                    credit(words, KeySet.share(2), gap - 1, SATS, account = 1)
            // Used through an address alone: still an account.
            val third = credit(words, KeySet.share(1), gap - 1, SATS, account = 2)

            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(mapOf(0L to first, 1L to second, 2L to third), phone.balances())
            assertEquals(1, phone.store.accounts.first().map { it.account.walletId }.distinct().size, "one wallet")

            val ids = phone.ids()
            assertEquals(gap, phone.index(ids.getValue(0), KeySet.Wallet))
            assertEquals(gap, phone.index(ids.getValue(0), KeySet.share(0)))
            assertEquals(1, phone.index(ids.getValue(0), KeySet.share(1)))
            assertEquals(1, phone.index(ids.getValue(1), KeySet.Change))
            assertEquals(4, phone.index(ids.getValue(1), KeySet.share(0)))
            assertEquals(0, phone.index(ids.getValue(1), KeySet.share(1)))
            assertEquals(gap, phone.index(ids.getValue(1), KeySet.share(2)))
            assertEquals(gap, phone.index(ids.getValue(2), KeySet.share(1)))
            assertEquals(
                mapOf(0L to 2L, 1L to 3L, 2L to 2L),
                ids.mapValues { phone.sharesHandedOut(it.value) },
                "shares are not handed out twice",
            )
        }

    @Test
    fun `each share of each account counts its own gap, so a note one key past it is missed there and nowhere else`() =
        runBlocking<Unit> {
            val words = phrase()
            val found = credit(words, KeySet.share(1), gap - 1, SATS, account = 1)
            credit(words, KeySet.share(0), gap, SATS, account = 1)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(mapOf(0L to 0L, 1L to found), phone.balances())
        }

    @Test
    fun `a share after a gap of addresses that were never paid is found, up to the share gap`() =
        runBlocking<Unit> {
            val last = WalletSettings().restoreStopAfterUnusedShares - 1L
            val words = phrase()
            val msat = credit(words, KeySet.share(last), 0, SATS, account = 1)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(mapOf(0L to 0L, 1L to msat), phone.balances())

            val past = phrase()
            credit(past, KeySet.share(last + 1), 0, SATS, account = 1)
            val other = Fresh()
            assertIs<RestoreResult.Restored>(other.restore(past))
            assertEquals(mapOf(0L to 0L), other.balances(), "and with nothing else used, no second account either")
        }

    @Test
    fun `the wallet settings are the ones a restore keeps to`() =
        runBlocking<Unit> {
            val words = phrase()
            val past = credit(words, KeySet.share(2), 0, SATS)
            val account = credit(words, KeySet.Wallet, 2, SATS, account = 2)

            val narrow = Fresh(settings = WalletSettings(scanStopAfterUnusedKeys = 2, restoreStopAfterUnusedShares = 2))
            assertIs<RestoreResult.Restored>(narrow.restore(words))
            assertEquals(mapOf(0L to 0L), narrow.balances(), "both a share and a key past their gaps, and so no account past the first")

            val wide =
                Fresh(
                    settings =
                        WalletSettings(scanStopAfterUnusedKeys = 3, restoreStopAfterUnusedShares = 3, restoreStopAfterUnusedAccounts = 2)
                )
            assertIs<RestoreResult.Restored>(wide.restore(words))
            assertEquals(mapOf(0L to past, 1L to 0L, 2L to account), wide.balances(), "the unused account stays, for the one after it")

            val capped =
                Fresh(
                    settings =
                        WalletSettings(
                            scanStopAfterUnusedKeys = 3,
                            restoreStopAfterUnusedShares = 3,
                            restoreStopAfterUnusedAccounts = 2,
                            restoreMaxAccountsChecked = 2,
                        )
                )
            assertIs<RestoreResult.Restored>(capped.restore(words))
            assertEquals(mapOf(0L to past), capped.balances(), "the look ended at the cap, with only an unused account")
        }

    @Test
    fun `accounts are looked for up to the first unused one, which ends the restore`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, KeySet.Wallet, 0, SATS)
            credit(words, KeySet.Wallet, 0, SATS, account = 2)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(mapOf(0L to msat), phone.balances())
        }

    @Test
    fun `an account whose notes are all spent is still found, and so are the ones after it`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, KeySet.Wallet, 0, SATS, account = 1)
            spend(words, KeySet.Wallet, 0, account = 1)
            val msat = credit(words, KeySet.Wallet, 0, SATS, account = 2)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(mapOf(0L to 0L, 1L to 0L, 2L to msat), phone.balances())
        }

    @Test
    fun `an address paid after a restore is found on its share, in its own account, before the phone knows the name`() =
        runBlocking<Unit> {
            val words = phrase()
            val first = credit(words, KeySet.share(1), 0, SATS)
            val second = credit(words, KeySet.share(0), 0, SATS, account = 1)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))

            val again = credit(words, KeySet.share(1), 1, SATS)
            val secondAgain = credit(words, KeySet.share(0), 1, SATS, account = 1)
            phone.wallet.refresh()
            assertEquals(mapOf(0L to first + again, 1L to second + secondAgain), phone.balances())
        }

    /** A phone with nothing on it yet. */
    private inner class Fresh(intercept: Interceptor? = null, settings: WalletSettings = WalletSettings()) {
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore()
        val wallet = Wallet(store, seeds, client(intercept), policies = NotePolicies.InMemory(ManualStrategy.ID), settings = settings)

        suspend fun restore(words: List<String>) = wallet.restore(words.joinToString(" "), host, "Restored")

        suspend fun index(accountId: String, set: KeySet): Long = store.indexOf(accountId, host, set)

        /** Each account's id, by its index in the wallet. */
        suspend fun ids(): Map<Long, String> = store.accounts.first().associate { it.account.index to it.account.id }

        /** What each account holds, by its index in the wallet. */
        suspend fun balances(): Map<Long, Long> = store.accounts.first().associate { it.account.index to it.balanceMsat }

        suspend fun sharesHandedOut(accountId: String): Long? = store.keyIndexOrNull(accountId, spendDomain(host), KeyCounter.Shares)

        /** The wallet and change indexes, then the legacy wallet, change and address ones. */
        suspend fun indexes(accountId: String) = listOf(KeySet.Wallet, KeySet.Change, KeySet.share(0)).map { index(accountId, it) }
    }

    /** Spends the note on that key at the mint, by rotating it to a key of another phrase. */
    private suspend fun spend(words: List<String>, set: KeySet, index: Long, account: Long = 0) {
        val client = client()
        val keys = keys(words, account)
        val info = client.lookup(client.servicePayRequest(host).withdrawUrl, keys.pubkey(set, index))
        val spend = keys.spend(set, index, host)
        val elsewhere = keys(phrase()).pubkey(KeySet.Wallet, 0)
        assertIs<MutationOutcome.Confirmed>(client.mutate(MutationType.Rotate, Requests.rotate(info.callback, spend.k1, elsewhere)))
    }

    internal fun keys(words: List<String>, account: Long = 0): AccountKeys = testKeys(words, host, account)

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

    override suspend fun credit(words: List<String>, set: KeySet, index: Long, sats: Long, account: Long): Long {
        mint!!.credit(Cp1.encode(keys(words, account).pubkey(set, index)), sats * 1000)
        return sats * 1000
    }

    override fun client(intercept: Interceptor?): LnurlcashClient =
        if (intercept == null) LnurlcashClient()
        else LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(intercept).build())
}
