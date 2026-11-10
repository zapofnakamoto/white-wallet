package whitewallet.core.model

import whitewallet.lnurlcash.Redaction
import whitewallet.lnurlcash.RevealsNoteSecret

enum class NoteState {
    /** Held by this wallet and counted in the balance. */
    Outstanding,

    /** An operation on it may or may not have landed; reconciliation will settle it. */
    Ambiguous,

    /** Burned at the mint. */
    Spent,

    /**
     * Being melted: the mint is paying an invoice from it. Not in the balance; burned once the payment settles, outstanding again if it
     * fails.
     */
    Paying,

    /** Handed out as a link (sent as a note). Not in the balance; spent once someone claims it, or once taken back. */
    Shared,

    /**
     * Handed out as a link while its mint couldn't be asked, so not confirmed to be there. Like [Shared] in every way; the first lookup by
     * its key makes it [Shared], or [Spent].
     */
    SharedUnconfirmed,

    /**
     * Held by this wallet, but its mint says it doesn't know the note (a mint that lost its records, or misbehaves). Not in the balance and
     * never spent; kept with its certificate, and outstanding again once the mint knows it.
     */
    Unknown,
}

/** Handed out as a link, confirmed by its mint or not yet. */
val NoteState.isShared: Boolean
    get() = this == NoteState.Shared || this == NoteState.SharedUnconfirmed

enum class NoteKind {
    KeyPath,

    /** Nothing assigns this today: a link waits among the received notes, and its claim makes a [KeyPath] note. */
    Bearer,
}

/**
 * A wallet: one recovery phrase (kept in the seed store under [id]) and the accounts below it. Its mints and the user's trust in them are
 * the wallet's, shared by all its accounts; which of them is home is each account's ([Account.homeHost]).
 */
data class WalletRecord(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    /** The setup step the wallet was left at; null once it is set up. */
    val setupStep: SetupStep? = null,
    /** The account that was active before this wallet's setup started, to go back to if the setup is abandoned. */
    val activeBeforeSetup: String? = null,
)

/** Where a wallet's setup continues after the app was closed. */
enum class SetupStep {
    Mint,
    Address,
}

/**
 * One account: account [index] (LUD-26 `S/a'`) of its wallet's recovery phrase. It exists on every mint of its wallet at once; its notes,
 * addresses and key counters are its own. An account made before wallets held several is its own wallet, with the same id, at index 0.
 */
data class Account(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val walletId: String = id,
    val index: Long = 0,
    /**
     * Its home mint, one of its wallet's: the mint the user trusts most for this account's money. Its notes are collected there and its
     * invoices made there. Null while it has none.
     */
    val homeHost: String? = null,
)

/** How a key-path note's key derives from the seed. */
enum class KeyScheme(val value: Int) {
    /** Legacy: LUD-26 draft 2f17578, a tweak per purpose (0 wallet, 1 change, 2 Lightning address) of one branch per host. */
    Legacy(0),

    /** LUD-26 at luds 92e430f: BIP-32 below the account, on the wallet or change chain or a share. */
    Bip32(1);

    companion object {
        fun of(value: Int): KeyScheme = entries.first { it.value == value }
    }
}

/** One of an account's LUD-26 counters on a mint domain: the next wallet or change key, and the next share to hand out. */
enum class KeyCounter {
    Wallet,
    Change,
    Shares,
}

/** An account with what the switcher shows about it. */
data class AccountSummary(val account: Account, val balanceMsat: Long)

/** A note this wallet holds or held, identified by its output key `Q`. */
data class Note(
    /** Lowercase hex of `Q`. */
    val id: String,
    /** The account whose seed the note's key derives from. */
    val accountId: String,
    /** The mint host exactly as stored (port included). */
    val host: String,
    val kind: NoteKind,
    /**
     * LUD-26 derivation coordinates for key-path notes; the secret is re-derived from the seed when needed. Under [KeyScheme.Bip32],
     * [purpose] is the chain (0 wallet, 1 change, 2 share [share]); under [KeyScheme.Legacy], the legacy purpose.
     */
    val purpose: Int?,
    val index: Long?,
    val amountMsat: Long,
    val certificate: String?,
    val state: NoteState,
    val updatedAtMillis: Long,
    /** When its mint last answered a lookup for it; kept for shared notes, so their screen can say how fresh it is. */
    val checkedAtMillis: Long? = null,
    val scheme: KeyScheme = KeyScheme.Bip32,
    /** The share a chain-2 note is on (LUD-26 `S/a'/2/n'`). */
    val share: Long? = null,
)

