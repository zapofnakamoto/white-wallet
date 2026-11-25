package whitewallet.lnurlcash.protocol

import fr.acinq.bitcoin.ByteVector32
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string

/**
 * LUD-21 `verify`, as a melt's success answer hands it out (lnurl-mint 0.13.1 does; the draft after the pinned one requires it): whether
 * the melt's payment settled, and its [preimage] once it did. The wallet asks it only when a lookup answers the melted note 0.
 */
public data class VerifyAnswer(val settled: Boolean, val preimage: ByteVector32?) {
    public companion object {
        /**
         * Throws [NoteLookupError] for the service's error (`Not found` once the note is restored), [ProtocolViolation] for anything else.
         */
        public fun from(response: LnurlResponse): VerifyAnswer {
            if (response is LnurlResponse.Error) throw NoteLookupError(response.reason)
            val settled =
                (response.raw["settled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: throw ProtocolViolation("verify: no settled")
            @Suppress("ElseCaseInsteadOfExhaustiveWhen") // JsonElement's other cases include kotlinx's internal JsonLiteral.
            val preimage =
                when (response.raw["preimage"]) {
                    null,
                    JsonNull -> null
                    else ->
                        response.raw.string("preimage")?.takeIf { it.length == 64 }?.let { runCatching { ByteVector32(it) }.getOrNull() }
                            ?: throw ProtocolViolation("verify: a preimage that isn't 32 bytes")
                }
            return VerifyAnswer(settled, preimage)
        }
    }
}

/** The `verify` URL a melt's success answer carries, if any. */
public fun MutationOutcome.verifyUrl(): String? =
    when (this) {
        is MutationOutcome.Confirmed -> raw.string("verify")
        is MutationOutcome.Unverifiable -> raw.string("verify")
        is MutationOutcome.Ambiguous,
        is MutationOutcome.Rejected -> null
    }
