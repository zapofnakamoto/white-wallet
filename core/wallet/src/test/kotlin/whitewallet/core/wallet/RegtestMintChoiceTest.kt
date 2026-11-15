package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2

/** Receiving on, and paying from, a mint the user picked instead of the one the wallet would. */
class RegtestMintChoiceTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    @Test
    fun `an invoice on another mint than home is paid from home, and that mint then pays and makes notes on its own`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            alice.fund(5000)
            assertIs<ConnectResult.Connected>(alice.wallet.connectMint(HOST2))

            // Received on the second mint, paid from the first, each picked by hand.
            val request = assertIs<InvoiceRequest.Ready>(alice.wallet.requestInvoice(2_000_000, on = HOST2))
            val toSecond = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(request.invoice, from = HOST))
            assertEquals(HOST, toSecond.from)
            assertEquals(HOST, assertIs<PayQuote.Ok>(alice.wallet.quotePayment(toSecond, 2_000_000)).host)
            assertIs<PayResult.Paid>(alice.wallet.pay(toSecond, 2_000_000))
            assertEquals(InvoiceStatus.Paid(request.expectedMsat), paidStatus(alice, request))
            assertEquals(listOf(request.expectedMsat), alice.notes(HOST2).map { it.amountMsat })
            val onFirst = alice.notes(HOST).sumOf { it.amountMsat }

            // Left to itself, the wallet pays from home.
            assertEquals(HOST, assertIs<PayQuote.Ok>(alice.wallet.quotePayment(ready(alice, Regtest.invoice(500), null), 500_000)).host)

            // Picked, the second mint pays (an invoice on home: the second mint's node is user-lnd, which can't pay itself).
            val back = assertIs<InvoiceRequest.Ready>(alice.wallet.requestInvoice(500_000, on = HOST))
            val fromSecond = ready(alice, back.invoice, HOST2)
            assertEquals(HOST2, assertIs<PayQuote.Ok>(alice.wallet.quotePayment(fromSecond, 500_000)).host)
            assertIs<PayResult.Paid>(alice.wallet.pay(fromSecond, 500_000))
            assertEquals(InvoiceStatus.Paid(back.expectedMsat), paidStatus(alice, back))
            assertEquals(onFirst + back.expectedMsat, alice.notes(HOST).sumOf { it.amountMsat }, "nothing taken from home")
            val leftOnSecond = alice.notes(HOST2).sumOf { it.amountMsat }
            assertTrue(leftOnSecond <= request.expectedMsat - 500_000, "paid from the second mint: $leftOnSecond left")

            // More than the picked mint holds is too much, though home could pay it.
            val most = alice.wallet.sendableByMint().getValue(HOST2)
            assertTrue(most <= leftOnSecond)
            assertTrue(alice.wallet.sendableByMint().getValue(HOST) > 2_000_000)
            val big = ready(alice, Regtest.invoice(2000), HOST2)
            assertEquals(most, big.availableMsat)
            assertEquals(PayQuote.Invalid(PayProblem.TooMuch, most), alice.wallet.quotePayment(big, 2_000_000))

            // A note link made from the second mint is on it.
            assertEquals(HOST2, assertIs<PayQuote.Ok>(alice.wallet.quoteNote(100_000, from = HOST2)).host)
            val created = assertIs<CreateNoteResult.Created>(alice.wallet.createNote(100_000, from = HOST2))
            assertEquals(HOST2, created.note.host)
            assertEquals(onFirst + back.expectedMsat, alice.notes(HOST).sumOf { it.amountMsat }, "still nothing taken from home")
        }

    @Test
    fun `an address on the mint the money is picked from is paid within it, from another mint over Lightning`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            alice.fund(5000)
            val bob = Regtest.phone()
            assertTrue(assertIs<PayPreview.Ready>(alice.wallet.previewPayment(bob.address, from = HOST)).internal)

            // Alice has nothing on the second mint to pay from yet.
            assertIs<ConnectResult.Connected>(alice.wallet.connectMint(HOST2))
            assertEquals(PayPreview.Invalid(PayProblem.NoFunds), alice.wallet.previewPayment(bob.address, from = HOST2))
        }

    /** [request]'s status once the mint has settled it, waiting up to 10 s. */
    private suspend fun paidStatus(phone: Regtest.Phone, request: InvoiceRequest.Ready): InvoiceStatus {
        var status = phone.wallet.invoiceStatus(request.id)
        repeat(20) {
            if (status !is InvoiceStatus.Waiting) return status
            delay(500)
            status = phone.wallet.invoiceStatus(request.id)
        }
        return status
    }

    private suspend fun ready(phone: Regtest.Phone, input: String, from: String?) =
        assertIs<PayPreview.Ready>(phone.wallet.previewPayment(input, from))
}
