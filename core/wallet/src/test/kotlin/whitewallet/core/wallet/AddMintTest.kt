package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import java.io.IOException
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.MintScan
import whitewallet.core.wallet.legacy.LegacyWallet
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.spendDomain
import whitewallet.testkit.MockMint

/**
 * Adding a mint finds what every account of the active wallet already holds there, as a restore does on its mint. Every case runs against
 * mock mints ([MockMintAddMintTest]) and the regtest stack ([RegtestAddMintTest]); each uses a fresh phrase, so notes left on a mint by
 * earlier runs never count.
 */
abstract class AddMintTest {
    protected abstract val home: String
    protected abstract val other: String

    /** Puts a note of [sats] on key [index] of [set] of account [account] of [words] on [host]; returns what the note is worth. */
    protected abstract suspend fun credit(words: List<String>, host: String, set: KeySet, index: Long, sats: Long, account: Long = 0): Long

    /** A client to the mints; [intercept], if given, sees every request. */
    protected abstract fun client(intercept: Interceptor? = null): LnurlcashClient

    @Test
    fun `adding a mint finds the notes of every key chain there and sets the indexes past them`() =
        runBlocking<Unit> {
            val words = phrase()
            val onHome = credit(words, home, KeySet.Wallet, 0, SATS)
            val msat =
                credit(words, other, KeySet.Wallet, 1, SATS) +
                    credit(words, other, KeySet.Change, 0, SATS) +
                    credit(words, other, KeySet.share(0), 0, SATS) +
                    credit(words, other, KeySet.share(2), 1, SATS) +
                    credit(words, other, KeySet.LegacyWallet, 0, SATS)
            val phone = Fresh()
            val id = assertIs<RestoreResult.Restored>(phone.restore(words)).accountId
            assertEquals(onHome, phone.balance())

            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))

            assertEquals(onHome + msat, phone.balance())
            assertEquals(5, phone.store.allNotes().count { it.host == other })
            assertEquals(2, phone.store.indexOf(id, other, KeySet.Wallet), "never hands out a used key")
            assertEquals(1, phone.store.indexOf(id, other, KeySet.Change))
            assertEquals(2, phone.store.indexOf(id, other, KeySet.share(2)))
            assertEquals(3, phone.shares(id))
        }

    @Test
    fun `a wallet restored without a mint finds everything as its mints are added`() =
        runBlocking<Unit> {
            val words = phrase()
            // Another chain than on the other mint: both mints are on localhost, one domain, so their chains are the same keys.
            val onHome = credit(words, home, KeySet.Wallet, 0, SATS)
            val found = credit(words, other, KeySet.Change, 0, SATS, account = 1)
            val phone = Fresh()
            val id = assertIs<RestoreResult.Restored>(phone.restore(words, host = null)).accountId
            assertEquals(0, phone.balance())
            assertNull(phone.store.homeOf(id))
            assertEquals(false, phone.wallet.hasMint.first())

            assertIs<ConnectResult.Connected>(phone.wallet.addMint(home))
            assertEquals(mapOf(0L to onHome), phone.balances())
            assertEquals(home, phone.store.homeOf(id), "the first mint of its wallet is the account's home")

            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))
            assertEquals(mapOf(0L to onHome, 1L to found), phone.balances())
            assertEquals(home, phone.store.homeOf(id), "a mint added later doesn't take the home's place")
            assertEquals(other, phone.store.homeOf(phone.ids().getValue(1)), "an account found on a mint has its home there")
            assertEquals(id, phone.store.currentAccountId())
        }

    @Test
    fun `adding a mint reports its scan account by account, and says what each account holds there`() =
        runBlocking<Unit> {
            val words = phrase()
            val first = credit(words, home, KeySet.Wallet, 0, SATS)
            val second = credit(words, home, KeySet.Change, 0, SATS, account = 1)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words, host = null))
            val reports = mutableListOf<ScanProgress>()
            assertIs<ConnectResult.Connected>(phone.wallet.addMint(home, onProgress = { reports += it }))

            val last = reports.last().accounts
            assertEquals(listOf(true, true), last.take(2).map { it.done })
            assertEquals(listOf(first, second), last.take(2).map { it.foundMsat })
            assertEquals(true, last.all { it.keysChecked > 0 })
            assertEquals(
                true,
                reports.map { it.accounts.sumOf { a -> a.keysChecked } }.zipWithNext().all { (a, b) -> a <= b },
                "only forward",
            )
            assertEquals(listOf(0L to first, 1L to second), phone.wallet.heldAt(home).map { it.first.index to it.second })
        }

    @Test
    fun `a preview writes nothing, and the mint is only added with the key it showed`() =
        runBlocking<Unit> {
            val phone = Fresh()
            val id = assertIs<RestoreResult.Restored>(phone.restore(phrase(), host = null)).accountId
            val preview = assertIs<MintPreview.Ready>(phone.wallet.previewMint(other))
            assertNotNull(preview.signingKey)
            assertNull(preview.knownKey)
            assertNull(phone.store.mint(other), "nothing saved, nothing pinned")
            assertNull(phone.store.homeOf(id))
            assertIs<MintPreview.Unreachable>(phone.wallet.previewMint("localhost:1"))

            val wrong = assertIs<ConnectResult.Failed>(phone.wallet.addMint(other, expectedKey = "02" + "00".repeat(32)))
            assertEquals(OnboardResult.MintInvalid(MINT_KEY_CHANGED), wrong.result)
            assertNull(phone.store.mint(other))
            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other, expectedKey = preview.signingKey))
            assertEquals(preview.signingKey, assertIs<MintPreview.Ready>(phone.wallet.previewMint(other)).knownKey)
        }

    @Test
    fun `a wallet created on this phone isn't scanned while it is set up, and is once it is`() =
        runBlocking<Unit> {
            val phone = Fresh()
            assertIs<CreateAccountResult.Created>(phone.wallet.createWallet("New"))
            val words = phone.wallet.recoveryPhrase()
            val msat = credit(words, home, KeySet.Wallet, 0, SATS)
            var scanned = false
            assertIs<ConnectResult.Connected>(phone.wallet.addMint(home, onProgress = { scanned = true }))
            assertEquals(false, scanned, "nothing of a phrase made here can be at a mint yet")
            assertEquals(0, phone.balance())
            assertEquals(MintScan.Finished, phone.store.mint(home)?.scan, "nothing to look for: it counts as scanned")

            phone.wallet.setSetupStep(null)
            assertIs<ConnectResult.Connected>(phone.wallet.addMint(home))
            assertEquals(msat, phone.balance())
        }

    @Test
    fun `a mint with nothing of the phrase is added, and nothing is found`() =
        runBlocking<Unit> {
            val phone = Fresh()
            val id = assertIs<RestoreResult.Restored>(phone.restore(phrase())).accountId
            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))
            assertNotNull(phone.store.mint(other))
            assertEquals(0, phone.balance())
            assertEquals(0, phone.store.indexOf(id, other, KeySet.Wallet))
            assertNull(phone.shares(id))
        }

    @Test
    fun `new payments to an address found on the added mint arrive on a refresh`() =
        runBlocking<Unit> {
            val words = phrase()
            val first = credit(words, other, KeySet.share(0), 0, SATS)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))

            val next = credit(words, other, KeySet.share(0), 1, SATS)
            assertEquals(next, phone.wallet.refresh().receivedMsat)
            assertEquals(first + next, phone.balance())
        }

    @Test
    fun `every account of the wallet is looked up, and the legacy keys stay the first account's`() =
        runBlocking<Unit> {
            val words = phrase()
            // Another chain than on the other mint: both mints are on localhost, one domain, so their chains are the same keys.
            val onHome = credit(words, home, KeySet.Change, 0, SATS, account = 1)
            val first = credit(words, other, KeySet.Wallet, 0, SATS) + credit(words, other, KeySet.LegacyWallet, 0, SATS)
            val found = credit(words, other, KeySet.Wallet, 0, SATS, account = 1)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            phone.store.setActiveAccount(phone.ids().getValue(1))

            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))
            assertEquals(mapOf(0L to first, 1L to onHome + found), phone.balances())
            assertEquals(1, phone.store.allNotes().count { it.host == other && it.accountId == phone.ids().getValue(1) })
        }

    @Test
    fun `an account found only on the added mint is created there, and the active one stays`() =
        runBlocking<Unit> {
            val words = phrase()
            val found = credit(words, other, KeySet.Wallet, 0, SATS, account = 1)
            val phone = Fresh()
            val id = assertIs<RestoreResult.Restored>(phone.restore(words)).accountId
            assertEquals(setOf(0L), phone.ids().keys)

            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))
            assertEquals(mapOf(0L to 0L, 1L to found), phone.balances())
            val created = assertNotNull(phone.store.account(phone.ids().getValue(1)))
            assertEquals(other, created.homeHost, "its home is where it was found")
            assertNotEquals(other, phone.store.homeOf(id), "the account the wallet had keeps its own")
            assertEquals("2", created.name, "named by the app, by its index")
            assertEquals(id, created.walletId)
            assertEquals(id, phone.store.currentAccountId())
        }

    @Test
    fun `no account is created past a gap of unused ones, nor past the most the brand allows`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, other, KeySet.Wallet, 0, SATS, account = 2)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))
            assertEquals(setOf(0L), phone.ids().keys, "account 1 is unused: the scan stops there")

            val single = phrase()
            credit(single, other, KeySet.Wallet, 0, SATS, account = 1)
            val one = Fresh(maxAccounts = 1)
            assertIs<RestoreResult.Restored>(one.restore(single))
            assertIs<ConnectResult.Connected>(one.wallet.addMint(other))
            assertEquals(setOf(0L), one.ids().keys)
        }

    @Test
    fun `an account the wallet has is looked up past a gap of unused ones`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, home, KeySet.Change, 0, SATS, account = 1)
            credit(words, home, KeySet.Change, 0, SATS, account = 2)
            val found = credit(words, other, KeySet.Wallet, 0, SATS, account = 2)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(setOf(0L, 1L, 2L), phone.ids().keys)

            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))
            assertEquals(1, phone.store.allNotes().count { it.host == other && it.accountId == phone.ids().getValue(2) })
            assertEquals(found, phone.store.allNotes().filter { it.host == other }.sumOf { it.amountMsat })
        }

    @Test
    fun `another wallet on the phone is left alone`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, other, KeySet.Wallet, 0, SATS)
            credit(words, other, KeySet.Wallet, 0, SATS, account = 1)
            val phone = Fresh()
            val left = assertIs<RestoreResult.Restored>(phone.restore(words)).accountId
            val active = assertIs<RestoreResult.Restored>(phone.restore(phrase())).accountId

            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))
            assertEquals(0, phone.store.allNotes().count { it.host == other })
            assertEquals(listOf(left), phone.store.accounts.first().map { it.account }.filter { it.walletId == left }.map { it.id })
            assertEquals(active, phone.store.currentAccountId())
        }

    @Test
    fun `adding a mint never takes back shares handed out there`() =
        runBlocking<Unit> {
            val words = phrase()
            credit(words, other, KeySet.share(0), 0, SATS)
            val phone = Fresh()
            val id = assertIs<RestoreResult.Restored>(phone.restore(words)).accountId
            // Shares 1 and 2 went to addresses nobody paid: they look unused, and must not be given out again.
            phone.store.setKeyIndex(id, spendDomain(other), KeyCounter.Shares, 3)

            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))
            assertEquals(3, phone.shares(id))
        }

    @Test
    fun `a mint that stops answering before anything is found stays, its scan unfinished, and a scan finds the notes`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, other, KeySet.Wallet, 0, SATS)
            var answering = true
            val phone = Fresh(onLookup(other) { chain -> if (answering) chain.proceed(chain.request()) else throw IOException("dropped") })
            assertIs<RestoreResult.Restored>(phone.restore(words))

            answering = false
            val failed = assertIs<ConnectResult.Failed>(phone.wallet.addMint(other))
            assertIs<OnboardResult.NetworkError>(failed.result)
            assertEquals(MintScan.Unfinished, phone.store.mint(other)?.scan)
            assertEquals(listOf(other), phone.wallet.unscannedMints.first().map { it.host })
            // While it doesn't answer, a scan says where it stopped and changes nothing.
            assertEquals(ScanResult(0, listOf(other)), phone.wallet.scan())
            assertEquals(listOf(other), phone.wallet.unscannedMints.first().map { it.host })

            answering = true
            assertEquals(ScanResult(msat, emptyList()), phone.wallet.scan())
            assertEquals(msat, phone.balance())
            assertEquals(emptyList(), phone.wallet.unscannedMints.first())
            assertEquals(ScanResult(0, emptyList()), phone.wallet.scan(), "again finds nothing new and counts nothing twice")
            assertEquals(msat, phone.balance())
        }

    @Test
    fun `a scan cut off half way keeps what it found and says it isn't finished, and a scan finds the rest`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, other, KeySet.Wallet, 0, SATS)
            val rest = credit(words, other, KeySet.Change, 0, SATS)
            var lookups = 0
            var limit = Int.MAX_VALUE
            val phone =
                Fresh(onLookup(other) { chain -> if (++lookups > limit) throw IOException("dropped") else chain.proceed(chain.request()) })
            assertIs<RestoreResult.Restored>(phone.restore(words))

            limit = 2
            assertIs<ConnectResult.Failed>(phone.wallet.addMint(other))
            assertEquals(MintScan.Unfinished, phone.store.mint(other)?.scan)
            assertEquals(msat, phone.balance())

            limit = Int.MAX_VALUE
            val scanned = mutableSetOf<String>()
            assertEquals(ScanResult(rest, emptyList()), phone.wallet.scan { host, _ -> scanned += host })
            assertEquals(msat + rest, phone.balance())
            assertEquals(MintScan.Finished, phone.store.mint(other)?.scan)
            assertEquals(setOf(home, other), scanned, "every mint of the wallet is looked through")
        }

    @Test
    fun `a scan cut off in an account it created keeps that account if it found a note there`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, other, KeySet.Wallet, 0, SATS, account = 1)
            val rest = credit(words, other, KeySet.Change, 0, SATS, account = 1)
            val stop = Cp1.encode(keys(words, other, 1).pubkey(KeySet.Change, 0))
            var answering = false
            val phone =
                Fresh(
                    onLookup(other) { chain ->
                        if (!answering && chain.request().url.queryParameter("p") == stop) throw IOException("dropped")
                        chain.proceed(chain.request())
                    }
                )
            assertIs<RestoreResult.Restored>(phone.restore(words))

            assertIs<ConnectResult.Failed>(phone.wallet.addMint(other))
            assertEquals(mapOf(0L to 0L, 1L to msat), phone.balances())

            answering = true
            assertIs<ConnectResult.Connected>(phone.wallet.addMint(other))
            assertEquals(mapOf(0L to 0L, 1L to msat + rest), phone.balances())
        }

    @Test
    fun `a mint that refuses a lookup stays, its scan unfinished`() =
        runBlocking<Unit> {
            val phone = Fresh(onLookup(other) { chain -> Regtest.answer(chain, """{"status":"ERROR","reason":"not today"}""") })
            assertIs<RestoreResult.Restored>(phone.restore(phrase()))
            val failed = assertIs<ConnectResult.Failed>(phone.wallet.addMint(other))
            assertIs<OnboardResult.MintInvalid>(failed.result)
            assertEquals(MintScan.Unfinished, phone.store.mint(other)?.scan)
        }

    @Test
    fun `a mint the wallet meets without adding it was never scanned, until a scan looks through it`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, other, KeySet.Change, 0, SATS)
            val phone = Fresh()
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertEquals(MintScan.Finished, phone.store.mint(home)?.scan, "the restore looked through its mint")
            assertEquals(emptyList(), phone.wallet.unscannedMints.first())

            // As claiming a note from it, or getting an address there, does.
            assertIs<ConnectResult.Connected>(phone.wallet.connectMint(other))
            assertEquals(MintScan.Never, phone.store.mint(other)?.scan)
            assertEquals(listOf(other), phone.wallet.unscannedMints.first().map { it.host })
            assertEquals(0, phone.balance())
            // Connecting again (its fees are refreshed) doesn't forget what is known of its scan.
            assertIs<ConnectResult.Connected>(phone.wallet.connectMint(home))
            assertEquals(MintScan.Finished, phone.store.mint(home)?.scan)

            assertEquals(ScanResult(msat, emptyList()), phone.wallet.scan())
            assertEquals(msat, phone.balance())
            assertEquals(emptyList(), phone.wallet.unscannedMints.first())
        }

    @Test
    fun `a scan passes over a mint that doesn't answer and still looks through the others`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, other, KeySet.Wallet, 0, SATS)
            val later = credit(words, home, KeySet.Change, 3, SATS)
            var answering = false
            val phone = Fresh(onLookup(other) { chain -> if (answering) chain.proceed(chain.request()) else throw IOException("dropped") })
            answering = true
            assertIs<RestoreResult.Restored>(phone.restore(words))
            assertIs<ConnectResult.Connected>(phone.wallet.connectMint(other))
            answering = false
            val more = credit(words, home, KeySet.Wallet, 5, SATS)

            assertEquals(ScanResult(more, listOf(other)), phone.wallet.scan())
            assertEquals(later + more, phone.balance())
            assertEquals(MintScan.Unfinished, phone.store.mint(other)?.scan)

            answering = true
            // Mints on one domain share their keys: what is known at the home mint (keys 3 and 5) doesn't hide key 0 here.
            assertEquals(ScanResult(msat, emptyList()), phone.wallet.scan())
            assertEquals(later + more + msat, phone.balance())
            assertEquals(emptyList(), phone.wallet.unscannedMints.first())

            // Looked through once, it stays so when looking again stops: nothing is missing that wasn't found then.
            answering = false
            assertEquals(ScanResult(0, listOf(other)), phone.wallet.scan())
            assertEquals(MintScan.Finished, phone.store.mint(other)?.scan)
        }

    @Test
    fun `leaving the screen doesn't cut the scan off`() =
        runBlocking<Unit> {
            val words = phrase()
            val msat = credit(words, other, KeySet.Change, 0, SATS)
            var adding: Job? = null
            val phone =
                Fresh(
                    onLookup(other) { chain ->
                        adding?.cancel()
                        chain.proceed(chain.request())
                    }
                )
            assertIs<RestoreResult.Restored>(phone.restore(words))

            adding = launch(Dispatchers.Default) { phone.wallet.addMint(other) }
            adding.join()
            assertEquals(msat, phone.balance())
        }

    /** Answers every lookup on [host] with [answer], and lets everything else through. */
    private fun onLookup(host: String, answer: (Interceptor.Chain) -> Response) = Interceptor { chain ->
        val url = chain.request().url
        if ("${url.host}:${url.port}" == host && url.queryParameter("p") != null) answer(chain) else chain.proceed(chain.request())
    }

    /** A phone with nothing on it yet; a restore stops after a few unused shares, as the tests use only the first. */
    private inner class Fresh(intercept: Interceptor? = null, maxAccounts: Int = AppDefaults.UNLIMITED) {
        val store = InMemoryWalletStore()
        val wallet =
            Wallet(
                store,
                InMemorySeedStore(),
                client(intercept),
                policies = NotePolicies.InMemory(ManualStrategy.ID),
                settings = WalletSettings(scanStopAfterUnusedShares = 3),
                maxAccounts = maxAccounts,
            )

        suspend fun restore(words: List<String>, host: String? = home) = wallet.restore(words.joinToString(" "), host, "Restored")

        suspend fun balance(): Long = wallet.balanceMsat.first()

        /** Each account's id, by its index in the wallet. */
        suspend fun ids(): Map<Long, String> = store.accounts.first().associate { it.account.index to it.account.id }

        /** What each account holds, by its index in the wallet. */
        suspend fun balances(): Map<Long, Long> = store.accounts.first().associate { it.account.index to it.balanceMsat }

        suspend fun shares(accountId: String): Long? = store.keyIndexOrNull(accountId, spendDomain(other), KeyCounter.Shares)
    }

    internal fun keys(words: List<String>, host: String, account: Long): AccountKeys = testKeys(words, host, account)

    private fun phrase(): List<String> = MnemonicCode.toMnemonics(ByteArray(16).also(random::nextBytes))

    private companion object {
        val random = SecureRandom()
        const val SATS = 1_000L
    }
}

