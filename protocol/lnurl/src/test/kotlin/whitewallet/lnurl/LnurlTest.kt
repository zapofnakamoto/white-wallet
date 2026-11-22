package whitewallet.lnurl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import whitewallet.testkit.Vectors
import whitewallet.testkit.arr
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
    fun `a link's bech32 form wraps the URL it resolves to, in uppercase`() {
        val link = Lnurl.bech32Link("lnurlw://mint.example/w?k1=ck1abc&c=cs1xyz")!!
        assertEquals(link.uppercase(), link)
        assertTrue(link.startsWith("LNURL1"), link)
        assertEquals("https://mint.example/w?k1=ck1abc&c=cs1xyz", Lnurl.decodeBech32(link))
        assertEquals("http://127.0.0.1:3338/w?k1=x", Lnurl.decodeBech32(Lnurl.bech32Link("lnurlw://127.0.0.1:3338/w?k1=x")!!))
        assertNull(Lnurl.bech32Link("hello"))
    }

    @Test
    fun `input resolution`() {
        for (c in resolution.objects("lnurl")) assertEquals(c.strOrNull("expect"), Lnurl.resolve(c.str("input")), c.str("input"))
    }

    @Test
    fun `url admission`() {
        val admission = Vectors.conformance("url-admission")
        for (url in admission.arr("allowed").map { it.jsonPrimitive.content }) assertEquals(url, Lnurl.resolve(url), url)
        for (c in admission.objects("rejected")) assertNull(Lnurl.resolve(c.str("url")), c.str("why"))
    }

    @Test
    fun `cleartext only to a real loopback address`() {
        val lookalikes =
            listOf(
                "127.evil.com",
                "127.0.0.1.evil.com",
                "0127.0.0.1",
                "127.0.0.01",
                "127.256.0.1",
                "127.0.0",
                "127.1",
                "128.0.0.1",
                "localhost.evil.com",
                "0.0.0.0.evil.com",
            )
        for (host in lookalikes) {
            assertNull(Lnurl.resolve("http://$host/w"), host)
            assertNotEquals("http://$host/w", Lnurl.resolve("lnurlw://$host/w"), host)
            assertEquals("https://$host/.well-known/lnurlp/alice", Lnurl.lightningAddressUrl("alice@$host"), host)
        }
        for (host in listOf("localhost", "127.0.0.1", "127.255.255.255", "127.10.0.250", "0.0.0.0", "[::1]", "mint.onion")) {
            assertEquals("http://$host:8000/w", Lnurl.resolve("http://$host:8000/w"), host)
        }
        for (host in listOf("localhost", "127.0.0.1", "127.10.0.250", "0.0.0.0", "[::1]", "mint.onion")) {
            assertEquals("http://$host/w", Lnurl.resolve("lnurlw://$host/w"), host)
            assertEquals("http://$host:8000/w", Lnurl.resolve("lnurlw://$host:8000/w"), host)
        }
        assertEquals("https://[2001:db8::1]:8000/w", Lnurl.resolve("lnurlw://[2001:db8::1]:8000/w"))
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
        for (c in resolution.objects("lightningAddressUsername")) assertEquals(
            c.strOrNull("expect"),
            Lnurl.lightningAddressUsername(c.str("payUrl")),
        )
    }

    @Test
    fun `query building keeps existing parameters`() {
        assertEquals(
            "https://m.example/w/cb?token=abc&k1=a&k1=b",
            Lnurl.withQuery("https://m.example/w/cb?token=abc", listOf("k1" to "a", "k1" to "b")),
        )
        assertEquals("https://m.example/p?comment=a%20b", Lnurl.withQuery("https://m.example/p", listOf("comment" to "a b")))
        assertEquals(listOf("token" to "abc", "k1" to "a", "k1" to "b"), Lnurl.queryParams("https://m.example/w/cb?token=abc&k1=a&k1=b"))
    }

    @Test
    fun `withdraw responses are parsed strictly`() {
        val v = Vectors.conformance("withdraw-info")
        for (c in v.objects("accepted")) assertIs<LnurlResponse.WithdrawRequest>(LnurlResponse.parse(c.obj("body")), c.str("name"))
        val structural =
            setOf(
                "wrong tag",
                "no callback",
                "no maxWithdrawable",
                "maxWithdrawable is a string",
                "negative maxWithdrawable",
                "fractional maxWithdrawable",
                "maxWithdrawable past 2^63",
                "minWithdrawable above maxWithdrawable",
            )
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
