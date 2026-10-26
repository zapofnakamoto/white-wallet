package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The local regtest stack's faucet (dev/regtest/faucet.py, reached over `adb reverse`): pays an address or invoice
 * from the stack's own Lightning node, so a debug build can receive without a terminal. [host] is null in release
 * builds, where there is no faucet.
 */
class RegtestFaucet(
    val host: String?,
    private val http: OkHttpClient = OkHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Pays [target] (a Lightning Address on a local mint, or an invoice) [sats]; returns what arrived. */
    suspend fun pay(target: String, sats: Long): Long = withContext(io) {
        val host = host ?: throw IOException("no regtest faucet in this build")
        val url = HttpUrl.Builder().scheme("http").host(host.substringBefore(':')).port(host.substringAfter(':').toInt())
            .addPathSegment("pay").addQueryParameter("target", target).addQueryParameter("sats", sats.toString()).build()
        http.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()).execute().use { response ->
            val body = runCatching { Json.parseToJsonElement(response.body.string()).jsonObject }.getOrNull()
            if (!response.isSuccessful || body == null) {
                throw IOException(body?.get("error")?.jsonPrimitive?.content ?: "the faucet answered HTTP ${response.code}")
            }
            body["paid_sats"]?.jsonPrimitive?.longOrNull ?: sats
        }
    }
}
