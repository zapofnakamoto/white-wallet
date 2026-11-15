package whitewallet.core.wallet

import java.util.concurrent.ConcurrentHashMap
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import whitewallet.lnurlcash.Cp1

/** A fake mint that answers each lookup by `p` from [lookups] ("Unknown note." otherwise), and every mutation with [callback]. */
internal class LookupMint : AutoCloseable {
    private val server = MockWebServer()
    val host
        get() = "localhost:${server.port}"

    val withdrawUrl
        get() = "http://$host/w"

    /** Answers by note key (hex). */
    val lookups = ConcurrentHashMap<String, String>()
    @Volatile var callback = error("pending")
    /** Runs as a mutation arrives, before its answer goes out. */
    @Volatile var onCallback: () -> Unit = {}
    @Volatile var callbacks = 0

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val base = "http://$host"
                    val url = request.url
                    return when (url.encodedPath) {
                        "/.well-known/lnurlp/mint" ->
                            MockResponse(
                                body =
                                    """{"tag":"payRequest","callback":"$base/p/cb","minSendable":1000,"maxSendable":100000000,"metadata":"[[\"text/plain\",\"fake\"]]","withdrawLink":"$base/w","commentAllowed":64}"""
                            )
                        "/.well-known/lnurlw/mint" -> MockResponse(body = withdrawRequest(base, 0, 1000))
                        "/w" -> {
                            val q = url.queryParameter("p")?.let { Cp1.decode(it)?.toHex() }
                            val answer = q?.let { lookups[it] } ?: error("Unknown note.")
                            MockResponse(body = answer.replace(BASE, base))
                        }
                        "/w/cb" -> {
                            callbacks++
                            onCallback()
                            MockResponse(body = callback)
                        }
                        else -> MockResponse(code = 404)
                    }
                }
            }
        server.start()
    }

    override fun close() = server.close()

    companion object {
        /** Stands for the mint's base URL in an answer, filled in as it goes out. */
        const val BASE = "{base}"
        const val PUBKEY = "034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"

        fun error(reason: String) = """{"status":"ERROR","reason":"$reason"}"""

        fun withdrawRequest(base: String, min: Long, max: Long) =
            """{"tag":"withdrawRequest","callback":"$base/w/cb","minWithdrawable":$min,"maxWithdrawable":$max,"mintPubkey":"$PUBKEY"}"""

        /** A lookup's answer for a note worth [valueMsat]. */
        fun note(valueMsat: Long) = withdrawRequest(BASE, valueMsat, valueMsat)
    }
}
