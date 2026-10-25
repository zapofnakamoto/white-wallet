package whitewallet.lnurl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** A parsed LNURL response body. Every LNURL error is HTTP 200 with `{"status":"ERROR"}` (LUD-01). */
public sealed interface LnurlResponse {
    public val raw: JsonObject

    public data class Error(val reason: String?, override val raw: JsonObject) : LnurlResponse

    public data class Ok(override val raw: JsonObject) : LnurlResponse

    /** LUD-06 `payRequest`. */
    public data class PayRequest(
        val callback: String,
        val minSendable: Long,
        val maxSendable: Long,
        val metadata: String,
        val commentAllowed: Int?,
        override val raw: JsonObject,
    ) : LnurlResponse {
        /** The `metadata` array as `(mime type, content)` pairs; malformed entries are skipped. */
        val metadataEntries: List<Pair<String, String>> by lazy {
            runCatching { Json.parseToJsonElement(metadata).jsonArray }.getOrNull().orEmpty().mapNotNull { entry ->
                val pair = entry as? JsonArray ?: return@mapNotNull null
                val type = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val content = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                type to content
            }
        }
    }

    /** LUD-03 `withdrawRequest`. `k1` is optional because LUD-25's lookup by `p` omits it. */
    public data class WithdrawRequest(
        val callback: String,
        val k1: String?,
        val minWithdrawable: Long,
        val maxWithdrawable: Long,
        val defaultDescription: String?,
        override val raw: JsonObject,
    ) : LnurlResponse

    /** Valid JSON that is none of the above. */
    public data class Unrecognized(override val raw: JsonObject) : LnurlResponse

    public companion object {
        /** Parses [body]; returns null if it is not a JSON object at all. Malformed known tags become [Unrecognized]. */
        public fun parse(body: String): LnurlResponse? {
            val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            return parse(obj)
        }

        public fun parse(obj: JsonObject): LnurlResponse {
            when (obj.string("status")?.uppercase()) {
                "ERROR" -> return Error(obj.string("reason"), obj)
                "OK" -> if (obj.string("tag") == null) return Ok(obj)
            }
            return when (obj.string("tag")) {
                "payRequest" -> parsePay(obj)
                "withdrawRequest" -> parseWithdraw(obj)
                else -> null
            } ?: Unrecognized(obj)
        }

        private fun parsePay(obj: JsonObject): PayRequest? {
            val callback = obj.string("callback") ?: return null
            val min = obj.msat("minSendable") ?: return null
            val max = obj.msat("maxSendable") ?: return null
            if (min > max) return null
            val commentAllowed = if ("commentAllowed" in obj) obj.msat("commentAllowed")?.toInt() ?: return null else null
            return PayRequest(callback, min, max, obj.string("metadata") ?: "[]", commentAllowed, obj)
        }

        private fun parseWithdraw(obj: JsonObject): WithdrawRequest? {
            val callback = obj.string("callback") ?: return null
            val max = obj.msat("maxWithdrawable") ?: return null
            val min = if ("minWithdrawable" in obj) obj.msat("minWithdrawable") ?: return null else 0
            if (min > max) return null
            return WithdrawRequest(callback, obj.string("k1"), min, max, obj.string("defaultDescription"), obj)
        }
    }
}

/** A string field, or null if absent or not a JSON string. */
public fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A non-negative integer field that fits a Long, or null. Strings, fractions and exponents are rejected. */
public fun JsonObject.msat(key: String): Long? {
    val p = get(key) as? JsonPrimitive ?: return null
    if (p.isString) return null
    val content = p.jsonPrimitive.content
    if (!content.all { it.isDigit() } || content.isEmpty()) return null
    return content.toLongOrNull()
}
