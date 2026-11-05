package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach

/** What lnurl-mint refuses that no amount or limit announces: "What a mint refuses" in docs/DEVELOPMENT.md. */
class RegtestMintRulesTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    private suspend fun ready(phone: Regtest.Phone, invoice: String) = assertIs<PayPreview.Ready>(phone.wallet.previewPayment(invoice))

    @Test
    fun `an invoice of the payer's own mint can't be paid, it costs the split, and the notes stay`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            val bob = Regtest.phone()
            alice.fund(5000)
            val before = alice.balance()
            val invoice = assertIs<InvoiceRequest.Ready>(bob.wallet.requestInvoice(1_000_000)).invoice
            val preview = alice.wallet.previewPayment(invoice) as PayPreview.Ready
            assertEquals(PayResult.Failed(PayProblem.OwnMintInvoice), alice.wallet.pay(preview, 1_000_000))
            assertEquals(before - 1_000, alice.balance(), "the split went through before the mint refused the melt")
            assertEquals(1, alice.notes().count { it.amountMsat == 1_000_000L })

            // The note of the amount exists now, so asking again costs nothing.
            assertEquals(PayResult.Failed(PayProblem.OwnMintInvoice), alice.wallet.pay(preview, 1_000_000))
            assertEquals(before - 1_000, alice.balance())
        }

    @Test
    fun `an invoice paid before is refused the second time, and pays nothing twice`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            alice.fund(5000)
            val invoice = Regtest.invoice(1000)
            assertEquals(
                PayResult.Paid(1_000_000, 1_000),
                alice.wallet.pay(alice.wallet.previewPayment(invoice) as PayPreview.Ready, 1_000_000),
            )
            val paid = alice.balance()
            val again = alice.wallet.previewPayment(invoice) as PayPreview.Ready
            assertEquals(PayResult.Failed(PayProblem.InvoiceUsed), alice.wallet.pay(again, 1_000_000))
            assertEquals(paid - 1_000, alice.balance(), "only the split")
        }

    @Test
    fun `an invoice whose payment failed can't be tried again, and costs nothing more`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            alice.fund(2000)
            val hold = Regtest.holdInvoice(1999)
            hold.cancel()
            assertEquals(PayResult.Failed(PayProblem.PaymentFailed), alice.wallet.pay(ready(alice, hold.invoice), 1_999_000))
            alice.wallet.refresh()
            val before = alice.balance()
            assertEquals(PayResult.Failed(PayProblem.InvoiceUsed), alice.wallet.pay(ready(alice, hold.invoice), 1_999_000))
            assertEquals(before, alice.balance())
        }

    @Test
    fun `a refusal the wallet doesn't recognize keeps the mint's words, and the notes`() =
        runBlocking<Unit> {
            val client = Regtest.client { chain ->
                if (chain.request().url.queryParameter("pr") != null) {
                    Regtest.answer(chain, """{"status":"ERROR","reason":"Closed\nfor maintenance."}""")
                } else chain.proceed(chain.request())
            }
            val alice = Regtest.phone(client = client)
            alice.fund(2000)
            val before = alice.balance()
            val invoice = Regtest.invoice(1999)
            assertEquals(
                PayResult.Failed(PayProblem.Refused, "Closed for maintenance."),
                alice.wallet.pay(ready(alice, invoice), 1_999_000),
            )
            assertEquals(before, alice.balance())
        }

    @Test
    fun `a split may go down to 1 msat on either side`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            alice.fund(2000)
            val note = alice.notes().single()
            val limits = assertIs<SplitLimits>(alice.wallet.splitLimits(note.id))
            assertEquals(1, limits.minMsat)
            assertIs<NoteActionResult.Done>(alice.wallet.splitNote(note.id, 1))
            val large = alice.notes().maxBy { it.amountMsat }
            val most = assertIs<SplitLimits>(alice.wallet.splitLimits(large.id)).maxMsat
            assertIs<NoteActionResult.Done>(alice.wallet.splitNote(large.id, most))
            assertEquals(listOf(1L, 1L, most), alice.notes().map { it.amountMsat }.sorted())
        }
}
