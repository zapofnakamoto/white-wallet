package whitewallet.feature.notes

import androidx.compose.runtime.Composable
import whitewallet.core.designsystem.PREVIEW_MILLIS
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.NoteProblem

// The notes view and the archive in their everyday state, and the states that tell of a problem or a wait (docs/DEVELOPMENT.md,
// "Screenshots"). Made-up data only.

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

/** The phone has no connection: the notes view says so, and a note received as a link waits for it. */
@WalletPreviews @Composable private fun NotesOfflinePreview() = NotesContent(notesState.copy(offline = true), NotesActions())

/** Notes that are being paid out, may or may not have landed, that their mint does not know, or whose amount cannot be verified. */
@WalletPreviews
@Composable
private fun NotesNoteProblemsPreview() =
    NotesContent(
        notesState.copy(
            mints =
                listOf(
                    MintNotes(
                        HOME,
                        isHome = true,
                        listOf(
                            note("n1", HOME, 13_000_000, NoteState.Paying),
                            note("n2", HOME, 8_000_000, NoteState.Ambiguous),
                            note("n3", HOME, 4_000_000, NoteState.Unknown),
                            note("n4", HOME, 2_000_000),
                        ),
                        trusted = true,
                    )
                )
        ),
        NotesActions(),
    )

/** An action is waiting for its answer: the note list says so. */
@WalletPreviews
@Composable
private fun NotesOutcomePendingPreview() = NotesContent(notesState.copy(outcome = NotesOutcome.Pending), NotesActions())

/** An action failed: the reason is told in the error colour above the notes. */
@WalletPreviews
@Composable
private fun NotesOutcomeFailedPreview() =
    NotesContent(notesState.copy(outcome = NotesOutcome.Failed(NoteProblem.Unreachable)), NotesActions())

/** The sheet of a note its mint does not know: what that means, and that it can only be asked about again or archived. */
@WalletPreviews
@Composable
private fun NoteSheetUnknownPreview() =
    NotesContent(
        notesState.copy(
            mints = listOf(MintNotes(HOME, isHome = true, listOf(note("n1", HOME, 4_000_000, NoteState.Unknown)), trusted = true)),
            open = "n1",
        ),
        NotesActions(),
    )

/** An archived received note is being tried again: its button shows the wait, and the others stay off until it is answered. */
@WalletPreviews
@Composable
private fun ArchiveRetryingPreview() = ArchiveScreen(archiveState.copy(retrying = "r2"), ArchiveActions(), onBack = {})
