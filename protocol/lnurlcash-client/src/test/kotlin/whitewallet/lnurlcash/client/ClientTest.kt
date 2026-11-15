@file:OptIn(RevealsNoteSecret::class)

package whitewallet.lnurlcash.client

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType

class ClientTest {
    @Test
    fun `refuses an HTTP client that retries`() {
        assertFailsWith<IllegalArgumentException> { LnurlcashClient(OkHttpClient()) }
    }

    @Test
    fun `a request carrying a spend leaves only as a mutation`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                server.start()
                val client = LnurlcashClient()
                assertFailsWith<SpendRefused> { client.get(server.url("/w?k1=a").toString()) }
                assertFailsWith<SpendRefused> { client.get(server.url("/w?p=cp1x&K1=a").toString()) }
                assertEquals(0, server.requestCount, "nothing went out")
                client.mutate(MutationType.Rotate, server.url("/w/cb?k1=a&p1=cp1z").toString())
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `a spend an interceptor slips into a lookup doesn't leave`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                val leaky =
                    LnurlcashClient.defaultHttpClient()
                        .newBuilder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .url(chain.request().url.newBuilder().addQueryParameter("k1", "a").build())
                                    .build()
                            )
                        }
                        .build()
                assertFailsWith<SpendRefused> { LnurlcashClient(leaky).get(server.url("/w?p=cp1x").toString()) }
                assertEquals(0, server.requestCount)
            }
        }

    @Test
    fun `a melt is sent exactly once even when the answer is lost`() =
        runBlocking<Unit> {
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
    fun `a request carrying an invoice is a melt, never replayed whatever type it is sent as`() =
        runBlocking<Unit> {
            for (type in MutationType.entries) {
                for (param in listOf("pr", "PR")) {
                    MockWebServer().use { server ->
                        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
                        server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                        server.start()
                        val outcome = LnurlcashClient().mutate(type, server.url("/w/cb?k1=a&$param=lnbc1&amount=1000").toString())
                        assertIs<MutationOutcome.Ambiguous>(outcome, "$type $param")
                        assertEquals(1, server.requestCount, "$type $param")
                    }
                }
            }
        }

    @Test
    fun `a transfer whose answer is lost is replayed like a rotate`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
                server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                server.start()
                val outcome = LnurlcashClient().mutate(MutationType.Transfer, server.url("/p/cb?amount=1000&k1=a").toString())
                assertIs<MutationOutcome.Confirmed>(outcome)
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun `a melt answered 503 with Retry-After 0 reaches the mint once and is ambiguous`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
                server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                server.start()
                val outcome = LnurlcashClient().mutate(MutationType.Melt, server.url("/w/cb?k1=a&pr=lnbc1").toString())
                assertEquals(MutationOutcome.Ambiguous("HTTP 503"), outcome)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `a melt answered with a redirect or a challenge reaches the mint once and is ambiguous, whatever the client`() =
        runBlocking<Unit> {
            // A client of the caller's that follows redirects and answers challenges: the guards hold all the same.
            val following =
                OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .authenticator { _, response -> response.request.newBuilder().header("Authorization", "Basic YTpi").build() }
                    .proxyAuthenticator { _, response -> response.request.newBuilder().header("Proxy-Authorization", "Basic YTpi").build() }
                    .build()
            for (http in listOf(LnurlcashClient.defaultHttpClient(), following)) {
                for (answer in listOf(302, 307, 401, 407)) {
                    MockWebServer().use { server ->
                        server.start()
                        server.enqueue(
                            MockResponse.Builder()
                                .code(answer)
                                .addHeader("Location", server.url("/w/cb2?k1=a&pr=lnbc1"))
                                .addHeader("WWW-Authenticate", "Basic realm=\"mint\"")
                                .addHeader("Proxy-Authenticate", "Basic realm=\"mint\"")
                                .build()
                        )
                        server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                        val outcome = LnurlcashClient(http).mutate(MutationType.Melt, server.url("/w/cb?k1=a&pr=lnbc1").toString())
                        assertIs<MutationOutcome.Ambiguous>(outcome, "$answer")
                        assertEquals(1, server.requestCount, "$answer")
                    }
                }
            }
        }

    @Test
    fun `an interceptor that resends never sends a mutation twice`() =
        runBlocking<Unit> {
            val resending =
                LnurlcashClient.defaultHttpClient()
                    .newBuilder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request()).close()
                        chain.proceed(chain.request())
                    }
                    .build()
            MockWebServer().use { server ->
                server.enqueue(MockResponse(body = """{"status":"ERROR","reason":"rate limited"}"""))
                server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                server.start()
                val outcome = LnurlcashClient(resending).mutate(MutationType.Melt, server.url("/w/cb?k1=a&pr=lnbc1").toString())
                assertIs<MutationOutcome.Ambiguous>(outcome)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `a replay refused after an unanswered attempt stays ambiguous`() =
        runBlocking<Unit> {
            val refusals =
                listOf(
                    200 to """{"status":"ERROR","reason":"rate limited"}""",
                    200 to """{"status":"ERROR","reason":"pending"}""",
                    200 to """{"status":"ERROR","reason":"already in use"}""",
                    502 to """{"status":"ERROR","reason":"Bad Gateway"}""",
                )
            for ((code, body) in refusals) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
                    server.enqueue(MockResponse(code = code, body = body))
                    server.start()
                    val outcome = LnurlcashClient().mutate(MutationType.Rotate, server.url("/w/cb?k1=a&p1=cp1z").toString())
                    assertIs<MutationOutcome.Ambiguous>(outcome, body)
                    assertEquals(2, server.requestCount, body)
                }
            }
        }

    @Test
    fun `a refused replay says what refused it`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
                server.enqueue(MockResponse(body = """{"status":"ERROR","reason":"rate limited"}"""))
                server.start()
                val outcome = LnurlcashClient().mutate(MutationType.Rotate, server.url("/w/cb?k1=a&p1=cp1z").toString())
                assertEquals(MutationOutcome.Ambiguous("replay refused: rate limited"), outcome)
            }
        }

    @Test
    fun `a replay that lands confirms an unanswered attempt`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                val c = Certificates.sign("mint.example", 21_000, ByteVector32.One, PrivateKey(ByteVector32.One)).encode()
                server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
                server.enqueue(MockResponse(body = """{"status":"OK","c":"$c"}"""))
                server.start()
                val outcome = LnurlcashClient().mutate(MutationType.Rotate, server.url("/w/cb?k1=a&p1=cp1z").toString())
                assertEquals(c, assertIs<MutationOutcome.Confirmed>(outcome).certificate)
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun `a read-only request is retried once after a dropped connection`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
                server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                server.start()
                assertIs<whitewallet.lnurl.LnurlResponse.Ok>(LnurlcashClient().get(server.url("/w?p=cp1x").toString()))
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun `a read-only request is not retried more than once`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                repeat(3) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
                server.start()
                assertFailsWith<java.io.IOException> { LnurlcashClient().get(server.url("/w?p=cp1x").toString()) }
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun `a read-only request gets through when every pooled connection was closed by the server`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                // Three lookups at once leave three pooled connections, each of which the server closes once it answered (idle timeout).
                repeat(3) {
                    server.enqueue(
                        MockResponse.Builder()
                            .body("""{"status":"OK"}""")
                            .headersDelay(300, TimeUnit.MILLISECONDS)
                            .onResponseEnd(SocketEffect.ShutdownConnection)
                            .build()
                    )
                }
                server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                server.start()
                val client = LnurlcashClient()
                coroutineScope { List(3) { async { client.get(server.url("/w?p=cp1$it").toString()) } }.awaitAll() }
                assertEquals(3, (0 until 3).map { server.takeRequest().connectionIndex }.distinct().size, "three connections")

                assertIs<whitewallet.lnurl.LnurlResponse.Ok>(client.get(server.url("/w?p=cp1x").toString()))
            }
        }

    @Test
    fun `the retry closes the other pooled connections off the caller's thread`() =
        runBlocking<Unit> {
            // Closing a TLS socket writes to the network: on Android's main thread that is a crash (NetworkOnMainThreadException).
            val closers = java.util.Collections.synchronizedList(mutableListOf<String>())
            val sockets =
                object : javax.net.SocketFactory() {
                    private fun socket() =
                        object : java.net.Socket() {
                            override fun close() {
                                closers += Thread.currentThread().name
                                super.close()
                            }
                        }

                    override fun createSocket() = socket()

                    override fun createSocket(host: String, port: Int) = socket().apply { connect(java.net.InetSocketAddress(host, port)) }

                    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) =
                        createSocket(host, port)

                    override fun createSocket(host: java.net.InetAddress, port: Int) =
                        socket().apply { connect(java.net.InetSocketAddress(host, port)) }

                    override fun createSocket(
                        address: java.net.InetAddress,
                        port: Int,
                        localAddress: java.net.InetAddress,
                        localPort: Int,
                    ) = createSocket(address, port)
                }
            val io = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "client-io") }
            try {
                MockWebServer().use { other ->
                    MockWebServer().use { server ->
                        other.enqueue(MockResponse(body = """{"status":"OK"}"""))
                        other.start()
                        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
                        server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                        server.start()
                        val client =
                            LnurlcashClient(
                                http = LnurlcashClient.defaultHttpClient().newBuilder().socketFactory(sockets).build(),
                                io = io.asCoroutineDispatcher(),
                            )
                        // An idle connection to another mint stays in the pool, until the retry empties it.
                        client.get(other.url("/w?p=cp1o").toString())
                        val caller = Thread.currentThread().name
                        closers.clear()

                        assertIs<whitewallet.lnurl.LnurlResponse.Ok>(client.get(server.url("/w?p=cp1x").toString()))
                        assertTrue(closers.isNotEmpty(), "the retry closed the pooled connection")
                        assertTrue(caller !in closers, "closed on $closers, not on the caller's thread $caller")
                    }
                }
            } finally {
                io.shutdown()
            }
        }

    @Test
    fun `mutations never reuse an idle pooled connection`() =
        runBlocking<Unit> {
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
    fun `registration posts a form to the address it claims`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.enqueue(MockResponse(body = """{"status":"OK"}"""))
                server.enqueue(MockResponse(body = """{"status":"OK","pr":"lnbcrt210n1fee"}"""))
                server.enqueue(MockResponse(body = """{"status":"ERROR","reason":"already registered"}"""))
                server.enqueue(MockResponse(body = """{"status":"ERROR","reason":"cx1 already used"}"""))
                server.enqueue(MockResponse(body = """{"status":"ERROR","reason":"unknown user"}"""))
                server.start()
                val host = "${server.hostName}:${server.port}"
                val client = LnurlcashClient()
                assertEquals(RegistrationOutcome.Registered, client.register(host, "Alice", "cx1abc", "00"))
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/.well-known/lnurlp/alice", request.target)
                assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"]?.substringBefore(';'))
                assertEquals("cx1=cx1abc&sig=00", request.body?.utf8())

                assertEquals(RegistrationOutcome.AwaitingFee("lnbcrt210n1fee"), client.register(host, "bob", "cx1abc", "00"))
                server.takeRequest()
                val taken = assertIs<RegistrationOutcome.Rejected>(client.register(host, "alice", "cx1abc", "00"))
                assertEquals(RegistrationRejection.AlreadyRegistered, taken.kind)
                server.takeRequest()

                val used = assertIs<RegistrationOutcome.Rejected>(client.updateRegistration(host, "alice", "cx1new", "01", "02"))
                assertEquals(RegistrationRejection.Cx1AlreadyUsed, used.kind)
                val put = server.takeRequest()
                assertEquals("PUT", put.method)
                assertEquals("cx1=cx1new&sig=01&newsig=02", put.body?.utf8())

                val unknown = assertIs<RegistrationOutcome.Rejected>(client.unregister(host, "alice", "03"))
                assertEquals(RegistrationRejection.UnknownUser, unknown.kind)
                val delete = server.takeRequest()
                assertEquals("DELETE", delete.method)
                assertEquals("sig=03", delete.body?.utf8())
            }
        }

    @Test
    fun `a registration answered with anything but a mint's OK or ERROR is ambiguous`() =
        runBlocking<Unit> {
            val answers =
                listOf(
                    200 to "<html><body>Welcome to the hotel wifi</body></html>",
                    200 to
                        """{"tag":"payRequest","callback":"https://mint.example/cb","minSendable":1000,"maxSendable":2000,"metadata":"[]"}""",
                    200 to """{"status":"ok"}""",
                    200 to """{"status":"Error","reason":"already registered"}""",
                    204 to "",
                    404 to """{"status":"ERROR","reason":"unknown user"}""",
                    502 to """{"status":"ERROR","reason":"already registered"}""",
                    500 to """{"status":"OK"}""",
                )
            for ((code, body) in answers) {
                MockWebServer().use { server ->
                    repeat(3) { server.enqueue(MockResponse(code = code, body = body)) }
                    server.start()
                    val host = "${server.hostName}:${server.port}"
                    val client = LnurlcashClient()
                    for (outcome in
                        listOf(
                            client.register(host, "alice", "cx1abc", "00"),
                            client.updateRegistration(host, "alice", "cx1new", "01", "02"),
                            client.unregister(host, "alice", "03"),
                        )) {
                        assertIs<RegistrationOutcome.Ambiguous>(outcome, "$code $body")
                    }
                    assertEquals(3, server.requestCount, "never retried")
                }
            }
        }
}
