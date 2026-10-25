package whitewallet.lnurlcash.client

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Interceptor
import okhttp3.Response

/** One request to a service and what came back. Spends and preimages are already redacted. */
public data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val method: String,
    val url: String,
    /** HTTP status, or null if no response was read. */
    val status: Int?,
    val durationMillis: Long,
    val responseBody: String?,
    /** Transport failure (connection reset, timeout, ...), if any. */
    val error: String?,
)

/**
 * In-memory log of the most recent requests, newest first. Nothing is written to disk, and entries are redacted
 * before they are stored (see [Redaction]), so the log never holds a bearer secret.
 */
public class RequestLog(private val capacity: Int = 200, private val now: () -> Long = System::currentTimeMillis) {
    private val ids = AtomicLong()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    public val entries: StateFlow<List<LogEntry>> = _entries

    public fun clear() {
        _entries.value = emptyList()
    }

    internal fun record(method: String, url: String, status: Int?, durationMillis: Long, body: String?, error: String?) {
        val entry = LogEntry(ids.incrementAndGet(), now(), method, Redaction.url(url), status, durationMillis, body?.let(Redaction::body), error)
        _entries.update { (listOf(entry) + it).take(capacity) }
    }

    /** An OkHttp interceptor that records every request through [log]. It never retries or alters a request. */
    public fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val started = System.nanoTime()
        val elapsed = { (System.nanoTime() - started) / 1_000_000 }
        val response: Response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            record(request.method, request.url.toString(), null, elapsed(), null, e.message ?: e.javaClass.simpleName)
            throw e
        }
        val body = runCatching { response.peekBody(MAX_BODY_BYTES).string() }.getOrNull()
        record(request.method, request.url.toString(), response.code, elapsed(), body, null)
        response
    }

    private companion object {
        const val MAX_BODY_BYTES = 64L * 1024
    }
}

/**
 * Masks bearer secrets: `k1` spends in URLs and JSON, and payment preimages. Also address proofs (`sig`): an unregister
 * proof stays valid for its name, so a leaked one could free the name later. Everything else is public.
 */
public object Redaction {
    private const val KEEP = 6
    private val QUERY_K1 = Regex("([?&](?:k1|sig)=)([^&#]+)")
    private val JSON_SECRET = Regex("(\"(?:k1|preimage)\"\\s*:\\s*\")([^\"]*)(\")")

    public fun url(url: String): String = QUERY_K1.replace(url) { m -> m.groupValues[1] + mask(m.groupValues[2]) }

    public fun body(body: String): String = JSON_SECRET.replace(body) { m -> m.groupValues[1] + mask(m.groupValues[2]) + m.groupValues[3] }

    private fun mask(secret: String): String = if (secret.length <= KEEP) "…" else secret.take(KEEP) + "…(redacted)"
}