/** Where a [ReceivedNote] stands. Only [Claimed] puts its money in the balance, as one of the wallet's own notes. */
enum class ReceivedState {
    /** Kept, not claimed yet: its mint couldn't be reached. Tried again on every refresh. */
    Waiting,

    /** Its spend claims a time its mint hasn't reached yet. Tried again once it may have. */
    Locked,

    /**
     * Its mint presents another signing key than the one pinned for it: the spend isn't sent there until the user accepts the new key (the
     * mint's details). Asked again, by the note's key alone, on every refresh.
     */
    KeyChanged,

    /** A claim was sent and its outcome is unknown; settled with its journal entry. */
    Claiming,

    /** Renewed to one of the wallet's own keys. */
    Claimed,

    /** Spent by someone else before it was claimed, or unknown to its mint. */
    Gone,

    /**
     * Its mint kept refusing it for another reason than spent, unknown or locked, for a week since the first refusal: no longer tried. Its
     * spend is kept until the user removes it.
     */
    Refused;

    companion object {
        /** Not the wallet's yet, and may still become it. */
        val PENDING: Set<ReceivedState> = setOf(Waiting, Locked, KeyChanged, Claiming)
    }
}

/**
 * A note received as a link, kept from the moment the user keeps or claims it until it is one of the wallet's own notes. Unlike a [Note],
 * its secret can't be derived from the seed: [k1], from the link, is the only copy, so until it is claimed the recovery phrase doesn't
 * cover it, and whoever made the link can still spend it.
 */
data class ReceivedNote(
    /** Lowercase hex of `Q`. */
    val id: String,
    val accountId: String,
    /** The mint host exactly as in the link (port included). */
    val host: String,
    val withdrawUrl: String,
    /** The spend; cleared once it is worth nothing ([ReceivedState.Claimed], [ReceivedState.Gone]). */
    @property:RevealsNoteSecret val k1: String?,
    val certificate: String?,
    /** What the link says it is worth; display only. */
    val declaredMsat: Long?,
    /** What its mint says, or a certificate verified against its key; null until known. */
    val amountMsat: Long?,
    /** The time its spend claims; its mint refuses it until then. */
    val lockedUntilMillis: Long?,
    /** The wait its spend claims after its mint last credited it. */
    val lockedForSeconds: Long?,
    val state: ReceivedState,
    /** The claim in flight ([ReceivedState.Claiming]), or the one that landed. */
    val journalId: String?,
    val receivedAtMillis: Long,
    val updatedAtMillis: Long,
    /** When its mint first refused it for another reason than spent, unknown or locked: it gives up a week later. */
    val refusedSinceMillis: Long? = null,
) {
    /** Not the wallet's yet, and may still become it. */
    val isPending: Boolean
        get() = state in ReceivedState.PENDING

    /** Debug text without the spend. */
    @OptIn(RevealsNoteSecret::class)
    override fun toString(): String =
        "ReceivedNote(id=$id, accountId=$accountId, host=$host, withdrawUrl=$withdrawUrl, k1=${k1?.let(Redaction::mask)}, " +
            "certificate=$certificate, declaredMsat=$declaredMsat, amountMsat=$amountMsat, lockedUntilMillis=$lockedUntilMillis, " +
            "lockedForSeconds=$lockedForSeconds, state=$state, journalId=$journalId, receivedAtMillis=$receivedAtMillis, " +
            "updatedAtMillis=$updatedAtMillis, refusedSinceMillis=$refusedSinceMillis)"

    companion object {
        /** The received notes a list shows: not those already claimed, which are the wallet's own notes now. */
        fun shown(notes: List<ReceivedNote>): List<ReceivedNote> = notes.filter { it.state != ReceivedState.Claimed }
    }
}

