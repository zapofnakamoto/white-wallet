package whitewallet.brand

/**
 * What the screen the user is looking at is about, in a few words a brand's design can show: a face, a colour, a
 * motion. It says what is going on, never how to show it, and carries no wallet data beyond an amount.
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

    /** The user handed out a note (its QR code or link) and waits for someone to redeem it. */
    data object WaitingForRecipient : WalletStatus

    /** A payment (or a claimed note) just arrived. */
    data class PaymentReceived(val amountMsat: Long) : WalletStatus

    /** A payment is on its way out. */
    data object Sending : WalletStatus

    /** A payment went out (or a note was handed out, or redeemed by its recipient). */
    data class Sent(val amountMsat: Long) : WalletStatus

    /** Something stopped the user: not enough money, no connection, or anything else. */
    data class Problem(val kind: ProblemKind) : WalletStatus
}

enum class ProblemKind { NotEnoughFunds, Offline, Other }
