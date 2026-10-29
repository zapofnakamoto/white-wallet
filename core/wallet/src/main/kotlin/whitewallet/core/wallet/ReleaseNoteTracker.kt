package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.brand.ReleaseNote

/**
 * Which release notes the user still has to see. The first time it is asked, it records what counts as seen already:
 * on a [freshInstall] the ordinary notes (they tell what changed since a version this user never had), after an
 * update from a version without release notes nothing, so they see every note.
 */
class ReleaseNoteTracker(
    /** Newest first. */
    val notes: List<ReleaseNote>,
    private val preferences: AppPreferences,
    /** The app was installed and never updated since. */
    private val freshInstall: Boolean,
) {
    /** The notes not seen yet, newest first. */
    suspend fun unseen(): List<ReleaseNote> {
        val seen = preferences.seenReleaseNotes.first() ?: baseline().also { preferences.setSeenReleaseNotes(it) }
        return notes.filter { it.id !in seen }
    }

    /** "Got it": every note [shown] counts as seen. */
    suspend fun markSeen(shown: List<ReleaseNote>) {
        preferences.setSeenReleaseNotes(preferences.seenReleaseNotes.first().orEmpty() + shown.map { it.id })
    }

    /** The notes were closed without "Got it": ordinary ones count as seen, important ones come back next time. */
    suspend fun markDismissed(shown: List<ReleaseNote>) = markSeen(shown.filterNot { it.important })

    private fun baseline(): Set<String> = if (freshInstall) notes.filterNot { it.important }.map { it.id }.toSet() else emptySet()
}
