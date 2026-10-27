package whitewallet.core.wallet

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2

/** The developer faucet the app's debug build talks to (dev/regtest/faucet.py): paying the wallet and making invoices for it. */
class RegtestFaucetTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    private val faucet = RegtestFaucet("localhost:8114")

    @Test
    fun `the faucet pays an address on either mint`() = runBlocking<Unit> {
        for (host in listOf(HOST, HOST2)) {
            val alice = Regtest.phone(host = host)
            assertEquals(1000, faucet.pay(alice.address, 1000))
            assertTrue(alice.wallet.refresh().receivedMsat > 990_000, "on $host")
        }
    }

    @Test
    fun `the faucet makes an invoice the wallet pays from its home mint`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val invoice = faucet.invoice(1000, HOST)
        val preview = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(invoice))
        assertEquals(1_000_000, preview.fixedAmountMsat)
        assertIs<PayResult.Paid>(alice.wallet.pay(preview, 1_000_000))
        assertTrue(Regtest.settled(invoice))
    }

    @Test
    fun `a faucet refusal comes back as its reason, and a build without one never calls out`() = runBlocking<Unit> {
        val refused = assertFailsWith<IOException> { faucet.pay("not a target", 1000) }
        assertTrue(!refused.message.isNullOrBlank(), "the faucet says why")
        assertFailsWith<IOException> { RegtestFaucet(null).pay("x@$HOST", 1000) }
    }
}
