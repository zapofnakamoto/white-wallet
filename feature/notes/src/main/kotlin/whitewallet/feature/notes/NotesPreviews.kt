package whitewallet.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.HostText
import whitewallet.core.designsystem.PREVIEW_MILLIS
import whitewallet.core.designsystem.WalletMintMark
import whitewallet.core.designsystem.WalletNoteFace
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

/**
 * Notes on the home mint and another one (its biggest certified, drawn with the foil stripe), a note handed out, and one received that
 * waits to be claimed.
 */
internal val notesState =
    NotesState(
        mints =
            listOf(
                MintNotes(
                    HOME,
                    isHome = true,
                    // Never verified in a preview: any certificate draws the stripe.
                    listOf(note("n1", HOME, 13_000_000).copy(certificate = "preview"), note("n2", HOME, 8_000_000)),
                    trusted = true,
                ),
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

/** Notes being paid out, that may or may not have landed, that their mint doesn't know, or whose amount can't be verified. */
private val problemsState =
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
            ),
        keyChangedHosts = setOf(HOME),
    )

/** The overview with such notes: the needs-attention card says what needs the user. */
@WalletPreviews @Composable private fun NotesNoteProblemsPreview() = NotesContent(problemsState, NotesActions())

/** The home mint with none of its notes in the balance, another with one. */
private val noneInBalanceState =
    notesState.copy(
        mints =
            listOf(
                MintNotes(
                    HOME,
                    isHome = true,
                    listOf(
                        note("n1", HOME, 8_000_000, NoteState.Unknown),
                        note("n2", HOME, 4_000_000, NoteState.Unknown),
                        note("n3", HOME, 2_000_000, NoteState.Ambiguous),
                    ),
                    trusted = true,
                ),
                MintNotes(OTHER, isHome = false, listOf(note("n4", OTHER, 4_000_000)), trusted = true),
            )
    )

/** Its pile greys them out and says why, its total muted and "Not in your balance" below, never ₿0. */
@WalletPreviews @Composable private fun NotesNoneInBalancePreview() = NotesContent(noneInBalanceState, NotesActions())

/** That mint's page: its head says the same as its pile. */
@WalletPreviews
@Composable
private fun NotesNoneInBalanceMintPagePreview() = NotesContent(noneInBalanceState.copy(mintPage = HOME), NotesActions())

/** The same mint's page: every note as a row with a small bill, those out of the balance greyed out with their status in words. */
@WalletPreviews @Composable private fun NotesMintPagePreview() = NotesContent(problemsState.copy(mintPage = HOME), NotesActions())

/** What needs the user on a page of its own, by mint. */
@WalletPreviews @Composable private fun NotesAttentionPagePreview() = NotesContent(problemsState.copy(attentionOpen = true), NotesActions())

/** Selecting on a mint's page: checkboxes, and the bar with what can be done with the selection. */
@WalletPreviews
@Composable
private fun NotesSelectingPreview() =
    NotesContent(notesState.copy(mintPage = HOME, selecting = true, selected = setOf("n1", "n2")), NotesActions())

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

/**
 * Five mints' bills and marks, which between them take every border, seal outline and motif: how a brand's bills tell mints apart. The
 * first is the home mint's, certified.
 */
@WalletPreviews
@Composable
private fun MintPatternsPreview() =
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("mint.example", "mint.heron.example", "mint.kestrel.example", "mint.alder.example", "notes.fjord.example").forEachIndexed {
            i,
            host ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WalletMintMark(host, home = i == 0)
                HostText(host, style = MaterialTheme.typography.titleSmall)
            }
            WalletNoteFace(
                size = NoteFaceSize.Large,
                (i + 1) * 1_000_000L,
                modifier = Modifier.fillMaxWidth(0.55f),
                colors = MaterialTheme.walletColors.note(homeMint = i == 0),
                certified = i == 0,
                host = host,
            )
        }
    }
