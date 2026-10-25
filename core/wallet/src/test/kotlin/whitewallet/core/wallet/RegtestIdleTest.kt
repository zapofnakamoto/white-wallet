package whitewallet.core.wallet

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * Real use pauses between requests (reading a dialog, tapping Rotate). The reference mint (uvicorn) closes idle
 * keep-alive connections after ~5 s, so the next request must not die on a stale pooled connection.
 * Needs the stack from dev/regtest and LNURLCASH_REGTEST=1.
 */
class RegtestIdleTest {
    private val host = "localhost:8111"
    private val random = SecureRandom()

    @BeforeEach
    fun requireStack() = assumeTrue(System.getenv("LNURLCASH_REGTEST") == "1", "LNURLCASH_REGTEST not set")

    private fun pay(target: String, sats: Int) {
        val process = ProcessBuilder(File("../../dev/regtest/regtest.sh").canonicalPath, "pay", target, sats.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
    }

    @Test
    fun `rotate all after the connection has been idle`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        val name = "idle" + (1..6).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")
        assertIs<OnboardResult.Ready>(wallet.onboard(name, host))
        pay("$name@$host", 1000)
        pay("$name@$host", 2000)
        assertEquals(2, wallet.refresh().newNotes)

        delay(7_000) // longer than the server's keep-alive

        val result = wallet.rotateAllKeys(host, 2)
        assertEquals(RotateAllResult(rotated = 2, failed = 0, pending = 0, totalMsat = 2_998_000, stoppedEarly = null), result)
        assertEquals(2_998_000, wallet.balanceMsat.first())

        delay(7_000)
        assertIs<RotateResult.Rotated>(wallet.rotateKey(host, 0, 0), "a single rotate after another idle pause")
    }
}
