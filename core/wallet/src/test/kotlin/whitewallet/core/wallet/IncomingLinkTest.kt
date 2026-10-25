package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IncomingLinkTest {
    private val note = "lnurlw://mint.example/w?k1=" + "ab".repeat(32)

    @Test
    fun `note links are claimed, with or without lightning prefix`() {
        assertEquals(IncomingLink.Claim(note), IncomingLink.classify(note))
        assertEquals(IncomingLink.Claim(note), IncomingLink.classify("lightning:$note"))
    }

    @Test
    fun `invoices, addresses and pay links are paid`() {
        val invoice = TestInvoices.invoice(1_000_000, "x", null)
        assertEquals(IncomingLink.Pay(invoice), IncomingLink.classify("LIGHTNING:$invoice"))
        assertEquals(IncomingLink.Pay(invoice), IncomingLink.classify("bitcoin:bcrt1qxyz?amount=0.001&lightning=$invoice"))
        assertIs<IncomingLink.Pay>(IncomingLink.classify("alice@mint.example"))
        assertIs<IncomingLink.Pay>(IncomingLink.classify("lnurlp://mint.example/.well-known/lnurlp/alice"))
    }

    @Test
    fun `anything else is ignored`() {
        assertNull(IncomingLink.classify("hello there"))
        assertNull(IncomingLink.classify(""))
        assertNull(IncomingLink.classify(null))
    }
}
