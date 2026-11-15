package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import java.io.IOException
import java.security.SecureRandom
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import whitewallet.core.model.MintScan
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/**
 * A restore stops at the first account a mint shows unused, yet an account may be used on another mint alone: once a scan finds accounts on
 * one mint, the wallet's other mints are looked through again from where their scan stopped. Every case runs against the mock mint
 * ([MockMintAccountsAcrossMintsTest]) and the regtest stack ([RegtestAccountsAcrossMintsTest]), each with a fresh phrase.
 */
abstract class AccountsAcrossMintsTest {
    /** Three mints. */
    protected abstract val hosts: List<String>

    /** Puts a note of [sats] on wallet key [index] of account [account] of [words] at [host]; returns what the note is worth. */
    protected abstract suspend fun credit(words: List<String>, host: String, account: Long, sats: Long = SATS, index: Long = 0): Long

    private val a
        get() = hosts[0]

    private val b
        get() = hosts[1]

    private val c
        get() = hosts[2]

    /** Every key lookup (`p=`) sent, with the mint it went to. */
    private val lookups = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    /** Mints whose lookups fail, as if they didn't answer. */
    private val down = Collections.synchronizedSet(mutableSetOf<String>())

    private fun client() =
        LnurlcashClient(
            http =
                LnurlcashClient.defaultHttpClient()
                    .newBuilder()
                    .addInterceptor(
                        Interceptor { chain ->
                            val url = chain.request().url
                            val host = "${url.host}:${url.port}"
                            url.queryParameter("p")?.let { p ->
                                if (host in down) throw IOException("connection dropped")
                                lookups += host to p
                            }
                            chain.proceed(chain.request())
                        }
                    )
                    .build()
        )

    private inner class Phone(settings: WalletSettings = WalletSettings()) {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), client(), policies = NotePolicies.InMemory(ManualStrategy.ID), settings = settings)

        suspend fun restore(words: List<String>, host: String) =
            assertIs<RestoreResult.Restored>(wallet.restore(words.joinToString(" "), host, "Restored"))

        /** What each account of the active wallet holds, by its index. */
        suspend fun balances(): Map<Long, Long> = wallet.activeWalletAccounts.first().associate { it.account.index to it.balanceMsat }

        suspend fun scan(host: String): MintScan = checkNotNull(store.mint(wallet.walletOf(wallet.activeId()), host)).scan
    }

    @Test
    fun `an account used on another mint alone doesn't end the look at the first, adding that mint finds it, and those after it on the first`() =
        runBlocking<Unit> {
            val words = phrase()
            val first = credit(words, a, account = 0)
            val second = credit(words, b, account = 1)
            val third = credit(words, a, account = 2)
            val phone = Phone()
            phone.restore(words, a)
            assertEquals(mapOf(0L to first), phone.balances(), "account 1 is unused on the first mint, so the restore stops there")

            assertIs<ConnectResult.Connected>(phone.wallet.addMint(b))
            assertEquals(mapOf(0L to first, 1L to second, 2L to third), phone.balances())
            assertEquals(listOf(MintScan.Finished, MintScan.Finished), listOf(a, b).map { phone.scan(it) })
            val held = phone.store.allNotes().associate { note -> checkNotNull(phone.store.account(note.accountId)).index to note.host }
            assertEquals(mapOf(0L to a, 1L to b, 2L to a), held, "each note in its own account, at its own mint")
        }

    @Test
    fun `the first mint is looked through again only from where its scan stopped`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, a, account = 0)
            credit(words, b, account = 1)
            credit(words, a, account = 2)
            val phone = Phone()
            phone.restore(words, a)
            lookups.clear()
            phone.wallet.addMint(b)
            val firstAccount = keysOf(words, a, account = 0)
            assertTrue(lookups.none { (host, p) -> host == a && p in firstAccount }, "account 0 was looked through there already")
            assertTrue(lookups.any { (host, p) -> host == a && p in keysOf(words, a, account = 2) })
        }

    @Test
    fun `a scan of every mint goes back and forth between them until none is behind`() =
        runBlocking<Unit> {
            val words = phrase()
            val found =
                listOf(
                    credit(words, a, account = 0),
                    credit(words, b, account = 1),
                    credit(words, a, account = 2),
                    credit(words, b, account = 3),
                )
            val phone = Phone()
            phone.restore(words, a)
            assertIs<ConnectResult.Connected>(phone.wallet.connectMint(b), "added without its scan")
            val result = phone.wallet.scan()
            assertEquals(emptyList<String>(), result.stoppedAt)
            assertEquals(found.withIndex().associate { (i, msat) -> i.toLong() to msat }, phone.balances())
            assertEquals(found.drop(1).sum(), result.foundMsat)
        }

    @Test
    fun `it ends, also with three mints that each find a further account, and never past the accounts a scan may look at`() =
        runBlocking<Unit> {
            val words = phrase()
            val found =
                listOf(
                    credit(words, a, account = 0),
                    credit(words, b, account = 1),
                    credit(words, c, account = 2),
                    credit(words, b, account = 3),
                    credit(words, c, account = 4),
                )
            for ((settings, expected) in listOf(WalletSettings() to 5, WalletSettings(scanMaxAccountsChecked = 3) to 3)) {
                val phone = Phone(settings)
                phone.restore(words, a)
                phone.wallet.connectMint(b)
                phone.wallet.connectMint(c)
                assertEquals(emptyList<String>(), phone.wallet.scan().stoppedAt)
                assertEquals(found.take(expected).withIndex().associate { (i, msat) -> i.toLong() to msat }, phone.balances())
            }
        }

    @Test
    fun `a mint that doesn't answer when looked at again for new accounts says its scan isn't finished, and a later scan finds them`() =
        runBlocking<Unit> {
            val words = phrase()
            val first = credit(words, a, account = 0)
            val second = credit(words, b, account = 1)
            val third = credit(words, a, account = 2)
            val phone = Phone()
            phone.restore(words, a)
            down += a
            assertIs<ConnectResult.Connected>(phone.wallet.addMint(b), "the mint added was looked through to its end")
            assertEquals(mapOf(0L to first, 1L to second), phone.balances())
            assertEquals(MintScan.Unfinished, phone.scan(a))
            assertEquals(listOf(a), phone.wallet.unscannedMints.first().map { it.host })

            down -= a
            assertEquals(emptyList<String>(), phone.wallet.scan().stoppedAt)
            assertEquals(mapOf(0L to first, 1L to second, 2L to third), phone.balances())
            assertEquals(MintScan.Finished, phone.scan(a))
        }

    @Test
    fun `with one mint, a scan looks each key up once, as before`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, a, account = 0)
            credit(words, a, account = 1)
            val phone = Phone()
            phone.restore(words, a)
            lookups.clear()
            phone.wallet.scan()
            assertTrue(lookups.isNotEmpty())
            assertEquals(lookups.size, lookups.toSet().size, "no key twice")
        }

    @Test
    fun `another wallet's mints and keys are never touched`() =
        runBlocking<Unit> {
            val words = phrase()
            val others = phrase()
            credit(words, a, account = 0)
            credit(words, b, account = 1)
            credit(words, a, account = 2)
            credit(others, b, account = 0)
            val phone = Phone()
            phone.restore(others, b)
            val otherWallet = phone.wallet.walletOf(phone.wallet.activeId())
            val before = phone.store.mint(otherWallet, b)
            phone.restore(words, a)
            lookups.clear()
            phone.wallet.addMint(b)
            phone.wallet.scan()
            assertEquals(before, phone.store.mint(otherWallet, b))
            val otherKeys = keysOf(others, a, account = 0) + keysOf(others, a, account = 1)
            assertTrue(lookups.none { (_, p) -> p in otherKeys })
        }

    /** The first keys of each chain and share of [account] of [words] at [host], as lookups name them. */
    private fun keysOf(words: List<String>, host: String, account: Long): Set<String> {
        val keys = testKeys(words, host, account)
        val sets = listOf(KeySet.Wallet, KeySet.Change) + (0L until 25).map { KeySet.Share(it) }
        return sets.flatMap { set -> (0L until 25).map { Cp1.encode(keys.pubkey(set, it)) } }.toSet()
    }

    private fun phrase(): List<String> = MnemonicCode.toMnemonics(ByteArray(16).also(random::nextBytes))

    protected companion object {
        val random = SecureRandom()
        const val SATS = 1_000L
    }
}

