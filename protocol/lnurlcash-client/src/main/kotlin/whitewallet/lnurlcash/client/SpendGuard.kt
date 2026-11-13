package whitewallet.lnurlcash.client

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Refuses every request that carries a note's spend (`k1`, in the query or the body) unless [LnurlcashClient.mutate] (or
 * [LnurlcashClient.withdrawLink]) sent it. Installed as the client's last network interceptor, so nothing added before it can slip a spend
 * out. A refusal throws [SpendRefused], which isn't an I/O error: nothing retries it.
 */
internal object SpendGuard : Interceptor {
    /** The tag only [LnurlcashClient.mutate] and [LnurlcashClient.withdrawLink] put on their requests. */
    object Mutation

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(Mutation::class.java) == null && carriesSpend(request)) {
            throw SpendRefused("${request.method} ${request.url.host}${request.url.encodedPath}")
        }
        return chain.proceed(request)
    }

    private fun carriesSpend(request: okhttp3.Request): Boolean {
        if (request.url.queryParameterNames.any { it.equals("k1", ignoreCase = true) }) return true
        val body = request.body ?: return false
        val text = Buffer().also(body::writeTo).readUtf8()
        return Regex("""(^|[&?"\s])k1["\s]*[=:]""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}

/** A request carried a note's spend outside a mutation, and wasn't sent. */
public class SpendRefused(request: String) :
    IllegalStateException("not sent: $request carries a note's spend, which only goes out with a mutation")
