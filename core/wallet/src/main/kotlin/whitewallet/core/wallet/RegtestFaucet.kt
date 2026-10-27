package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The local regtest stack's faucet (dev/regtest/faucet.py, reached over `adb reverse`): pays this wallet, or makes an
 * invoice for it to pay, from the stack's own Lightning nodes, so receiving and sending can be tried without a
 * terminal. [host] is null in release builds, where there is no faucet.
 */
class RegtestFaucet(
    val host: String?,
    developerMode: Flow<Boolean> = flowOf(false),
    homeHost: Flow<String?> = flowOf(null),
    localDevMints: List<String> = emptyList(),
    private val http: OkHttpClient = OkHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Whether to offer it: a debug build (it has a host), developer mode, and an account on a local regtest mint. */
    val available: Flow<Boolean> = combine(developerMode, homeHost) { developer, home ->
        developer && host != null && home in localDevMints
    }

    /** Pays [target] (a Lightning address on a local mint, or an invoice) [sats]; returns what arrived. */
    suspend fun pay(target: String, sats: Long): Long =
        call("pay", "target" to target, "sats" to sats.toString())["paid_sats"]?.jsonPrimitive?.longOrNull ?: sats

    /** A fresh invoice for [sats] that the account on [home] (a local mint) can pay. */
    suspend fun invoice(sats: Long, home: String): String =
        call("invoice", "sats" to sats.toString(), "host" to home)["invoice"]?.jsonPrimitive?.content
            ?: throw IOException("the faucet made no invoice")

    private suspend fun call(path: String, vararg query: Pair<String, String>): JsonObject = withContext(io) {
        val host = host ?: throw IOException("no regtest faucet in this build")
        val url = HttpUrl.Builder().scheme("http").host(host.substringBefore(':')).port(host.substringAfter(':').toInt())
            .addPathSegment(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()).execute().use { response ->
            val body = runCatching { Json.parseToJsonElement(response.body.string()).jsonObject }.getOrNull()
            if (!response.isSuccessful || body == null) {
                throw IOException(body?.get("error")?.jsonPrimitive?.content ?: "the faucet answered HTTP ${response.code}")
            }
            body
        }
    }
}

/** A developer's request to the faucet, as a screen shows it: under way, or why it failed. */
data class FaucetState(val busy: Boolean = false, val error: String? = null)
