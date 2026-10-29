package whitewallet.core.wallet

import java.io.File
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.brand.MaintenanceStep
import whitewallet.brand.WalletTask
import whitewallet.lnurlcash.client.LnurlcashClient

/** What the wallet says it works on ([Wallet.task]) against the real reference mint (dev/regtest, LNURLCASH_REGTEST=1). */
class RegtestWalletTaskTest {
    private val host = "localhost:8111"
    private val random = SecureRandom()

    @BeforeEach
    fun requireStack() = assumeTrue(System.getenv("LNURLCASH_REGTEST") == "1", "LNURLCASH_REGTEST not set")

    private fun pay(target: String, sats: Int) {
        val process = ProcessBuilder(File("../../dev/regtest/regtest.sh").canonicalPath, "pay", target, sats.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
    }

    private fun name() = "task" + (1..6).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    /** Every task [wallet] reports while [block] runs, in order, repeats dropped. */
    private suspend fun tasks(wallet: Wallet, block: suspend () -> Unit): List<WalletTask?> {
        val seen = Collections.synchronizedList(mutableListOf<WalletTask?>())
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        watcher.launch { wallet.task.collect { if (seen.lastOrNull() != it || seen.isEmpty()) seen += it } }
        try {
            block()
        } finally {
            watcher.cancel()
        }
        return seen.toList()
    }

    @Test
    fun `a refresh says it updates, and which maintenance step runs`() = runBlocking<Unit> {
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        val alice = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(alice, host))
        wallet.setNoteStrategy(SingleNoteStrategy.ID)
        pay("$alice@$host", 1000)
        pay("$alice@$host", 1000)

        val combining = WalletTask.Maintaining(MaintenanceStep.Combining, SingleNoteStrategy.ID)
        val seen = tasks(wallet) { wallet.refresh() }
        assertEquals(listOf(null, WalletTask.Updating, combining, WalletTask.Updating, null), seen)

        // Nothing to maintain: only updating.
        assertEquals(listOf(null, WalletTask.Updating, null), tasks(wallet) { wallet.refresh() })

        // Checking every note counts them off, after the refresh before it, with no pause in between.
        val checking = tasks(wallet) { wallet.recheckNotes() }
        assertEquals(listOf(null, WalletTask.Updating, WalletTask.CheckingNotes(0, 1), WalletTask.Updating, null), checking)
    }

    @Test
    fun `auto-renew is maintenance of no strategy`() = runBlocking<Unit> {
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        val bob = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(bob, host))
        wallet.setNoteStrategy(ManualStrategy.ID)
        wallet.setAutoRotate(true)
        pay("$bob@$host", 1500)

        val seen = tasks(wallet) { wallet.refresh() }
        assertTrue(WalletTask.Maintaining(MaintenanceStep.Renewing) in seen, "renewing: $seen")
        assertNull(wallet.task.value)
    }
}
