package whitewallet.lnurlcash.protocol

import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string

/** A service response that violates LUD-25 badly enough that the wallet must not act on it. */
public class ProtocolViolation(message: String) : Exception(message)

/**
 * The `Mint fees: <base_fee_msat>,<fee_ppm>[,<address_fee_msat>]` token of a payRequest's `text/plain` metadata (LUD-25), anywhere in the
 * entry; its values end at whitespace. The third value is LUD-26's address fee, charged once per registration; without it (or when it
 * doesn't parse) registering is free. All zeros is no fee at all.
 */
public data class MintFee(val baseMsat: Long, val ppm: Long, val addressMsat: Long? = null) {
    /** The expected note value when paying [amountMsat], never negative; the service's `maxWithdrawable` stays authoritative. */
    public fun noteValue(amountMsat: Long): Long =
        maxOf(0, amountMsat - baseMsat - (amountMsat / 1_000_000 * ppm + amountMsat % 1_000_000 * ppm / 1_000_000))

    public companion object {
        private val TOKEN = Regex("(?:^|\\s)Mint fees:\\s*(\\S+)")

        /** Integers above 2^53 lose precision in most languages: refused rather than read differently from other wallets. */
        private const val MAX = 1L shl 53

        public fun fromMetadata(entries: List<Pair<String, String>>): MintFee? =
            entries.filter { it.first == "text/plain" }.firstNotNullOfOrNull { entry -> TOKEN.find(entry.second)?.let { parse(it) } }

        private fun parse(match: MatchResult): MintFee? {
            val values = match.groupValues[1].split(',')
            if (values.size < 2) return null
            val (base, ppm) = values.take(2).map { value(it) ?: return null }
            if (ppm >= 1_000_000) return null
            val address = values.getOrNull(2)?.let(::value)
            return MintFee(base, ppm, address).takeUnless { base == 0L && ppm == 0L && (address ?: 0L) == 0L }
        }

        private fun value(text: String): Long? =
            text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toBigInteger()?.takeIf { it <= MAX.toBigInteger() }?.toLong()
    }
}

/** What a wallet learns from a service's minting payRequest (LUD-25 "Minting a note from a payRequest"). */
public data class MintingPayRequest(
    val pay: LnurlResponse.PayRequest,
    /** The resolved, fetchable `withdrawLink`. */
    val withdrawUrl: String,
    val fee: MintFee?,
) {
    public companion object {
        /** Parses a payRequest that mints notes; throws [ProtocolViolation] if it does not. */
        public fun from(response: LnurlResponse): MintingPayRequest {
            val pay = response as? LnurlResponse.PayRequest ?: throw ProtocolViolation(describe(response, "payRequest"))
            val withdrawLink = pay.raw.string("withdrawLink") ?: throw ProtocolViolation("payRequest does not mint notes (no withdrawLink)")
            val withdrawUrl = Lnurl.resolve(withdrawLink) ?: throw ProtocolViolation("withdrawLink is not a usable URL")
            if ((pay.commentAllowed ?: 0) < 64) throw ProtocolViolation("minting payRequest must allow a 64-character comment")
            return MintingPayRequest(pay, withdrawUrl, MintFee.fromMetadata(pay.metadataEntries))
        }
    }
}

/** A service's withdraw-side discovery document (`/.well-known/lnurlw/<name>`), used to pin `mintPubkey`. */
public data class WithdrawDiscovery(val mintPubkey: PublicKey?, val response: LnurlResponse.WithdrawRequest) {
    public companion object {
        public fun from(response: LnurlResponse): WithdrawDiscovery {
            val w = response as? LnurlResponse.WithdrawRequest ?: throw ProtocolViolation(describe(response, "withdrawRequest"))
            return WithdrawDiscovery(w.raw.string("mintPubkey")?.let(::parseMintPubkey), w)
        }
    }
}

/** A compressed secp256k1 key in hex (any case), or null. */
public fun parseMintPubkey(hex: String): PublicKey? {
    if (hex.length != 66 || !(hex.startsWith("02") || hex.startsWith("03"))) return null
    val bytes = runCatching { Hex.decode(hex.lowercase()) }.getOrNull() ?: return null
    return PublicKey(bytes).takeIf { it.isValid() }
}

internal fun describe(response: LnurlResponse, expected: String): String =
    when (response) {
        is LnurlResponse.Error -> response.reason ?: "the server returned an error"
        else -> "expected a $expected"
    }
