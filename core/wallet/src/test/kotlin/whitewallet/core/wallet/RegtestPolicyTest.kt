package whitewallet.core.wallet

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.client.LnurlcashClient

/** Note policies against the real reference mint (dev/regtest, LNURLCASH_REGTEST=1). */
class RegtestPolicyTest {
    private val host = "localhost:8111"
    private val random = SecureRandom()

    @BeforeEach
    fun requireStack() = assumeTrue(System.getenv("LNURLCASH_REGTEST") == "1", "LNURLCASH_REGTEST not set")

    private fun pay(target: String, sats: Int) {
        val process = ProcessBuilder(File("../../dev/regtest/regtest.sh").canonicalPath, "pay", target, sats.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
    }

    private fun name() = "pol" + (1..6).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    @Test
    fun `keep only one note and auto-rotate on the real mint`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient())

        // Keep only one note: three payments (1 sat mint fee each) end up as one wallet note.
        val alice = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(alice, host))
        wallet.setNoteStrategy(SingleNoteStrategy.ID)
        pay("$alice@$host", 1000)
        pay("$alice@$host", 1000)
        pay("$alice@$host", 2000)
        wallet.refresh()
        val single = store.allNotes().single { it.state == NoteState.Outstanding }
        assertEquals(0, single.purpose)
        assertNotNull(single.certificate, "the mint certified the merged note")
        val received = 999_000L + 999_000 + 1_999_000
        println("merged 3 notes worth $received msat into ${single.amountMsat} msat")
        assertTrue(single.amountMsat >= received, "a merge never loses value (LUD-25 may refund base fees)")

        // Auto-rotate on a second account: the incoming note moves onto a wallet key.
        val bob = name()
        assertIs<OnboardResult.Ready>(wallet.onboard(bob, host, newAccountName = "Rotating"))
        wallet.setAutoRotate(true)
        pay("$bob@$host", 1500)
        wallet.refresh()
        val rotated = store.allNotes().single { it.accountId == store.currentAccountId() && it.state == NoteState.Outstanding }
        assertEquals(0, rotated.purpose)
        assertEquals(1_499_000, wallet.balanceMsat.first())
    }
}
