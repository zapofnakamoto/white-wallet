package whitewallet.lnurlcash.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType

class RequestLogTest {
    private val secret = "ck1" + "q".repeat(150)

    private fun client(log: RequestLog) =
        LnurlcashClient(LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(log.interceptor()).build())

    @Test
    fun `requests and responses are logged with secrets redacted`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = """{"tag":"withdrawRequest","callback":"x","k1":"$secret","maxWithdrawable":1000,"preimage":"abcdef0123456789"}"""))
            server.start()
            val log = RequestLog()
            runCatching { client(log).get(server.url("/w?k1=$secret&p1=cp1public").toString()) }
            val entry = log.entries.value.single()
            assertEquals("GET", entry.method)
            assertEquals(200, entry.status)
            assertFalse(secret in entry.url, "spend in the URL is masked")
            assertTrue("k1=ck1qqq…(redacted)" in entry.url)
            assertTrue("p1=cp1public" in entry.url, "public values stay readable")
            val body = assertNotNull(entry.responseBody)
            assertFalse(secret in body, "spend echoed in the response is masked")
            assertFalse("abcdef0123456789" in body, "preimages are masked")
            assertTrue("\"maxWithdrawable\":1000" in body)
        }
    }

    @Test
    fun `transport failures are logged and still reported to the caller`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
            server.start()
            val log = RequestLog()
            val outcome = client(log).mutate(MutationType.Rotate, server.url("/w/cb?k1=$secret&p1=cp1x").toString())
            // One replay of a rotate is allowed: both attempts are logged, and nothing else is sent.
            assertIs<MutationOutcome.Ambiguous>(outcome)
            assertEquals(2, server.requestCount)
            val entries = log.entries.value
            assertEquals(2, entries.size)
            assertTrue(entries.all { it.status == null && it.error != null && it.responseBody == null })
        }
    }

    @Test
    fun `only the most recent entries are kept, newest first`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(5) { server.enqueue(MockResponse(body = """{"status":"OK","n":$it}""")) }
            server.start()
            val log = RequestLog(capacity = 3)
            repeat(5) { runCatching { client(log).get(server.url("/n$it").toString()) } }
            assertEquals(listOf("n4", "n3", "n2"), log.entries.value.map { it.url.substringAfterLast('/') })
            log.clear()
            assertTrue(log.entries.value.isEmpty())
        }
    }

    @Test
    fun `address proofs are redacted, the name and cx1 are not`() {
        val sig = "ab".repeat(64)
        assertEquals("https://m.example/p/alice?sig=ababab…(redacted)", Redaction.url("https://m.example/p/alice?sig=$sig"))
        assertEquals("https://m.example/p/alice?cx1=cx1qq&sig=ababab…(redacted)", Redaction.url("https://m.example/p/alice?cx1=cx1qq&sig=$sig"))
    }

    @Test
    fun `redaction leaves short or absent secrets alone`() {
        assertEquals("https://m.example/w?p=cp1abc", Redaction.url("https://m.example/w?p=cp1abc"))
        assertEquals("""{"k1":"…"}""", Redaction.body("""{"k1":"abc"}"""))
    }
}
