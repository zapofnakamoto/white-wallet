package whitewallet.lnurl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Examples from BOLT-11 itself. */
class Bolt11Test {
    private val donation =
        "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
    private val coffee =
        "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"
    private val hashedDescription =
        "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs9qrsgq7ea976txfraylvgzuxs8kgcw23ezlrszfnh8r6qtfpr6cxga50aj6txm9rxrydzd06dfeawfk6swupvz4erwnyutnjq7x39ymw6j38gp7ynn44"

    @Test
    fun `amount, description and payment hash`() {
        val invoice = assertNotNull(Bolt11.decode(coffee))
        assertEquals("bc", invoice.currency)
        assertEquals(250_000_000, invoice.amountMsat, "2500u = 250,000 sat")
        assertEquals("1 cup coffee", invoice.description)
        assertEquals("0001020304050607080900010203040506070809000102030405060708090102", invoice.paymentHashHex)
        assertEquals(1496314658, invoice.timestampSeconds)
        assertEquals(60, invoice.expirySeconds)
        assertTrue(invoice.isExpired(System.currentTimeMillis()))
    }

    @Test
    fun `an invoice without an amount, and one with a description hash`() {
        val open = assertNotNull(Bolt11.decode("lightning:" + donation.uppercase()))
        assertNull(open.amountMsat)
        assertEquals("Please consider supporting this project", open.description)
        assertEquals(3600, open.expirySeconds, "the default")

        val hashed = assertNotNull(Bolt11.decode(hashedDescription))
        assertEquals(2_000_000_000, hashed.amountMsat)
        assertNull(hashed.description)
        assertEquals("3925b6f67e2c340036ed12093dd44e0368df1b6ea26c53dbe4811f58fd5db8c1", hashed.descriptionHashHex)
    }

    @Test
    fun `garbage and a broken checksum are not invoices`() {
        assertNull(Bolt11.decode("hello"))
        assertNull(Bolt11.decode(coffee.dropLast(1) + "q"))
        assertNull(Bolt11.decode("lnurl1dp68gurn8ghj7um9wfmxjcm99e3k7mf0v9cxj0m385ekvcenxc6r2c35xvukxefcv5mkvv34x5ekzd3ev56nyd3hxqurzepexejxxepnxscrvwfnv9nxzcn9xq6xyefhvgcxxcmyxymnserxfq5fns"))
    }
}
