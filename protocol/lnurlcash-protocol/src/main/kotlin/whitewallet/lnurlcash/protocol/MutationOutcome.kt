package whitewallet.lnurlcash.protocol

import kotlinx.serialization.json.JsonObject
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string
import whitewallet.lnurlcash.Cs1

/** Why a service definitively refused a request. */
public enum class RejectionKind {
    /** The note has another operation in flight (a melt). Retry later; never treat as spent. */
    Pending,

    /**
     * lnurl-mint's wording for a spend it refused: `Note already spent.`, or `Invalid or already spent k1.`, which 0.13.1 says for a burned
     * note, an unknown one and a bad signature alike. A hint, never proof: only a lookup by `p` answered `already in use` shows a note
     * burned ([NoteLookupError.burned]).
     */
    Spent,

    /** lnurl-mint's wording for a note it doesn't know (`Unknown note.`, `k1 not found`). A hint, never proof the note is gone. */
    NoteUnknown,

    /**
     * A key is outstanding or was ever burned (LUD-25). At a mutation it may be a `p1`/`p2` (retry at the next index) or, since LUD-25 at
     * 92e430f, a burned input: the wallet tells the two apart by looking its inputs up.
     */
    AlreadyInUse,
    RateLimited,
    InsufficientValue,
    TooManyK1,

    /** A melt into an invoice of this mint. Not a reason the spec defines; lnurl-mint's wording. */
    OwnInvoice,

    /** A melt into an invoice an earlier melt used, paid or not. Not a reason the spec defines; lnurl-mint's wording. */
    InvoiceUsed,
    Other;

    public companion object {
        /**
         * The kind [reason] names, matched exactly (trimmed, case-sensitive): the spec's reasons and lnurl-mint's sentences. Never by a
         * word in it: a mint may word any refusal freely, a timelock not reached ("cannot be spent before …") or its own trouble ("unknown
         * error"), which says nothing about the note.
         */
        public fun fromReason(reason: String?): RejectionKind =
            when (reason?.trim()) {
                "already in use" -> AlreadyInUse
                "pending" -> Pending
                "rate limited" -> RateLimited
                "insufficient value" -> InsufficientValue
                "too many k1" -> TooManyK1
                "Note already spent.",
                "Invalid or already spent k1." -> Spent
                "Unknown note.",
                "k1 not found" -> NoteUnknown
                "Cannot melt into an invoice this mint issued itself." -> OwnInvoice
                "Invoice already used by an earlier melt - use a fresh one." -> InvoiceUsed
                else -> Other
            }
    }
}

/** What actually reached the wallet for one HTTP request. */
public sealed interface HttpResult {
    public data class Response(val status: Int, val body: String) : HttpResult

    /** No response was read: connection failure, reset, or timeout. The request may still have landed. */
    public data class TransportFailure(val message: String) : HttpResult
}

/**
 * The outcome of a rotate, split, merge or melt. The dangerous case is not an error but [Ambiguous]: the input may already be burned, which
 * makes the wallet's fresh output secrets the only copy of the money.
 */
public sealed interface MutationOutcome {
    /** Confirmed. For a melt this means "payment in flight", not "spent". */
    public data class Confirmed(val certificate: String?, val changeCertificate: String?, val raw: JsonObject) : MutationOutcome

    /**
     * Landed, but a certificate owed for a `cp1` output is missing, or isn't a `cs1`. The outputs exist; keep their secrets. [certificate]
     * and [changeCertificate] are as the mint sent them.
     */
    public data class Unverifiable(val certificate: String?, val changeCertificate: String?, val raw: JsonObject) : MutationOutcome

    /**
     * Processed and refused. Nothing was burned or minted. Only ever the answer to a first attempt: a replay that is refused leaves the
     * outcome [Ambiguous], since the attempt before it may have landed.
     */
    public data class Rejected(val kind: RejectionKind, val reason: String?) : MutationOutcome

    /** Not known whether it landed. Reconcile with lookups; never discard output secrets. */
    public data class Ambiguous(val cause: String) : MutationOutcome
}

public enum class MutationType {
    Rotate,
    Split,
    Merge,
    Melt,

    /**
     * Internal transfer (LUD-26): a rotate whose output the mint picks, on the payee's share. Answered with a bare OK, and replayed like a
     * rotate when that answer is lost.
     */
    Transfer,
}

public object MutationClassifier {
    /**
     * Classifies [result]. Every output this wallet names is a `cp1`, so rotate/split/merge owe certificates.
     *
     * A mint answers every LNURL request with HTTP 200 (LUD-01), a refusal too: an answer outside 2xx comes from something in front of it
     * (a proxy's 502 or 504 page), which says nothing about whether the mint processed the request, whatever its body. Only a `status`
     * spelled `OK` or `ERROR` as LUD-01 has it is the mint's answer.
     */
    public fun classify(type: MutationType, result: HttpResult): MutationOutcome {
        val response =
            when (result) {
                is HttpResult.TransportFailure -> return MutationOutcome.Ambiguous(result.message)
                is HttpResult.Response -> {
                    if (result.status !in 200..299) return MutationOutcome.Ambiguous("HTTP ${result.status}")
                    LnurlResponse.parse(result.body) ?: return MutationOutcome.Ambiguous("unparseable response (HTTP ${result.status})")
                }
            }
        val status = response.raw.string("status")
        return when {
            response is LnurlResponse.Error && status == "ERROR" ->
                MutationOutcome.Rejected(RejectionKind.fromReason(response.reason), response.reason)
            response is LnurlResponse.Ok && status == "OK" -> confirmed(type, response.raw)
            else -> MutationOutcome.Ambiguous("response confirms nothing (HTTP ${result.status})")
        }
    }

    /** Landed. A certificate owed that doesn't decode as a `cs1` certifies nothing, as if it were missing. */
    private fun confirmed(type: MutationType, raw: JsonObject): MutationOutcome {
        val c = raw.string("c") ?: raw.string("sig")
        val c2 = raw.string("c2")
        fun uncertified(certificate: String?) = certificate?.let(Cs1::decode) == null
        val missing =
            when (type) {
                MutationType.Melt,
                MutationType.Transfer -> false
                MutationType.Split -> uncertified(c) || uncertified(c2)
                MutationType.Rotate,
                MutationType.Merge -> uncertified(c)
            }
        return if (missing) MutationOutcome.Unverifiable(c, c2, raw) else MutationOutcome.Confirmed(c, c2, raw)
    }
}
