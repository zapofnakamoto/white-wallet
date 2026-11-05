package whitewallet.lnurlcash.protocol

import kotlinx.serialization.json.JsonObject
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string

/** Why a service definitively refused a request. */
public enum class RejectionKind {
    /** The note has another operation in flight (a melt). Retry later; never treat as spent. */
    Pending,

    /** The service is authoritative that the note is burned. */
    Spent,

    /** The service does not recognise the note. */
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
        public fun fromReason(reason: String?): RejectionKind {
            val r = reason?.trim()?.lowercase() ?: return Other
            return when {
                r == "pending" -> Pending
                "already in use" in r -> AlreadyInUse
                "rate limited" in r -> RateLimited
                "insufficient value" in r -> InsufficientValue
                "too many k1" in r -> TooManyK1
                "issued itself" in r -> OwnInvoice
                "earlier melt" in r -> InvoiceUsed
                "spent" in r -> Spent
                "unknown" in r || "not found" in r -> NoteUnknown
                else -> Other
            }
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

    /** Landed, but a certificate owed for a `cp1` output is missing. The outputs exist; keep their secrets. */
    public data class Unverifiable(val certificate: String?, val changeCertificate: String?, val raw: JsonObject) : MutationOutcome

    /** Processed and refused. Nothing was burned or minted. */
    public data class Rejected(val kind: RejectionKind, val reason: String?) : MutationOutcome

    /** Not known whether it landed. Reconcile with lookups; never discard output secrets. */
    public data class Ambiguous(val cause: String) : MutationOutcome
}

public enum class MutationType {
    Rotate,
    Split,
    Merge,
    Melt,

    /** Internal transfer (LUD-26): a rotate whose output the mint picks, on the payee's share. Answered with a bare OK. */
    Transfer,
}

public object MutationClassifier {
    /** Classifies [result]. Every output this wallet names is a `cp1`, so rotate/split/merge owe certificates. */
    public fun classify(type: MutationType, result: HttpResult): MutationOutcome {
        val response =
            when (result) {
                is HttpResult.TransportFailure -> return MutationOutcome.Ambiguous(result.message)
                is HttpResult.Response ->
                    LnurlResponse.parse(result.body) ?: return MutationOutcome.Ambiguous("unparseable response (HTTP ${result.status})")
            }
        return when (response) {
            is LnurlResponse.Error -> MutationOutcome.Rejected(RejectionKind.fromReason(response.reason), response.reason)
            is LnurlResponse.Ok -> confirmed(type, response.raw)
            else -> MutationOutcome.Ambiguous("response confirms nothing (HTTP ${result.status})")
        }
    }

    private fun confirmed(type: MutationType, raw: JsonObject): MutationOutcome {
        val c = raw.string("c") ?: raw.string("sig")
        val c2 = raw.string("c2")
        val missing =
            when (type) {
                MutationType.Melt,
                MutationType.Transfer -> false
                MutationType.Split -> c == null || c2 == null
                MutationType.Rotate,
                MutationType.Merge -> c == null
            }
        return if (missing) MutationOutcome.Unverifiable(c, c2, raw) else MutationOutcome.Confirmed(c, c2, raw)
    }
}
