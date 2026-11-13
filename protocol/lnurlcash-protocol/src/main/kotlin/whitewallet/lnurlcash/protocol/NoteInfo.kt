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
         * one). Returns the service's error as [NoteLookupError].
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
            val certificate = (w.raw.string("c") ?: w.raw.string("sig"))?.let(Cs1::decode)
            return NoteInfo(w.callback, w.maxWithdrawable, mintPubkey, certificate)
        }
    }
}

/** The service answered a note lookup with `{"status":"ERROR"}`: the note is unknown, spent, pending or rate limited. */
public class NoteLookupError(public val reason: String?) : Exception(reason ?: "note lookup refused") {
    /** A lookup names one key, which the mint would answer with the note if it were outstanding: `already in use` there means burned. */
    public val kind: RejectionKind
        get() = RejectionKind.fromReason(reason).let { if (it == RejectionKind.AlreadyInUse) RejectionKind.Spent else it }
}
