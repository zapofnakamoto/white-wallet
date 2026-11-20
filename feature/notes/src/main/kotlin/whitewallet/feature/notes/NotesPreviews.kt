package whitewallet.feature.notes

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.PREVIEW_MILLIS
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState

// The notes view and the archive in their everyday state (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only.

private const val HOME = "mint.example"
private const val OTHER = "cash.example"

private fun note(id: String, host: String, msat: Long, state: NoteState = NoteState.Outstanding, kind: NoteKind = NoteKind.KeyPath) =
    Note(id, "a1", host, kind, purpose = 0, index = id.length.toLong(), msat, certificate = null, state, PREVIEW_MILLIS)

private fun received(id: String, host: String, msat: Long, state: ReceivedState) =
    ReceivedNote(
        id,
        "a1",
        host,
        withdrawUrl = "https://$host/withdraw",
        k1 = null,
        certificate = null,
        declaredMsat = msat,
        amountMsat = msat,
        lockedUntilMillis = null,
        lockedForSeconds = null,
        state = state,
        journalId = null,
        receivedAtMillis = PREVIEW_MILLIS,
        updatedAtMillis = PREVIEW_MILLIS,
    )

/** Notes on the home mint and another one, a note handed out, and one received that waits to be claimed. */
internal val notesState =
    NotesState(
        mints =
            listOf(
                MintNotes(HOME, isHome = true, listOf(note("n1", HOME, 13_000_000), note("n2", HOME, 8_000_000)), trusted = true),
                MintNotes(OTHER, isHome = false, listOf(note("n3", OTHER, 4_000_000)), trusted = true),
            ),
        shared = listOf(note("s1", HOME, 2_100_000, NoteState.Shared, NoteKind.Bearer)),
        received = listOf(received("r1", OTHER, 1_000_000, ReceivedState.Waiting)),
        archivedNotes = 1,
        knownHosts = listOf(HOME, OTHER),
    )

internal val archiveState =
    ArchiveState(
        notes = listOf(received("r2", OTHER, 500_000, ReceivedState.Archived)),
        unknownNotes = listOf(note("u1", HOME, 300_000, NoteState.Unknown).copy(archivedAtMillis = PREVIEW_MILLIS)),
    )

@WalletPreviews @Composable private fun NotesPreview() = NotesContent(notesState, NotesActions())

@WalletPreviews @Composable private fun ArchivePreview() = ArchiveScreen(archiveState, ArchiveActions(), onBack = {})
