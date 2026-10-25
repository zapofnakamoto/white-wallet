package whitewallet.lnurlcash.protocol

import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string

/** A service response that violates LUD-25 badly enough that the wallet must not act on it. */
public class ProtocolViolation(message: String) : Exception(message)

/** `Mint fees: <base_fee_msat>,<fee_percent_ppm>` from a minting payRequest's metadata. */
public data class MintFee(val baseMsat: Long, val ppm: Long) {
    /** The expected note value when paying [amountMsat]; the service's `maxWithdrawable` stays authoritative. */
    public fun noteValue(amountMsat: Long): Long = amountMsat - baseMsat - amountMsat * ppm / 1_000_000

    public companion object {
        private val PATTERN = Regex("^Mint fees:\\s*(\\d+)\\s*,\\s*(\\d+)\\s*$")

        public fun fromMetadata(entries: List<Pair<String, String>>): MintFee? = entries
            .filter { it.first == "text/plain" }
            .firstNotNullOfOrNull { PATTERN.matchEntire(it.second) }
            ?.let { MintFee(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
    }
}

/** What a wallet learns from a service's minting payRequest (LUD-25 "Minting a note from a payRequest"). */
public data class MintingPayRequest(
    val pay: LnurlResponse.PayRequest,
    /** The resolved, fetchable `withdrawLink`. */
    val withdrawUrl: String,
    val fee: MintFee?,
    /** LUD-26 internal-transfer hint: the payee's `cx1` and best-known next index, if published. */
    val cpub: Pair<String, Long>?,
) {
    public companion object {
        /** Parses a payRequest that mints notes; throws [ProtocolViolation] if it does not. */
        public fun from(response: LnurlResponse): MintingPayRequest {
            val pay = response as? LnurlResponse.PayRequest
                ?: throw ProtocolViolation(describe(response, "payRequest"))
            val withdrawLink = pay.raw.string("withdrawLink")
                ?: throw ProtocolViolation("payRequest does not mint notes (no withdrawLink)")
            val withdrawUrl = Lnurl.resolve(withdrawLink)
                ?: throw ProtocolViolation("withdrawLink is not a usable URL")
            if ((pay.commentAllowed ?: 0) < 64) throw ProtocolViolation("minting payRequest must allow a 64-character comment")
            val cpub = pay.metadataEntries.firstOrNull { it.first == "text/cpub" }?.second?.let { value ->
                val cx1 = value.substringBeforeLast(':')
                value.substringAfterLast(':', "").toLongOrNull()?.let { cx1 to it }
            }
            return MintingPayRequest(pay, withdrawUrl, MintFee.fromMetadata(pay.metadataEntries), cpub)
        }
    }
}

/** A service's withdraw-side discovery document (`/.well-known/lnurlw/<name>`), used to pin `mintPubkey`. */
public data class WithdrawDiscovery(val mintPubkey: PublicKey?, val response: LnurlResponse.WithdrawRequest) {
    public companion object {
        public fun from(response: LnurlResponse): WithdrawDiscovery {
            val w = response as? LnurlResponse.WithdrawRequest
                ?: throw ProtocolViolation(describe(response, "withdrawRequest"))
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

internal fun describe(response: LnurlResponse, expected: String): String = when (response) {
    is LnurlResponse.Error -> response.reason ?: "the server returned an error"
    else -> "expected a $expected"
}
