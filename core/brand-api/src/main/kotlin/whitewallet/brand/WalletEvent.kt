package whitewallet.brand

/**
 * Something that just happened in the wallet, for a brand to react to (a sound, a vibration, a motion), wherever the
 * user is in the app. Events carry amounts and kinds only, never notes, keys or addresses.
 *
 * New events may be added in later releases: a brand's `when` needs an `else` branch.
 */
sealed interface WalletEvent {
    /** Money arrived: a payment to the address or an invoice, or a claimed note. */
    data class PaymentReceived(val amountMsat: Long) : WalletEvent

    /** A payment went out or a note was handed out. */
    data class PaymentSent(val amountMsat: Long) : WalletEvent

    /** A payment failed. */
    data class Problem(val kind: ProblemKind) : WalletEvent

    /** The user verified the recovery phrase of the active account. */
    data object BackupDone : WalletEvent

    /** A new account was created or restored. */
    data object AccountCreated : WalletEvent
}

/**
 * Called on the main thread for every [WalletEvent] while the app runs. Register one in a brand's code module (Hilt
 * `@IntoSet`). Keep it quick: it must not block.
 */
fun interface WalletEventListener {
    fun onEvent(event: WalletEvent)
}
