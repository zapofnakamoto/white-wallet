package whitewallet.lnurlcash.client

import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class NetworkConditionsTest {
    private val ok = """{"tag":"payRequest","callback":"x","minSendable":1,"maxSendable":2,"metadata":"[]"}"""

    private fun client(conditions: NetworkConditions, log: RequestLog = RequestLog()) = LnurlcashClient(
        LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(log.interceptor()).addInterceptor(conditions.interceptor()).build(),
    )

    @Test
    fun `a slow connection waits before each request`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(body = ok))
            server.start()
            val slept = mutableListOf<Long>()
            val conditions = NetworkConditions(NetworkConditions.Profile.Slow, sleep = { slept += it })
            client(conditions).get(server.url("/p").toString())
            assertEquals(listOf(200L), slept)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a dropped request never reaches the mint and is logged as failed`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            // Every request dropped: the random's draws are below the drop rate.
            val always = object : Random() {
                override fun nextBits(bitCount: Int) = 0
            }
            val log = RequestLog()
            val conditions = NetworkConditions(NetworkConditions.Profile.Flaky, random = always, sleep = {})
            assertFailsWith<IOException> { client(conditions, log).get(server.url("/p").toString()) }
            assertEquals(0, server.requestCount, "nothing was sent")
            assertTrue(log.entries.value.all { it.error == "simulated: no connection" }, "${log.entries.value}")
        }
    }

    @Test
    fun `normal changes nothing, and a profile set later applies to the next request`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse(body = ok)) }
            server.start()
            val slept = mutableListOf<Long>()
            val conditions = NetworkConditions(sleep = { slept += it })
            client(conditions).get(server.url("/p").toString())
            assertEquals(emptyList(), slept)
            conditions.profile = NetworkConditions.Profile.VerySlow
            client(conditions).get(server.url("/p").toString())
            assertEquals(listOf(6_000L), slept)
        }
    }
}
