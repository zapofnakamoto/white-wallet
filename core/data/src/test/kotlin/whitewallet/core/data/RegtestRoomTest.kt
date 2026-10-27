package whitewallet.core.data

import androidx.room.Room
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.wallet.CreateAccountResult
import whitewallet.core.wallet.InvoiceRequest
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.NotePolicies
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.PayPreview
import whitewallet.core.wallet.PayResult
import whitewallet.core.wallet.SeedStore
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * The app's own store on the real mint (dev/regtest): the wallet is closed and opened again over the same database,
 * as when the app is killed and restarted, and picks up where it left off. Skipped unless LNURLCASH_REGTEST=1.
 */
@RunWith(RobolectricTestRunner::class)
class RegtestRoomTest {
    private val host = "localhost:8111"
    private val dbName = "regtest-${System.nanoTime()}.db"
    private val seeds = Seeds()
    private var db: WalletDatabase? = null

    @BeforeTest
    fun requireStack() = assumeTrue("LNURLCASH_REGTEST not set", System.getenv("LNURLCASH_REGTEST") == "1")

    @AfterTest
    fun close() {
        db?.close()
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    /** Opens the wallet over the database, closing the one before: an app start. */
    private fun start(client: LnurlcashClient = LnurlcashClient(), requireAddress: Boolean = true): Wallet {
        db?.close()
        val opened = Room.databaseBuilder(RuntimeEnvironment.getApplication(), WalletDatabase::class.java, dbName).allowMainThreadQueries().build()
        db = opened
        return Wallet(RoomWalletStore(opened), seeds, client, policies = NotePolicies.InMemory(ManualStrategy.ID), requireAddress = requireAddress)
    }

    @Test
    fun `notes, history and the address survive a restart, and the notes pay after it`() = runBlocking<Unit> {
        var wallet = start()
        val name = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(name, host))
        regtest("pay", "$name@$host", "3000")
        wallet.refresh()
        val rows = wallet.activity.first()

        wallet = start()
        assertEquals("$name@$host", wallet.profile.first()?.address)
        assertEquals(2_999_000, wallet.balanceMsat.first())
        assertEquals(rows, wallet.activity.first())
        assertEquals(0, wallet.refresh().newNotes, "the address scan goes on where it was")

        val preview = assertIs<PayPreview.Ready>(wallet.previewPayment(regtest("invoice", "1000", "regtest test")))
        assertIs<PayResult.Paid>(wallet.pay(preview, 1_000_000))
        assertEquals(2_999_000 - 1_000_000 - 1_000, wallet.balanceMsat.first())
    }

    @Test
    fun `a payment left in flight when the app died is settled from the stored journal after the restart, paid once`() = runBlocking<Unit> {
        val bob = start()
        val bobName = name()
        assertIs<OnboardResult.Ready>(bob.onboard(bobName, host))
        bob.refresh()

        // Alice on her own database; her connection goes for good once the transfer reached the mint.
        val aliceDb = "$dbName-alice"
        var offline = false
        var armed = false
        val client = LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
            val url = chain.request().url
            if (offline) throw IOException("connection dropped")
            if (armed && url.queryParameter("k1") != null && url.queryParameter("p1") != null) {
                offline = true
                chain.proceed(chain.request()).close()
                throw IOException("connection dropped before the answer arrived")
            }
            chain.proceed(chain.request())
        }.build())
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), WalletDatabase::class.java, aliceDb).allowMainThreadQueries().build()
        val aliceSeeds = Seeds()
        var aliceDatabase = open()
        try {
            var alice = Wallet(RoomWalletStore(aliceDatabase), aliceSeeds, client, policies = NotePolicies.InMemory(ManualStrategy.ID))
            val aliceName = name()
            assertIs<OnboardResult.Ready>(alice.onboard(aliceName, host))
            regtest("pay", "$aliceName@$host", "2000")
            alice.refresh()
            val preview = assertIs<PayPreview.Ready>(alice.previewPayment("$bobName@$host"))
            assertTrue(preview.internal)
            armed = true
            assertEquals(PayResult.Pending(1_999_000), alice.pay(preview, 1_999_000))
            armed = false
            assertEquals(0, alice.balanceMsat.first(), "out of the balance while in flight")

            // The app dies; it starts again with the connection back.
            aliceDatabase.close()
            offline = false
            aliceDatabase = open()
            alice = Wallet(RoomWalletStore(aliceDatabase), aliceSeeds, client, policies = NotePolicies.InMemory(ManualStrategy.ID))
            assertEquals(1, alice.unresolvedOperations())
            assertEquals(0, alice.balanceMsat.first(), "still out of the balance after the restart")
            alice.refresh()
            assertEquals(0, alice.unresolvedOperations())
            assertEquals(listOf(ActivityStatus.Done), alice.activity.first().filter { it.kind == ActivityKind.Sent }.map { it.status })
            assertEquals(0, alice.balanceMsat.first())
            bob.refresh()
            assertEquals(1_999_000, bob.balanceMsat.first(), "paid once")
        } finally {
            aliceDatabase.close()
            RuntimeEnvironment.getApplication().deleteDatabase(aliceDb)
        }
    }

    @Test
    fun `an invoice shown before a restart and paid after it is received`() = runBlocking<Unit> {
        var wallet = start(requireAddress = false)
        assertIs<CreateAccountResult.Created>(wallet.createAccount(host, "Main"))
        val request = assertIs<InvoiceRequest.Ready>(wallet.requestInvoice(2_000_000))

        wallet = start(requireAddress = false)
        regtest("pay", request.invoice, "2000")
        var balance = 0L
        for (attempt in 1..20) {
            wallet.refresh()
            balance = wallet.balanceMsat.first()
            if (balance > 0) break
            delay(500)
        }
        assertEquals(1_999_000, balance)
        assertEquals(0, wallet.unresolvedOperations())
        assertEquals(listOf(ActivityKind.Received to 1_999_000L), wallet.activity.first().map { it.kind to it.amountMsat })
    }

    /** The phrases, kept across restarts as the Keystore keeps them on a phone. */
    private class Seeds : SeedStore {
        private val words = mutableMapOf<String, List<String>>()
        private val verified = MutableStateFlow<Set<String>>(emptySet())
        override suspend fun load(accountId: String) = words[accountId]
        override suspend fun save(accountId: String, words: List<String>) { this.words[accountId] = words }
        override fun backupVerified(accountId: String): Flow<Boolean> = verified.map { accountId in it }
        override suspend fun setBackupVerified(accountId: String) { verified.value = verified.value + accountId }
        override suspend fun clear(accountId: String) { words.remove(accountId) }
    }

    private companion object {
        val script: File = File("../../dev/regtest/regtest.sh").canonicalFile
        val random = SecureRandom()

        fun name() = "rr" + (1..8).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

        /** Runs regtest.sh with [args]; its result is the last line of the output. */
        fun regtest(vararg args: String): String {
            val process = ProcessBuilder(listOf(script.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "regtest.sh ${args.joinToString(" ")} failed:\n$output" }
            return output.trim().lines().last()
        }
    }
}
