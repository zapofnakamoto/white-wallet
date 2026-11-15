package whitewallet.lnurlcash.client

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Lets each request reach the wire once: a second attempt, by whatever follow-up path (a 421 on a coalesced HTTP/2 connection, which no
 * setting gates, an interceptor that resends, whatever a later OkHttp adds), fails before it is written. [LnurlcashClient] tags every
 * request it sends with a fresh [Attempt]; a deliberate replay is a new attempt.
 */
internal object SentOnce : Interceptor {
    class Attempt {
        val sent = AtomicBoolean()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val attempt = chain.request().tag(Attempt::class.java) ?: throw IOException("not sent: a request without its attempt tag")
        if (!attempt.sent.compareAndSet(false, true)) throw IOException("not resent: a request goes out once")
        return chain.proceed(chain.request())
    }
}

/** A 503 with `Retry-After: 0` makes OkHttp resend the request at once, whatever the settings: it comes back without the header. */
internal object NoImmediateRetryAfter : Interceptor {
    private const val UNAVAILABLE = 503

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code != UNAVAILABLE || response.header("Retry-After") == null) return response
        return response.newBuilder().removeHeader("Retry-After").build()
    }
}
