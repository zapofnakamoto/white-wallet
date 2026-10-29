package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST

/**
 * Real use pauses between requests (reading a dialog, tapping Rotate). The reference mint (uvicorn) closes idle
 * keep-alive connections after ~5 s, so the next request must not die on a stale pooled connection.
 * Needs the stack from dev/regtest and LNURLCASH_REGTEST=1.
 */
class RegtestIdleTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `rotate all after the connection has been idle`() = runBlocking<Unit> {
        val phone = Regtest.phone(strategy = ManualStrategy.ID)
        Regtest.pay(phone.address, 1000)
        Regtest.pay(phone.address, 2000)
        assertEquals(2, phone.wallet.refresh().newNotes)

        delay(7_000) // longer than the server's keep-alive

        val result = phone.wallet.rotateAllKeys(HOST, 2)
        assertEquals(RotateAllResult(rotated = 2, failed = 0, pending = 0, totalMsat = 2_998_000, stoppedEarly = null), result)
        assertEquals(2_998_000, phone.balance())

        delay(7_000)
        assertIs<RotateResult.Rotated>(phone.wallet.rotateKey(HOST, 0, 0), "a single rotate after another idle pause")
    }
}
