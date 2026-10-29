package whitewallet.core.wallet

/**
 * Telling users what the wallet did to their notes by itself, as the brand configures it (brand
 * `notes.maintenance.notices`, `notes.maintenance.notices.whenNotesHidden`).
 */
data class MaintenanceNoticeDefaults(
    /** Users have a switch for it, under Note settings. */
    val offered: Boolean = true,
    /** It starts on for a new account; with [offered] false, every account has it this way. */
    val enabled: Boolean = true,
    /** Users who never see notes (brand `notes.view=hidden`) are told too, only when the balance changed. */
    val whenNotesHidden: Boolean = true,
) {
    /** Whether an account is told, given its own choice (null if it never made one). */
    fun active(choice: Boolean?): Boolean = if (offered) choice ?: enabled else enabled

    companion object {
        /** `notes.maintenance.notices` = `hidden`, `off`, `on` or `always`. */
        fun fromConfig(value: String, whenNotesHidden: Boolean): MaintenanceNoticeDefaults = when (value.trim().lowercase()) {
            "hidden" -> MaintenanceNoticeDefaults(offered = false, enabled = false, whenNotesHidden = whenNotesHidden)
            "off" -> MaintenanceNoticeDefaults(offered = true, enabled = false, whenNotesHidden = whenNotesHidden)
            "on" -> MaintenanceNoticeDefaults(offered = true, enabled = true, whenNotesHidden = whenNotesHidden)
            "always" -> MaintenanceNoticeDefaults(offered = false, enabled = true, whenNotesHidden = whenNotesHidden)
            else -> error("notes.maintenance.notices must be hidden, off, on or always, not '$value'")
        }
    }
}

/**
 * What to tell users about one run of their account's note policies that changed their notes ([PolicyRun.maintained]):
 * the one thing that best says what happened, and what it did to the balance.
 */
sealed interface MaintenanceNotice {
    /** Positive when the mint paid fees back, negative for fees paid; null when none, or not known yet. */
    val balanceChangeMsat: Long?

    /** Notes on other mints moved to the home mint, [arrivedMsat] there. */
    data class MovedHome(val arrivedMsat: Long, override val balanceChangeMsat: Long?) : MaintenanceNotice

    /** [notes] notes combined into [into]; [amountMsat] is what those are worth. */
    data class Combined(val notes: Int, val into: Int, val amountMsat: Long, override val balanceChangeMsat: Long?) : MaintenanceNotice

    /** [notes] notes split into [into]. */
    data class Split(val notes: Int, val into: Int, override val balanceChangeMsat: Long?) : MaintenanceNotice

    /** [notes] notes combined and split into [into]. */
    data class Rearranged(val notes: Int, val into: Int, override val balanceChangeMsat: Long?) : MaintenanceNotice

    /** [notes] notes renewed: moved to fresh keys, same amounts. */
    data class Renewed(val notes: Int, override val balanceChangeMsat: Long?) : MaintenanceNotice

    /** Only what the balance did, for users who don't see notes or mints. */
    data class BalanceChanged(override val balanceChangeMsat: Long) : MaintenanceNotice

    companion object {
        /**
         * The notice for [run], or null if there is nothing to tell. Users who don't see notes ([notesVisible] false)
         * hear only about a move to the home mint and about the balance; mints are named only if [mintsVisible].
         */
        fun of(run: PolicyRun, notesVisible: Boolean, mintsVisible: Boolean): MaintenanceNotice? {
            if (!run.maintained) return null
            val change = run.balanceChangeMsat?.takeIf { it != 0L }
            val collected = run.collected.filterNot { it.pending }
            return when {
                collected.isNotEmpty() && mintsVisible -> MovedHome(collected.sumOf { it.arrivedMsat }, change)
                !notesVisible -> change?.let(::BalanceChanged)
                run.merged > 0 && run.split > 0 -> Rearranged(run.notesReplaced, run.notesMade, change)
                run.merged > 0 -> Combined(run.notesReplaced, run.notesMade, run.notesMadeMsat, change)
                run.split > 0 -> Split(run.notesReplaced, run.notesMade, change)
                run.rotated > 0 -> Renewed(run.rotated, change)
                else -> change?.let(::BalanceChanged)
            }
        }
    }
}
