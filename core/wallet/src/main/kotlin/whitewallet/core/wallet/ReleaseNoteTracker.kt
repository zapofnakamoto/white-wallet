package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.brand.ReleaseNote

/**
 * Which release notes the user still has to see. The first time the app starts with it ([start]), it records what counts as seen already:
 * without a wallet yet the ordinary notes (they tell what changed since a version this user never used: a new install, one first opened
 * after an update, an app whose data was cleared), with one from a version without release notes nothing, so they see every note.
 */
class ReleaseNoteTracker(
    /** Newest first. */
    val notes: List<ReleaseNote>,
    private val preferences: AppPreferences,
    /** Whether the phone holds a wallet. */
    private val hasWallet: suspend () -> Boolean,
) {
    /**
     * Records what counts as seen, if nothing is yet. Called as the app starts, before onboarding can make a wallet: asked later, a wallet
     * made since would count as one from before.
     */
    suspend fun start(): Set<String> = preferences.seenReleaseNotes.first() ?: baseline().also { preferences.setSeenReleaseNotes(it) }

    /** The notes not seen yet, newest first. */
    suspend fun unseen(): List<ReleaseNote> {
        val seen = start()
        return notes.filter { it.id !in seen }
    }

    /** "Got it": every note [shown] counts as seen. */
    suspend fun markSeen(shown: List<ReleaseNote>) {
        preferences.setSeenReleaseNotes(preferences.seenReleaseNotes.first().orEmpty() + shown.map { it.id })
    }

    /** The notes were closed without "Got it": ordinary ones count as seen, important ones come back next time. */
    suspend fun markDismissed(shown: List<ReleaseNote>) = markSeen(shown.filterNot { it.important })

    private suspend fun baseline(): Set<String> = if (hasWallet()) emptySet() else notes.filterNot { it.important }.map { it.id }.toSet()
}
