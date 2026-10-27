package whitewallet.core.model

enum class NoteState {
    /** Held by this wallet and counted in the balance. */
    Outstanding,

    /** An operation on it may or may not have landed; reconciliation will settle it. */
    Ambiguous,

    /** Burned at the mint. */
    Spent,

    /** Being melted: the mint is paying an invoice from it. Not in the balance; burned once the payment settles, outstanding again if it fails. */
    Paying,

    /** Handed out as a link (sent as a note). Not in the balance; spent once someone redeems it, or once taken back. */
    Shared,
}

enum class NoteKind { KeyPath, Bearer }

/**
 * One account: its own recovery phrase, home mint ([host]), notes, and Lightning Addresses (none, if the brand
 * doesn't require one). The primary address, when there is one, is always on [host].
 */
data class Account(val id: String, val name: String, val createdAtMillis: Long, val host: String)

/** An account with what the switcher shows about it. */
data class AccountSummary(val account: Account, val profile: Profile?, val balanceMsat: Long)

/** A note this wallet holds or held, identified by its output key `Q`. */
data class Note(
    /** Lowercase hex of `Q`. */
    val id: String,
    /** The account whose seed the note's key derives from. */
    val accountId: String,
    /** The mint host exactly as stored (port included). */
    val host: String,
    val kind: NoteKind,
    /** LUD-26 derivation coordinates for key-path notes; the secret is re-derived from the seed when needed. */
    val purpose: Int?,
    val index: Long?,
    val amountMsat: Long,
    val certificate: String?,
    val state: NoteState,
    val updatedAtMillis: Long,
)

/** A mint the wallet talks to. Only [host] is configuration; the rest is fetched from it. */
data class Mint(
    val host: String,
    val withdrawUrl: String,
    /** Pinned on first contact; operations stop if it ever changes. */
    val mintPubkey: String?,
    val minSendableMsat: Long,
    val maxSendableMsat: Long,
    val feeBaseMsat: Long?,
    val feePpm: Long?,
)

data class Profile(val username: String, val host: String) {
    val address: String get() = "$username@$host"
}

/**
 * A Lightning Address registered to an account. An account can hold several on a mint; all of them pay into the
 * same note keys (LUD-26 purpose 2). The account's [Profile] is the primary one, shown on Home and Receive.
 */
data class RegisteredAddress(val username: String, val host: String, val addedAtMillis: Long) {
    val address: String get() = "$username@$host"
}

enum class OperationType {
    Rotate, Split, Merge, Melt,

    /** A note minted at one of our keys by paying the mint's invoice (the receiving side of a move between mints). */
    Mint,
}

enum class JournalStatus {
    /** Written before the request leaves the device. If the app dies here, the request may or may not have been sent. */
    Prepared,
    Confirmed,
    Rejected,

    /** The outcome is not known yet. */
    Unknown,
}

/** An output note named in a mutation, recorded before its `cp1` is disclosed. [accountId] owns the output's key. */
data class OutputRef(val accountId: String, val purpose: Int, val index: Long, val q: String) {
    /** Someone else's key (an internal transfer): never recorded as a note of this wallet. */
    val isExternal: Boolean get() = accountId.isEmpty()

    companion object {
        fun external(q: String) = OutputRef("", 2, -1, q)
    }
}

/** Write-ahead log entry for one mutation. */
data class JournalEntry(
    val id: String,
    /** The account the inputs belong to. Outputs may belong to another account (moving funds). */
    val accountId: String,
    val type: OperationType,
    val host: String,
    val withdrawUrl: String,
    /** `Q` of every input note. */
    val inputIds: List<String>,
    /** The spends sent as `k1`. Needed to reclaim a received note if the mutation did not land. Sensitive. */
    val inputK1s: List<String>,
    val outputs: List<OutputRef>,
    /** The exact request, for byte-identical replay. */
    val requestUrl: String,
    val status: JournalStatus,
    val detail: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** What an [Activity] row stands for. */
enum class ActivityKind(val isMaintenance: Boolean) {
    /** A payment to one of the account's Lightning Addresses. */
    Received(false),

    /** A note someone handed over, now in this wallet. */
    Claimed(false),

    /** Funds moved here from another account of this wallet. */
    MovedIn(false),

    /** Funds moved from here to another account of this wallet. */
    MovedOut(false),

    /** A payment to an invoice, a Lightning Address or a pay link. */
    Sent(false),

    /** Notes moved to another mint of the same account (melted here, minted there). */
    MovedMint(false),

    /** One note split in two from the notes page. */
    Split(true),

    /** A note handed out as a link: pending until redeemed, cancelled if taken back. */
    SentNote(false),

    /** Note maintenance: several of the account's notes merged into one. The balance stays the same (less any fee). */
    Combined(true),

    /** Note maintenance: a note moved onto a fresh wallet key. The balance stays the same. */
    Rotated(true),
}

enum class ActivityStatus {
    Pending, Done, Failed,

    /** Called off by the user before it completed (a sent note taken back). */
    Cancelled,
}

/**
 * One entry of an account's history: something that happened to its money ([ActivityKind.isMaintenance] false),
 * or note maintenance. Written when it happens; notes themselves are an implementation detail.
 */
data class Activity(
    /** Stable, so a re-scan or retry never adds a second row: `recv:<Q>`, `claim:<journal id>`, `move:<journal id>:out`, `op:<journal id>`. */
    val id: String,
    val accountId: String,
    val kind: ActivityKind,
    /** What changed for the account (for maintenance: the amount involved). Always positive. */
    val amountMsat: Long,
    /** The mint's fee, if any and if known. Negative: the mint paid that much back (proven by the certificates of what came out). */
    val feeMsat: Long?,
    val host: String,
    /** For moves, the other account's name. */
    val counterparty: String?,
    /** Whether the mint's certificate verified, where that applies. */
    val certified: Boolean?,
    /** For [ActivityKind.Combined], how many notes were merged. */
    val noteCount: Int?,
    /** For maintenance: done by a note policy rather than by the user. */
    val automatic: Boolean,
    val status: ActivityStatus,
    /** The journal entry whose outcome settles a [ActivityStatus.Pending] row. */
    val journalId: String?,
    val createdAtMillis: Long,
)

/** How amounts are shown. Either way the number is the same whole count of the smallest unit (1 sat). */
enum class AmountUnit {
    /** BIP-177: the smallest unit is called bitcoin and written with ₿, as a whole number (₿1,000 = 1,000 sats). */
    Bitcoin,

    /** The same number with the word "sats" (1,000 sats). */
    Sats;

    companion object {
        /** Parses a brand config value: `bitcoin` or `sats`, ignoring case. */
        fun fromConfig(value: String): AmountUnit = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: error("defaultAmountUnit must be bitcoin or sats, not '$value'")
    }
}
