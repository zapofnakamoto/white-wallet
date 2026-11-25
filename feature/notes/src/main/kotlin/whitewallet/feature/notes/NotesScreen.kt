package whitewallet.feature.notes

import android.icu.text.ListFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.NoteStatus
import whitewallet.brand.ui.Page
import whitewallet.core.designsystem.BrandBody
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ReceivedNoteActions
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.needsUser
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.SimulatedFailure

/** Everything the notes view can ask for. Navigation ([openShared], [openArchive], [openSettings], [openMintDetails]) is the caller's. */
class NotesActions(
    val tap: (Note) -> Unit = {},
    val longPress: (Note) -> Unit = {},
    val startSelecting: () -> Unit = {},
    val stopSelecting: () -> Unit = {},
    val close: () -> Unit = {},
    val dismissOutcome: () -> Unit = {},
    val setSort: (NoteSort) -> Unit = {},
    val share: (String) -> Unit = {},
    val openSplit: (String) -> Unit = {},
    val recheck: (String) -> Unit = {},
    val renew: (String) -> Unit = {},
    val combine: () -> Unit = {},
    val openMove: () -> Unit = {},
    val recheckSelected: () -> Unit = {},
    val archive: (String) -> Unit = {},
    val archiveSelected: () -> Unit = {},
    val moveAnyway: (host: String) -> Unit = {},
    val splitAmount: (String) -> Unit = {},
    val split: () -> Unit = {},
    val moveTarget: (String) -> Unit = {},
    val move: () -> Unit = {},
    val dismissDialog: () -> Unit = {},
    val openShared: (String) -> Unit = {},
    val simulateFailure: (SimulatedFailure.Kind?) -> Unit = {},
    val received: ReceivedNoteActions = ReceivedNoteActions(),
    val openArchive: () -> Unit = {},
    val openReceive: () -> Unit = {},
    /** A mint's notes on their page, and back to all mints. */
    val openMint: (host: String) -> Unit = {},
    val closeMint: () -> Unit = {},
    /** What needs the user on a page of its own, and back. */
    val openAttention: () -> Unit = {},
    val closeAttention: () -> Unit = {},
    /** The mint's details (its key, to accept a new one); null: not offered. */
    val openMintDetails: ((host: String) -> Unit)? = null,
    /** The note settings (strategy, collecting at the home mint, auto-rotate); null: not offered. */
    val openSettings: (() -> Unit)? = null,
)

/**
 * Wires [NotesViewModel] to [NotesContent]; [onOpenShared] shows a shared note (its QR code, or claiming it yourself), [onOpenArchive] the
 * archived received notes, [onClaim] a link in Claim, [onReceive] Receive (the empty view's way forward), [onSettings] the note settings,
 * [onOpenMint] a mint's details.
 */
@Composable
fun rememberNotesActions(
    viewModel: NotesViewModel,
    onOpenShared: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onClaim: (String) -> Unit,
    onReceive: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onOpenMint: ((String) -> Unit)? = null,
): NotesActions =
    remember(viewModel, onOpenShared, onOpenArchive, onClaim, onReceive, onSettings, onOpenMint) {
        NotesActions(
            tap = viewModel::tap,
            longPress = viewModel::select,
            startSelecting = viewModel::startSelecting,
            stopSelecting = viewModel::stopSelecting,
            close = viewModel::close,
            dismissOutcome = viewModel::dismissOutcome,
            setSort = viewModel::setSort,
            share = viewModel::share,
            simulateFailure = viewModel::setSimulatedFailure,
            openSplit = viewModel::openSplit,
            recheck = viewModel::recheck,
            renew = viewModel::renew,
            combine = viewModel::combine,
            openMove = viewModel::openMove,
            recheckSelected = viewModel::recheckSelected,
            archive = viewModel::archive,
            archiveSelected = viewModel::archiveSelected,
            moveAnyway = viewModel::moveAnyway,
            splitAmount = viewModel::onSplitAmount,
            split = viewModel::split,
            moveTarget = viewModel::onMoveTarget,
            move = viewModel::move,
            dismissDialog = viewModel::dismissDialog,
            openShared = onOpenShared,
            received =
                ReceivedNoteActions(
                    remove = viewModel::removeReceived,
                    archive = viewModel::archiveReceived,
                    receive = { id -> viewModel.receiveReceived(id, onClaim) },
                ),
            openArchive = onOpenArchive,
            openReceive = onReceive,
            openMint = viewModel::openMint,
            closeMint = viewModel::closeMint,
            openAttention = viewModel::openAttention,
            closeAttention = viewModel::closeAttention,
            openMintDetails = onOpenMint,
            openSettings = onSettings,
        )
    }

/**
 * The Notes tab's content, inside Home (which supplies the top bar and pull to refresh, see [NotesViewModel.refresh]). A just-shared note
 * opens through [onOpenShared].
 */
