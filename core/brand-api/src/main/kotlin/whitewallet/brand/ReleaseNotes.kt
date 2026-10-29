package whitewallet.brand

/**
 * One entry of "What's new": shown once, after the update that brings it, and listed in About for good.
 *
 * An [important] note is about what lies ahead (an action the user has to take, a coming change), so it is shown on a
 * fresh install too; an ordinary one only tells an updated user what changed.
 */
data class ReleaseNote(
    /** Stable and stored once seen: lowercase letters, digits and `-`, e.g. `faster-sync`. Never reuse one. */
    val id: String,
    /** String resource: a short title. */
    val title: Int,
    /** String resource: what changed, or what the user should do. */
    val body: Int,
    val important: Boolean = false,
)

/**
 * The app's release notes, newest first. The app comes with its own; a brand's code module replaces them with its
 * own (Hilt `@Provides`, one binding), since its releases and their numbers are its own.
 */
fun interface ReleaseNotes {
    fun notes(): List<ReleaseNote>
}
