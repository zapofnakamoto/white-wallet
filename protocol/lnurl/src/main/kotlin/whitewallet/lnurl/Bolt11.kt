package whitewallet.lnurl

import fr.acinq.bitcoin.Bech32

/**
 * The parts of a BOLT-11 invoice a wallet shows and checks before paying. The signature is not verified here: the
 * service that pays the invoice does that, and nothing the wallet decides depends on who signed it.
 */
public data class Bolt11Invoice(
    /** The invoice as given, without a `lightning:` prefix, lowercase. */
    val raw: String,
    /** `bc`, `tb`, `bcrt`, `tbs` or `sb`. */
    val currency: String,
    /** null for an invoice that leaves the amount to the payer. */
    val amountMsat: Long?,
    val timestampSeconds: Long,
    val paymentHashHex: String,
    val description: String?,
    val descriptionHashHex: String?,
    val expirySeconds: Long,
    /** The payee's node id, when the invoice names it explicitly (`n`). */
    val payeeHex: String?,
) {
    public val expiresAtSeconds: Long get() = timestampSeconds + expirySeconds

    public fun isExpired(nowMillis: Long): Boolean = nowMillis / 1000 >= expiresAtSeconds
}

public object Bolt11 {
    private val HRP = Regex("^ln(bcrt|bc|tbs|tb|sb)([0-9]*)([munp]?)$")
    private const val MSAT_PER_BTC = 100_000_000_000L
    private const val SIGNATURE_WORDS = 104
    private const val TIMESTAMP_WORDS = 7
    private const val DEFAULT_EXPIRY_SECONDS = 3600L

    /** Decodes [input] (optionally prefixed `lightning:`), or returns null if it isn't a well-formed invoice. */
    public fun decode(input: String): Bolt11Invoice? = runCatching { decodeOrThrow(input) }.getOrNull()

    private fun decodeOrThrow(input: String): Bolt11Invoice? {
        val raw = input.trim().removePrefix("lightning:").removePrefix("LIGHTNING:").lowercase()
        val (hrp, words, encoding) = Bech32.decode(raw, false)
        if (encoding != Bech32.Encoding.Bech32) return null
        val match = HRP.matchEntire(hrp) ?: return null
        val amount = amountMsat(match.groupValues[2], match.groupValues[3]) ?: return null
        val data = words.map { it.toInt() and 31 }
        if (data.size < TIMESTAMP_WORDS + SIGNATURE_WORDS) return null
        val timestamp = data.take(TIMESTAMP_WORDS).fold(0L) { acc, w -> acc * 32 + w }

        var paymentHash: String? = null
        var description: String? = null
        var descriptionHash: String? = null
        var expiry = DEFAULT_EXPIRY_SECONDS
        var payee: String? = null
        var i = TIMESTAMP_WORDS
        val end = data.size - SIGNATURE_WORDS
        while (i < end) {
            if (i + 3 > end) return null
            val type = data[i]
            val length = data[i + 1] * 32 + data[i + 2]
            val start = i + 3
            if (start + length > end) return null
            val field = data.subList(start, start + length)
            // Fields of an unexpected length are skipped, as BOLT-11 asks of readers.
            when (type) {
                TAG_PAYMENT_HASH -> if (length == 52 && paymentHash == null) paymentHash = toBytes(field).toHex()
                TAG_DESCRIPTION -> if (description == null) description = toBytes(field).decodeToString()
                TAG_DESCRIPTION_HASH -> if (length == 52 && descriptionHash == null) descriptionHash = toBytes(field).toHex()
                TAG_EXPIRY -> expiry = field.fold(0L) { acc, w -> acc * 32 + w }
                TAG_PAYEE -> if (length == 53 && payee == null) payee = toBytes(field).toHex()
            }
            i = start + length
        }
        return Bolt11Invoice(raw, match.groupValues[1], amount.takeIf { it > 0 }, timestamp, paymentHash ?: return null,
            description, descriptionHash, expiry, payee)
    }

    /** The amount from the human-readable part in msat; 0 when absent, null when malformed. */
    private fun amountMsat(digits: String, multiplier: String): Long? {
        if (digits.isEmpty()) return if (multiplier.isEmpty()) 0 else null
        if (digits.startsWith("0")) return null
        val n = digits.toLongOrNull() ?: return null
        return when (multiplier) {
            "" -> Math.multiplyExact(n, MSAT_PER_BTC)
            "m" -> Math.multiplyExact(n, 100_000_000L)
            "u" -> Math.multiplyExact(n, 100_000L)
            "n" -> Math.multiplyExact(n, 100L)
            // Pico-bitcoin: only whole msat (a multiple of 10 pBTC) are valid.
            "p" -> if (n % 10 == 0L) n / 10 else null
            else -> null
        }
    }

    /** 5-bit words to bytes, dropping the incomplete last byte's padding. */
    private fun toBytes(words: List<Int>): ByteArray {
        val out = ByteArray(words.size * 5 / 8)
        var acc = 0
        var bits = 0
        var n = 0
        for (w in words) {
            acc = (acc shl 5) or w
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (n < out.size) out[n++] = ((acc shr bits) and 0xff).toByte()
            }
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val TAG_PAYMENT_HASH = 1
    private const val TAG_DESCRIPTION = 13
    private const val TAG_DESCRIPTION_HASH = 23
    private const val TAG_EXPIRY = 6
    private const val TAG_PAYEE = 19
}
