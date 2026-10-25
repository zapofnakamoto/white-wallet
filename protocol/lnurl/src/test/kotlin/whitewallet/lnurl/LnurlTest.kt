package whitewallet.lnurl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import whitewallet.testkit.Vectors
import whitewallet.testkit.obj
import whitewallet.testkit.objects
import whitewallet.testkit.str
import whitewallet.testkit.strOrNull

class LnurlTest {
    private val bech32 = Vectors.conformance("bech32")
    private val resolution = Vectors.conformance("input-resolution")

    @Test
    fun `bech32 encode and decode`() {
        for (c in bech32.objects("encode")) {
            assertEquals(c.str("lnurl"), Lnurl.encodeBech32(c.str("url")))
            assertEquals(c.str("url"), Lnurl.decodeBech32(c.str("lnurl")))
        }
        for (c in bech32.objects("decodeInvalid")) assertNull(Lnurl.decodeBech32(c.str("input")), c.str("why"))
        val ci = bech32.obj("caseInsensitive")
        assertEquals(ci.str("url"), Lnurl.decodeBech32(ci.str("lower")))
        assertEquals(ci.str("url"), Lnurl.decodeBech32(ci.str("upper")))
    }

    @Test
    fun `input resolution`() {
        for (c in resolution.objects("lnurl")) assertEquals(c.strOrNull("expect"), Lnurl.resolve(c.str("input")), c.str("input"))
    }

    @Test
    fun `mint addresses`() {
        // A mint address is a Lightning Address or an LNURL that resolves to a pay URL, never a raw note URL.
        for (c in resolution.objects("mint")) {
            val resolved = Lnurl.resolve(c.str("input"))?.takeIf { Lnurl.lightningAddressUsername(it) != null }
            assertEquals(c.strOrNull("expect"), resolved, c.str("input"))
        }
    }

    @Test
    fun `well-known paths`() {
        for (c in resolution.objects("mintAddressUrl")) assertEquals(c.strOrNull("expect"), Lnurl.withdrawUrlForPayUrl(c.str("payUrl")))
        for (c in resolution.objects("lightningAddressUsername")) assertEquals(c.strOrNull("expect"), Lnurl.lightningAddressUsername(c.str("payUrl")))
    }

    @Test
    fun `query building keeps existing parameters`() {
        assertEquals("https://m.example/w/cb?token=abc&k1=a&k1=b", Lnurl.withQuery("https://m.example/w/cb?token=abc", listOf("k1" to "a", "k1" to "b")))
        assertEquals("https://m.example/p?comment=a%20b", Lnurl.withQuery("https://m.example/p", listOf("comment" to "a b")))
        assertEquals(listOf("token" to "abc", "k1" to "a", "k1" to "b"), Lnurl.queryParams("https://m.example/w/cb?token=abc&k1=a&k1=b"))
    }

    @Test
    fun `withdraw responses are parsed strictly`() {
        val v = Vectors.conformance("withdraw-info")
        for (c in v.objects("accepted")) assertIs<LnurlResponse.WithdrawRequest>(LnurlResponse.parse(c.obj("body")), c.str("name"))
        val structural = setOf("wrong tag", "no callback", "no maxWithdrawable", "maxWithdrawable is a string", "negative maxWithdrawable",
            "fractional maxWithdrawable", "maxWithdrawable past 2^63", "minWithdrawable above maxWithdrawable")
        for (c in v.objects("rejected").filter { it.str("name") in structural }) {
            val parsed = LnurlResponse.parse(c.obj("body"))
            assert(parsed !is LnurlResponse.WithdrawRequest) { "${c.str("name")} should not parse" }
        }
    }

    @Test
    fun `error responses`() {
        val e = assertIs<LnurlResponse.Error>(LnurlResponse.parse("""{"status":"ERROR","reason":"pending"}"""))
        assertEquals("pending", e.reason)
        assertIs<LnurlResponse.Ok>(LnurlResponse.parse("""{"status":"OK"}"""))
        assertNull(LnurlResponse.parse("not json"))
    }
}
