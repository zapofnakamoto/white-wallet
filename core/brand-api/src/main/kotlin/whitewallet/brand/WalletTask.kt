package whitewallet.brand

/**
 * Something the wallet works on for a while that changes what the user would see: while it runs, the home screen shows
 * it ([WalletStatus.Working]) instead of figures about to change. Tasks carry kinds and counts only.
 *
 * New tasks may be added in later releases: a brand's `when` needs an `else` branch.
 */
sealed interface WalletTask {
    /** Asking the mints what changed: payments that arrived, outcomes still open, handed-out notes redeemed. */
    data object Updating : WalletTask

    /** Asking the mints about every note, one after the other: [checked] of [total] so far. */
    data class CheckingNotes(val checked: Int, val total: Int) : WalletTask

    /**
     * Looking after the notes by itself, at [step]. [strategyId] is the account's note strategy ([NoteStrategy.id])
     * when the step is one it asked for, null for auto-renew and collecting at the home mint.
     */
    data class Maintaining(val step: MaintenanceStep, val strategyId: String? = null) : WalletTask
}

/** What note maintenance does right now. New steps may be added in later releases. */
enum class MaintenanceStep { Combining, Splitting, Renewing, MovingHome }