/** A mint a wallet talks to. Each wallet has its own: what it fetched and the key it pinned say nothing about another wallet's. */
data class Mint(
    /** The wallet (recovery phrase) that added it. */
    val walletId: String,
    /** Only the host is configuration; the rest is fetched from it. */
    val host: String,
    val withdrawUrl: String,
    /** Pinned on first contact; operations stop if it ever changes. */
    val mintPubkey: String?,
    val minSendableMsat: Long,
    val maxSendableMsat: Long,
    val feeBaseMsat: Long?,
    val feePpm: Long?,
)

/**
 * A Lightning address registered to an account. An account can hold several on a mint; all of them pay into the same note keys (LUD-26
 * purpose 2). The account may mark one as primary: Receive offers it first.
 */
data class RegisteredAddress(
    val username: String,
    val host: String,
    val addedAtMillis: Long,
    /** The share it is registered for (LUD-26 `S/a'/2/n'`), or null while it is still on the legacy 2f17578 branch `cx1`. */
    val share: Long? = null,
    /** The invoice of the address fee (LUD-26) the mint holds the name for; null once it is registered. */
    val feeInvoice: String? = null,
) {
    val address: String
        get() = "$username@$host"
}

enum class OperationType {
    Rotate,
    Split,
    Merge,
    Melt,

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
data class OutputRef(
    val accountId: String,
    val purpose: Int,
    val index: Long,
    val q: String,
    val scheme: KeyScheme = KeyScheme.Bip32,
    val share: Long? = null,
) {
    /** Someone else's key (an internal transfer): never recorded as a note of this wallet. */
    val isExternal: Boolean
        get() = accountId.isEmpty()

    companion object {
        fun external(q: String) = OutputRef("", 2, -1, q, KeyScheme.Legacy)
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
    @property:RevealsNoteSecret val inputK1s: List<String>,
    val outputs: List<OutputRef>,
    /** The exact request, for byte-identical replay. Carries the spends. */
    @property:RevealsNoteSecret val requestUrl: String,
    val status: JournalStatus,
    val detail: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    /** Debug text without the spends. */
    @OptIn(RevealsNoteSecret::class)
    override fun toString(): String =
        "JournalEntry(id=$id, accountId=$accountId, type=$type, host=$host, withdrawUrl=$withdrawUrl, inputIds=$inputIds, " +
            "inputK1s=${inputK1s.map(Redaction::mask)}, outputs=$outputs, requestUrl=${Redaction.url(requestUrl)}, status=$status, " +
            "detail=$detail, createdAtMillis=$createdAtMillis, updatedAtMillis=$updatedAtMillis)"
}

/** What an [Activity] row stands for. */
enum class ActivityKind(val isMaintenance: Boolean) {
    /** A payment to one of the account's Lightning addresses. */
    Received(false),

    /** A note someone handed over, now in this wallet. */
    Claimed(false),

    /** Funds moved here from another account of this wallet. */
    MovedIn(false),

    /** Funds moved from here to another account of this wallet. */
    MovedOut(false),

    /** A payment to an invoice, a Lightning address or a pay link. */
    Sent(false),

    /** Notes moved to another mint of the same account (melted here, minted there). */
    MovedMint(false),

    /** One note split in two from the notes page. */
    Split(true),

    /** A note handed out as a link: pending until spent, cancelled if taken back. */
    SentNote(false),

    /** Note maintenance: several of the account's notes merged into one. The balance stays the same (less any fee). */
    Combined(true),

    /** Note maintenance: a note moved onto a fresh wallet key. The balance stays the same. */
    Rotated(true),
}

enum class ActivityStatus {
    Pending,
    Done,
    Failed,

    /** Called off by the user before it completed (a sent note taken back). */
    Cancelled,
}

/**
 * One entry of an account's history: something that happened to its money ([ActivityKind.isMaintenance] false), or note maintenance.
 * Written when it happens; notes themselves are an implementation detail.
 */
data class Activity(
    /**
     * Stable, so a re-scan or retry never adds a second row: `recv:<Q>`, `claim:<journal id>`, `move:<journal id>:out`, `op:<journal id>`.
     */
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
        fun fromConfig(value: String): AmountUnit =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: error("amounts.unit.default must be bitcoin or sats, not '$value'")
    }
}