@Composable
fun NotesTab(
    viewModel: NotesViewModel,
    onOpenShared: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onClaim: (String) -> Unit = {},
    onReceive: () -> Unit = {},
    onSettings: (() -> Unit)? = null,
    onOpenMint: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.sharedId) {
        state.sharedId?.let {
            onOpenShared(it)
            viewModel.sharedShown()
        }
    }
    NotesContent(state, rememberNotesActions(viewModel, onOpenShared, onOpenArchive, onClaim, onReceive, onSettings, onOpenMint))
}

/** The notes view on its own page, for brands with `notes.view=settings`; reached from account settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesRoute(
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onOpenShared: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onClaim: (String) -> Unit = {},
    onReceive: () -> Unit = {},
    onOpenMint: ((String) -> Unit)? = null,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.sharedId) {
        state.sharedId?.let {
            onOpenShared(it)
            viewModel.sharedShown()
        }
    }
    // The top bar's back arrow does what Back does: ends selecting, then leads from a mint's page or what needs the user to all mints. The
    // pages leave out their own arrow here, so there is one.
    val back =
        when {
            state.selecting -> viewModel::stopSelecting
            state.mintPage != null -> viewModel::closeMint
            state.attentionOpen -> viewModel::closeAttention
            else -> onBack
        }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.notes_title), back) }) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            NotesContent(
                state,
                rememberNotesActions(viewModel, onOpenShared, onOpenArchive, onClaim, onReceive, onSettings, onOpenMint),
                pageBackArrow = false,
            )
        }
    }
}

/**
 * The notes: the balance, what needs the user (all of it on a page of its own, by mint), each mint's notes as a pile of bills (swiped
 * through, tapped to open the mint's notes) and what isn't in the balance. On a mint's page, tap a note for its details and actions;
 * long-press (or Select) to pick several. [pageBackArrow]: a mint's page and what needs the user draw their own back arrow; false where the
 * screen's top bar has one already.
 */
@Composable
fun NotesContent(state: NotesState, actions: NotesActions, pageBackArrow: Boolean = true) {
    // Back ends selecting first, then leaves the mint's page or what needs the user, before it leaves the tab.
    BackHandler(enabled = state.selecting || state.mintPage != null || state.attentionOpen) {
        when {
            state.selecting -> actions.stopSelecting()
            state.mintPage != null -> actions.closeMint()
            else -> actions.closeAttention()
        }
    }
    if (!pageLoaded(state.loaded)) return
    // Kept above all three views, so coming back from a mint's page or what needs the user finds the overview as it was left. Received
    // links that wait for
    // the user open their section by themselves.
    val overview =
        OverviewState(
            rememberScrollState(),
            rememberPagerState { state.mints.size },
            rememberSaveable { mutableStateOf(state.received.any { it.needsUser() }) },
            rememberSaveable { mutableStateOf(false) },
        )
    BrandBody(Page.Notes, notesStatus(state)) {
        val mint = state.pageMint
        when {
            mint != null -> MintPage(mint, state, actions, pageBackArrow)
            state.attentionOpen -> AttentionPage(state, actions, pageBackArrow)
            else -> NotesOverview(state, actions, overview)
        }
    }
    state.openNote?.let { NoteSheet(it, state, actions) }
    when (val d = state.dialog) {
        is NotesDialog.Split -> SplitDialog(d, state.noteStrategy?.takeIf { state.switchesToManual }, actions)
        is NotesDialog.Move -> MoveDialog(d, state, actions)
        null -> Unit
    }
}

/** Empty when the view shows its empty state ([NotesState.empty]), waiting while handed-out notes are out, else idle. */
internal fun notesStatus(state: NotesState): WalletStatus =
    when {
        // Empty first, offline too: its empty state draws the brand's mark already, and offline holds nothing up there.
        state.empty -> WalletStatus.Empty
        state.offline -> WalletStatus.Problem(ProblemKind.Offline)
        state.sharedMsat > 0 -> WalletStatus.WaitingForRecipient
        else -> WalletStatus.Idle
    }

/** How a note in the wallet stands. */
internal fun noteStatus(note: Note): NoteStatus =
    when {
        note.state == NoteState.Paying -> NoteStatus.Paying
        note.state == NoteState.Ambiguous -> NoteStatus.Unclear
        note.state == NoteState.Unknown -> NoteStatus.Unknown
        note.certificate == null -> NoteStatus.Uncertified
        else -> NoteStatus.Ready
    }

/** What's unusual about a note, in words; null when it is simply ready. A [handedOut] note's mint not knowing it says so. */
@Composable
internal fun noteStatusText(status: NoteStatus, handedOut: Boolean = false): String? =
    when (status) {
        NoteStatus.Paying -> stringResource(R.string.notes_state_paying)
        NoteStatus.Unclear -> stringResource(DesignR.string.waiting_for_answer)
        NoteStatus.Uncertified -> stringResource(DesignR.string.uncertified)
        NoteStatus.Unknown -> stringResource(if (handedOut) R.string.notes_state_unknown_handed_out else R.string.notes_state_unknown)
        NoteStatus.Ready,
        NoteStatus.Waiting,
        NoteStatus.Spent,
        NoteStatus.TakenBack -> null
    }

