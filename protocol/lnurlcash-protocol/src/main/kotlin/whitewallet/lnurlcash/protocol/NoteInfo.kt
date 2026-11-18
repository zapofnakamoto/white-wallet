package whitewallet.lnurlcash.protocol

import fr.acinq.bitcoin.PublicKey
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string
import whitewallet.lnurlcash.Cs1

/** The answer to the informational GET on a note (`?k1=`) or a lookup by output key (`?p=`). */
public data class NoteInfo(
    val callback: String,
    /** The only authoritative statement of what the note is worth. */
    val maxWithdrawableMsat: Long,
    /**
     * The key the mint signs its certificates with. LUD-25 only says a mint SHOULD publish it: null for one that doesn't, whose notes can't
     * be certified, nor its key pinned.
     */
    val mintPubkey: PublicKey?,
    /** Certificate for the queried note, if the service sent a well-formed one. */
    val certificate: Cs1?,
) {
    public companion object {
        /**
         * Validates an informational response. [queriedK1] is the spend sent as `k1`, or null for a lookup by `p`. Throws
         * [ProtocolViolation] for anything a wallet must not trust: [InvalidMintPubkey] for a `mintPubkey` that isn't a key (not a missing
         * one). Returns the service's error, and an answer worth 0 ([NoteLookupError.burnedOrPending]), as [NoteLookupError].
         */
        public fun from(response: LnurlResponse, queriedK1: String?): NoteInfo {
            if (response is LnurlResponse.Error) throw NoteLookupError(response.reason)
            val w = response as? LnurlResponse.WithdrawRequest ?: throw ProtocolViolation(describe(response, "withdrawRequest"))
            if (queriedK1 != null && !queriedK1.equals(w.k1, ignoreCase = true)) {
                throw ProtocolViolation("the server echoed a different k1 than the one queried")
            }
            val mintPubkey =
                w.raw.string("mintPubkey")?.let {
                    parseMintPubkey(it) ?: throw InvalidMintPubkey()
                }
            // The draft after the pinned one answers a burned or pending note with 0, so no note is ever worth 0.
            if (w.maxWithdrawable == 0L) throw NoteLookupError(null, burnedOrPending = true, mintPubkey = mintPubkey)
            val certificate = (w.raw.string("c") ?: w.raw.string("sig"))?.let(Cs1::decode)
            return NoteInfo(w.callback, w.maxWithdrawable, mintPubkey, certificate)
        }
    }
}

/**
 * The service answered a note lookup with `{"status":"ERROR"}`: the note is burned, unknown, pending or rate limited. Or it answered the
 * note worth 0 ([burnedOrPending]).
 */
public class NoteLookupError(
    public val reason: String?,
    /**
     * The mint answered `minWithdrawable` and `maxWithdrawable` 0: burned, or pending while a melt's payment settles, as the draft after
     * the pinned one answers both. The mint doesn't say which; the wallet does, by its own open melts. Never [burned].
     */
    public val burnedOrPending: Boolean = false,
    /**
     * The key the mint presented with an answer worth 0: like any answer that names a note, it counts only from a mint whose key matches
     * the pinned one. Null for an error, which carries none.
     */
    public val mintPubkey: PublicKey? = null,
) : Exception(reason ?: if (burnedOrPending) "note answered as worth 0" else "note lookup refused") {
    /** What [reason] says. [RejectionKind.Spent] and [RejectionKind.NoteUnknown] are one mint's wording: hints, never proof. */
    public val kind: RejectionKind
        get() = RejectionKind.fromReason(reason)

    /**
     * The mint answered `already in use`: a lookup names one key, which the mint would answer with the note if it were outstanding, so the
     * note is burned (LUD-25). The only answer that shows a note burned; one answered worth 0 is [burnedOrPending].
     */
    public val burned: Boolean
        get() = kind == RejectionKind.AlreadyInUse
}
