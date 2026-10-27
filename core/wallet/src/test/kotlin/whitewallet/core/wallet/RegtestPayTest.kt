package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.lnurl.Bolt11

/** Paying invoices and pay links from the wallet's notes, on the real mint and real (regtest) Lightning. */
class RegtestPayTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    private suspend fun Regtest.Phone.ready(input: String) = assertIs<PayPreview.Ready>(wallet.previewPayment(input))
    private suspend fun Regtest.Phone.sent() = wallet.activity.first().filter { it.kind == ActivityKind.Sent }

    @Test
    fun `an invoice is paid from a note worth exactly its amount, at no fee`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        assertEquals(1_999_000, alice.balance())
        val preview = alice.ready(Regtest.invoice(1999))
        assertEquals(PayQuote.Ok(1_999_000, 0), alice.wallet.quotePayment(preview, 1_999_000))
        assertEquals(PayResult.Paid(1_999_000, 0), alice.wallet.pay(preview, 1_999_000))
        assertEquals(0, alice.balance())
        assertEquals(listOf(ActivityStatus.Done to null), alice.sent().map { it.status to it.feeMsat })
    }

    @Test
    fun `a bigger note is split first, and the split fee is shown and charged`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(5000)
        val preview = alice.ready(Regtest.invoice(1000))
        assertEquals(PayQuote.Ok(1_000_000, 1_000), alice.wallet.quotePayment(preview, 1_000_000))
        assertEquals(PayResult.Paid(1_000_000, 1_000), alice.wallet.pay(preview, 1_000_000))
        assertEquals(4_999_000 - 1_000_000 - 1_000, alice.balance(), "the mint's 1 sat split fee comes out of the change")
        assertEquals(1_000, alice.sent().single().feeMsat)
    }

    @Test
    fun `notes are merged and split for an amount no single note covers`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(3000, 2000)
        val start = alice.balance()
        val preview = alice.ready(Regtest.invoice(4000))
        val quote = assertIs<PayQuote.Ok>(alice.wallet.quotePayment(preview, 4_000_000))
        val paid = assertIs<PayResult.Paid>(alice.wallet.pay(preview, 4_000_000))
        assertEquals(quote.feeMsat, paid.feeMsat, "charged what the quote said")
        assertTrue(alice.balance() >= start - 4_000_000 - paid.feeMsat, "the change, and any merge refund, stay")
        assertEquals(1, alice.notes().size, "one note of change")
    }

    @Test
    fun `an amount the notes can't make exactly is refused before anything is split`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        // 1998 sats from the 1999 sat note leaves no change after the 1 sat split fee.
        val preview = alice.ready(Regtest.invoice(1998))
        assertIs<PayQuote.Unreachable>(alice.wallet.quotePayment(preview, 1_998_000))
        assertEquals(PayResult.Failed(PayProblem.NotExact), alice.wallet.pay(preview, 1_998_000))
        assertEquals(listOf(1_999_000L), alice.notes().map { it.amountMsat }, "nothing touched")
        assertEquals(emptyList(), alice.sent())
    }

    @Test
    fun `a payment that fails on Lightning gives the money back`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val hold = Regtest.holdInvoice(1999)
        hold.cancel()
        assertEquals(PayResult.Failed(PayProblem.PaymentFailed), alice.wallet.pay(alice.ready(hold.invoice), 1_999_000))
        alice.wallet.refresh()
        assertEquals(1_999_000, alice.balance())
        assertEquals(ActivityStatus.Failed, alice.sent().single().status)
    }

    @Test
    fun `a payment still in flight is pending, out of the balance, and settles on a later refresh`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.wallet.payWaitMillis = 3_000
        alice.fund(2000)
        val hold = Regtest.holdInvoice(1999)
        assertEquals(PayResult.Pending(1_999_000), alice.wallet.pay(alice.ready(hold.invoice), 1_999_000))
        assertEquals("ACCEPTED", hold.state(), "the payment is held at the payee")
        assertEquals(0, alice.balance(), "the note is being paid")
        assertEquals(NoteState.Paying, alice.store.allNotes().single().state)
        alice.wallet.refresh()
        assertEquals(ActivityStatus.Pending, alice.sent().single().status, "still in flight")

        hold.settle()
        alice.refreshUntil { alice.sent().single().status != ActivityStatus.Pending }
        assertEquals(ActivityStatus.Done, alice.sent().single().status)
        assertEquals(NoteState.Spent, alice.store.allNotes().single().state)
        assertEquals(0, alice.balance())
    }

    @Test
    fun `a payment in flight that fails later gives the money back on a refresh`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.wallet.payWaitMillis = 3_000
        alice.fund(2000)
        val hold = Regtest.holdInvoice(1999)
        assertEquals(PayResult.Pending(1_999_000), alice.wallet.pay(alice.ready(hold.invoice), 1_999_000))
        hold.cancel()
        alice.refreshUntil { alice.sent().single().status != ActivityStatus.Pending }
        assertEquals(ActivityStatus.Failed, alice.sent().single().status)
        assertEquals(1_999_000, alice.balance())
    }

    @Test
    fun `a Lightning Address elsewhere gets an invoice for the chosen amount, with the comment, paid over Lightning`() = runBlocking<Unit> {
        val carol = LightningAddressPayee()
        val alice = Regtest.phone(client = Regtest.client(carol))
        alice.fund(5000)
        val preview = alice.ready(carol.address)
        assertEquals(false, preview.internal)
        assertEquals("Pay Carol", preview.description)
        assertEquals(140, preview.commentMaxLength)
        val paid = assertIs<PayResult.Paid>(alice.wallet.pay(preview, 1_000_000, "thanks"))
        assertEquals("thanks", carol.lastComment)
        assertTrue(Regtest.settled(carol.lastInvoice!!), "user-lnd was paid")
        assertEquals(carol.address, alice.sent().single().counterparty)
        assertEquals(4_999_000 - 1_000_000 - paid.feeMsat, alice.balance())
    }

    @Test
    fun `an invoice for another amount than asked is refused before anything is split`() = runBlocking<Unit> {
        // A real invoice, but for 1 sat more than the wallet asked the pay link for.
        val carol = LightningAddressPayee { amount, metadata -> Regtest.invoice(amount + 1_000, Payer.sha256Hex(metadata)) }
        val alice = Regtest.phone(client = Regtest.client(carol))
        alice.fund(5000)
        assertEquals(PayResult.Failed(PayProblem.InvoiceMismatch), alice.wallet.pay(alice.ready(carol.address), 1_000_000))
        assertEquals(listOf(4_999_000L), alice.notes().map { it.amountMsat }, "no split happened")
        assertEquals(emptyList(), alice.sent())
        assertTrue(!Regtest.settled(carol.lastInvoice!!))
    }

    @Test
    fun `an invoice without a description hash, or with another one, is paid`() = runBlocking<Unit> {
        // LUD-06 no longer asks the wallet to check the invoice's description hash, only its amount.
        var hash: String? = null
        val carol = LightningAddressPayee { amount, _ -> Regtest.invoice(amount, hash) }
        val alice = Regtest.phone(client = Regtest.client(carol))
        alice.fund(5000)
        for (h in listOf(null, Payer.sha256Hex("something else"))) {
            hash = h
            assertIs<PayResult.Paid>(alice.wallet.pay(alice.ready(carol.address), 1_000_000))
            assertEquals(h, Bolt11.decode(carol.lastInvoice!!)?.descriptionHashHex)
            assertTrue(Regtest.settled(carol.lastInvoice!!))
        }
    }

    @Test
    fun `an address on another mint is paid over Lightning`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(5000)
        val carol = Regtest.phone(host = HOST2)
        val preview = alice.ready(carol.address)
        assertEquals(false, preview.internal, "Alice has no notes on Carol's mint")
        val paid = assertIs<PayResult.Paid>(alice.wallet.pay(preview, 1_000_000))
        assertEquals(4_999_000 - 1_000_000 - paid.feeMsat, alice.balance())
        assertEquals(1_000_000 - 2_000, carol.wallet.refresh().receivedMsat, "less the second mint's 2 sat fee")
    }

    @Test
    fun `the account's own address, and a name nobody registered, are refused before anything is spent`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        assertEquals(PayPreview.Invalid(PayProblem.OwnAddress), alice.wallet.previewPayment(alice.address))
        assertEquals(PayPreview.Invalid(PayProblem.PayeeRefused), alice.wallet.previewPayment("${Regtest.name("nobody")}@${Regtest.HOST}"))
        assertEquals(listOf(1_999_000L), alice.notes().map { it.amountMsat })
        assertEquals(emptyList(), alice.sent())
    }
}
