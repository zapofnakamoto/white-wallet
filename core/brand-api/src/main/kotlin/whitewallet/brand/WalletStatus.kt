package whitewallet.brand

/**
 * What the screen the user is looking at is about, in a few words a brand's design can show: a face, a colour, a motion. It says what is
 * going on, never how to show it, and carries no wallet data beyond an amount.
 *
 * New statuses may be added in later releases: a brand's `when` needs an `else` branch.
 */
sealed interface WalletStatus {
    /** The screen states nothing (settings, for example). */
    data object None : WalletStatus

    /** The wallet has money and nothing is happening. */
    data object Idle : WalletStatus

    /** The wallet holds nothing (yet). */
    data object Empty : WalletStatus

    /** The user is showing an address or invoice and waits for a payment. */
    data object AwaitingPayment : WalletStatus

    /** The user handed out a note (its QR code or link) and waits for someone to claim it. */
    data object WaitingForRecipient : WalletStatus

    /**
     * A payment (or a claimed note) just arrived. When several arrive at one address while its screen is open, they stay one status:
     * [amountMsat] is their total and [payments] how many there are (1 for a paid invoice or a claimed note).
     */
    data class PaymentReceived(val amountMsat: Long, val payments: Int = 1) : WalletStatus

    /** A payment is on its way out. */
    data object Sending : WalletStatus

    /** A payment went out (or a note was handed out, or claimed by its recipient). */
    data class Sent(val amountMsat: Long) : WalletStatus

    /**
     * Something the user did just worked (a mint, an address or an account added, a scan finished); [amountMsat] is what it found, 0 if
     * nothing.
     */
    data class Done(val amountMsat: Long) : WalletStatus

    /** The wallet works on [task] for a while; the screen shows that rather than figures about to change. */
    data class Working(val task: WalletTask) : WalletStatus

    /** Something stopped the user: not enough money, no connection, a mint that doesn't answer, or anything else. */
    data class Problem(val kind: ProblemKind) : WalletStatus
}

/** New kinds may be added in later releases: a brand's `when` needs an `else` branch. */
enum class ProblemKind {
    NotEnoughFunds,

    /** The phone has no connection. */
    Offline,

    /** The phone is online, but a mint the user's money is on (or that receives for them) doesn't answer. */
    MintNotAnswering,
    Other,
}
