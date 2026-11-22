package whitewallet.feature.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLinkFormatTest {
    private val link = "lnurlw://mint.example/w?k1=ck1abc&amount=1000&c=cs1xyz"

    @Test
    fun lnurlwHandsTheLinkOverAsItIs() {
        assertEquals(link, NoteLinkFormat.Lnurlw.qr(link))
        assertEquals(link, NoteLinkFormat.Lnurlw.text(link))
    }

    @Test
    fun theLnurlCodeIsAllInTheQrCodesCompactAlphabet() {
        val qr = NoteLinkFormat.Lnurl.qr(link)
        assertTrue(qr, qr.matches(Regex("^LIGHTNING:LNURL1[0-9A-Z]+$")))
        assertTrue(qr, qr.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })
    }

    @Test
    fun theLnurlTextIsTheSameLinkBehindLowercaseLightning() {
        assertEquals("lightning:" + NoteLinkFormat.Lnurl.qr(link).removePrefix("LIGHTNING:"), NoteLinkFormat.Lnurl.text(link))
    }
}