class MockMintAccountsAcrossMintsTest : AccountsAcrossMintsTest() {
    private val mints = mutableListOf<MockMint>()

    override val hosts: List<String>
        get() = mints.map { it.host }

    @BeforeEach
    fun start() {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        repeat(3) { mints += MockMint.start() }
    }

    @AfterEach fun stop() = mints.forEach { it.close() }

    override suspend fun credit(words: List<String>, host: String, account: Long, sats: Long, index: Long): Long {
        mints.single { it.host == host }.credit(Cp1.encode(testKeys(words, host, account).pubkey(KeySet.Wallet, index)), sats * 1000)
        return sats * 1000
    }
}

// The third mint is one of the three: one test at a time on it, as a key rotation restarts it.
@ExtendWith(ThirdMintLock::class)
class RegtestAccountsAcrossMintsTest : AccountsAcrossMintsTest() {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    override val hosts = listOf(Regtest.HOST, Regtest.HOST2, Regtest.HOST3)

    /** Asks the mint for an invoice minting to the key (LUD-25 minting), pays it, and waits until the note is there. */
    override suspend fun credit(words: List<String>, host: String, account: Long, sats: Long, index: Long): Long {
        val client = LnurlcashClient()
        val pay = client.servicePayRequest(host)
        val key = testKeys(words, host, account).pubkey(KeySet.Wallet, index)
        val asker = Wallet(InMemoryWalletStore(), InMemorySeedStore(), client)
        Regtest.pay(assertIs<MintInvoice.Ok>(MintInvoice.fetch(asker, pay, sats * 1000, key)).invoice, sats)
        repeat(30) {
            val note = runCatching { client.lookup(pay.withdrawUrl, key) }.getOrNull()
            if (note != null) return note.maxWithdrawableMsat
            delay(1_000)
        }
        error("no note on account $account at $host after paying its invoice")
    }
}