class MockMintAddMintTest : AddMintTest() {
    private var homeMint: MockMint? = null
    private var otherMint: MockMint? = null

    override val home: String
        get() = homeMint!!.host

    override val other: String
        get() = otherMint!!.host

    @BeforeEach
    fun start() {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        homeMint = MockMint.start()
        otherMint = MockMint.start()
    }

    @AfterEach
    fun stop() {
        homeMint?.close()
        otherMint?.close()
    }

    override suspend fun credit(words: List<String>, host: String, set: KeySet, index: Long, sats: Long, account: Long): Long {
        val mint = if (host == home) homeMint!! else otherMint!!
        mint.credit(Cp1.encode(keys(words, host, account).pubkey(set, index)), sats * 1000)
        return sats * 1000
    }

    override fun client(intercept: Interceptor?): LnurlcashClient =
        if (intercept == null) LnurlcashClient()
        else LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(intercept).build())
}

/** [AddMintTest] against the real reference mints: notes are minted to the chosen keys by paying the mint's invoices for them. */
class RegtestAddMintTest : AddMintTest() {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    override val home = Regtest.HOST
    override val other = Regtest.HOST2

    override suspend fun credit(words: List<String>, host: String, set: KeySet, index: Long, sats: Long, account: Long): Long {
        val client = client()
        val pay = client.servicePayRequest(host)
        val key = keys(words, host, account).pubkey(set, index)
        val asker = Wallet(InMemoryWalletStore(), InMemorySeedStore(), client)
        Regtest.pay(assertIs<MintInvoice.Ok>(MintInvoice.fetch(asker, pay, sats * 1000, key)).invoice, sats)
        repeat(30) {
            val note = runCatching { client.lookup(pay.withdrawUrl, key) }.getOrNull()
            if (note != null) return note.maxWithdrawableMsat
            delay(1_000)
        }
        error("no note on $set/$index after paying its invoice")
    }

    override fun client(intercept: Interceptor?): LnurlcashClient = intercept?.let(Regtest::client) ?: LnurlcashClient()
}
