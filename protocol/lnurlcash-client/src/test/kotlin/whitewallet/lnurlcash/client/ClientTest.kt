package whitewallet.lnurlcash.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.RejectionKind

class ClientTest {
    @Test
    fun `refuses an HTTP client that retries`() {
        assertFailsWith<IllegalArgumentException> { LnurlcashClient(OkHttpClient()) }
    }

    @Test
    fun `a melt is sent exactly once even when the answer is lost`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
            server.enqueue(MockResponse(body = """{"status":"OK"}"""))
            server.start()
            val outcome = LnurlcashClient().mutate(MutationType.Melt, server.url("/w/cb?k1=a&pr=lnbc1").toString())
            assertIs<MutationOutcome.Ambiguous>(outcome)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a read-only request is retried once after a dropped connection`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
            server.enqueue(MockResponse(body = """{"status":"OK"}"""))
            server.start()
            assertIs<whitewallet.lnurl.LnurlResponse.Ok>(LnurlcashClient().get(server.url("/w?p=cp1x").toString()))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a read-only request is not retried more than once`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
            server.start()
            assertFailsWith<java.io.IOException> { LnurlcashClient().get(server.url("/w?p=cp1x").toString()) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `mutations never reuse an idle pooled connection`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse(body = """{"status":"OK","c":"cs1x"}""")) }
            server.start()
            val client = LnurlcashClient()
            client.get(server.url("/w?p=cp1x").toString())
            client.get(server.url("/w?p=cp1y").toString())
            client.mutate(MutationType.Rotate, server.url("/w/cb?k1=a&p1=cp1z").toString())
            val (first, second, mutation) = List(3) { server.takeRequest() }
            assertEquals(first.connectionIndex, second.connectionIndex, "read-only requests share the pooled connection")
            assertTrue(mutation.connectionIndex != first.connectionIndex, "the mutation opened its own connection")
        }
    }

    @Test
    fun `registration posts cx1 and signature`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = """{"status":"OK"}"""))
            server.enqueue(MockResponse(body = """{"status":"ERROR","reason":"username taken"}"""))
            server.start()
            val host = "${server.hostName}:${server.port}"
            assertEquals(RegistrationOutcome.Registered, LnurlcashClient().register(host, "alice", "cx1abc", "00"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/p/alice?cx1=cx1abc&sig=00", request.target)
            val taken = assertIs<RegistrationOutcome.Rejected>(LnurlcashClient().register(host, "alice", "cx1abc", "00"))
            assertEquals(RejectionKind.Other, taken.kind)
        }
    }
}