internal fun noteStatusIcon(status: NoteStatus): Int? =
    when (status) {
        NoteStatus.Paying -> DesignR.drawable.ic_pending
        NoteStatus.Unclear -> DesignR.drawable.ic_unknown
        NoteStatus.Uncertified -> DesignR.drawable.ic_uncertified
        NoteStatus.Unknown -> DesignR.drawable.ic_warning
        NoteStatus.Ready,
        NoteStatus.Waiting,
        NoteStatus.Spent,
        NoteStatus.TakenBack -> null
    }

@Composable
internal fun Outcome(outcome: NotesOutcome, showMints: Boolean, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            outcomeText(outcome, showMints),
            style = MaterialTheme.typography.bodyMedium,
            color = if (outcome.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        WalletButton(kind = ButtonKind.Text, onClick = onDismiss) { Text(stringResource(DesignR.string.done)) }
    }
}

private val NotesOutcome.isProblem: Boolean
    get() =
        this is NotesOutcome.Failed ||
            this is NotesOutcome.KeyChanged ||
            this is NotesOutcome.AlreadySpent ||
            (this is NotesOutcome.Checked && (gone > 0 || unknown > 0 || unreachable.isNotEmpty()))

@Composable
private fun outcomeText(outcome: NotesOutcome, showMints: Boolean): String =
    when (outcome) {
        is NotesOutcome.Combined -> stringResource(R.string.notes_combined, formatAmount(outcome.amountMsat))
        is NotesOutcome.Split ->
            stringResource(R.string.notes_split_done, formatAmount(outcome.firstMsat), formatAmount(outcome.secondMsat))
        is NotesOutcome.Moved -> stringResource(R.string.notes_moved, formatAmount(outcome.arrivedMsat), outcome.host)
        is NotesOutcome.Renewed -> stringResource(R.string.notes_renewed, formatAmount(outcome.amountMsat))
        is NotesOutcome.Archived -> pluralStringResource(R.plurals.notes_archived_done, outcome.notes, outcome.notes)
        is NotesOutcome.Checked ->
            buildList {
                    if (outcome.gone > 0) add(pluralStringResource(R.plurals.notes_checked_gone, outcome.gone, outcome.gone))
                    if (outcome.unknown > 0) add(pluralStringResource(R.plurals.notes_checked_unknown, outcome.unknown, outcome.unknown))
                    if (outcome.takenBack > 0)
                        add(pluralStringResource(R.plurals.notes_checked_taken_back, outcome.takenBack, outcome.takenBack))
                    if (outcome.takeBackRefused > 0)
                        add(
                            pluralStringResource(
                                R.plurals.notes_checked_take_back_refused,
                                outcome.takeBackRefused,
                                outcome.takeBackRefused,
                            )
                        )
                    if (outcome.handedOutSpent > 0)
                        add(pluralStringResource(R.plurals.notes_checked_handed_out_spent, outcome.handedOutSpent, outcome.handedOutSpent))
                    if (outcome.handedOutUnknown > 0)
                        add(
                            pluralStringResource(
                                R.plurals.notes_checked_handed_out_unknown,
                                outcome.handedOutUnknown,
                                outcome.handedOutUnknown,
                            )
                        )
                    if (outcome.handedOut > 0)
                        add(pluralStringResource(R.plurals.notes_checked_handed_out, outcome.handedOut, outcome.handedOut))
                    if (outcome.gone == 0 && outcome.unknown == 0 && !outcome.handedOutNews && outcome.unreachable.isEmpty())
                        add(pluralStringResource(R.plurals.notes_checked_ok, outcome.ok, outcome.ok))
                    if (outcome.unreachable.isNotEmpty()) {
                        add(
                            if (showMints)
                                pluralStringResource(
                                    DesignR.plurals.mints_not_answering,
                                    outcome.unreachable.size,
                                    outcome.unreachable.size,
                                    ListFormatter.getInstance().format(outcome.unreachable),
                                )
                            else stringResource(DesignR.string.mint_not_answering_unnamed)
                        )
                    }
                }
                .joinToString(" ")
        is NotesOutcome.KeyChanged -> stringResource(R.string.notes_key_changed, outcome.host)
        NotesOutcome.AlreadySpent -> stringResource(R.string.notes_already_spent)
        is NotesOutcome.Settled -> stringResource(if (outcome.landed) R.string.notes_settled_landed else R.string.notes_settled_back)
        NotesOutcome.Pending -> stringResource(R.string.notes_pending)
        is NotesOutcome.Failed -> problemText(outcome.problem)
    }
