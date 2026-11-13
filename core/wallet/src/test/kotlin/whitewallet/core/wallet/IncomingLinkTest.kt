package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import whitewallet.lnurl.Lnurl

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
    fun `bech32 note links are claimed, also in uppercase and behind LIGHTNING`() {
        val bech32 = Lnurl.encodeBech32("https://mint.example/w?k1=" + "ab".repeat(32))
        assertEquals(IncomingLink.Claim(bech32), IncomingLink.classify(bech32))
        assertEquals(IncomingLink.Claim(bech32), IncomingLink.classify("LIGHTNING:$bech32"))
        assertEquals(IncomingLink.Claim(bech32.lowercase()), IncomingLink.classify("lightning:${bech32.lowercase()}"))
    }

    @Test
    fun `a bech32 link without a note opens in Claim, which asks its service whether it pays out or is paid`() {
        val pay = Lnurl.encodeBech32("https://mint.example/.well-known/lnurlp/alice")
        assertEquals(IncomingLink.Claim(pay, mayBePay = true), IncomingLink.classify(pay))
        assertEquals(IncomingLink.Claim(pay, mayBePay = true), IncomingLink.classify("LIGHTNING:$pay"))
    }

    @Test
    fun `withdraw links of other services are claimed - a hex k1, a short k1, or none`() {
        // A 64-hex k1 reads as a bearer note; Claim finds out whether its service answers for one.
        val hex = "lnurlw://lnbits.example/withdraw/api/v1/lnurl/abc?k1=" + "cd".repeat(32)
        assertEquals(IncomingLink.Claim(hex), IncomingLink.classify(hex))
        val short = "lnurlw://lnbits.example/withdraw/api/v1/lnurl/abc?k1=x7Yh2"
        assertEquals(IncomingLink.Claim(short), IncomingLink.classify(short))
        val plain = "lnurlw://lnbits.example/withdraw/api/v1/lnurl/abc"
        assertEquals(IncomingLink.Claim(plain), IncomingLink.classify("LIGHTNING:$plain"))
        assertEquals(IncomingLink.Claim("LNURLW://atm.example/w"), IncomingLink.classify("LNURLW://atm.example/w"))
    }

    @Test
    fun `anything else is ignored`() {
        assertNull(IncomingLink.classify("hello there"))
        assertNull(IncomingLink.classify(""))
        assertNull(IncomingLink.classify(null))
    }
}
