package whitewallet.feature.send

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.WalletStatus
import whitewallet.brand.ProblemKind
import whitewallet.core.designsystem.SuccessScreen
import whitewallet.core.designsystem.SuccessAction
import whitewallet.core.designsystem.ResultTone
import whitewallet.core.designsystem.ResultScreen
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.NoteFacePlace
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.NoteStatus
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.MintChoiceLine
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.PrintPage
import whitewallet.core.designsystem.QrCode
import whitewallet.core.designsystem.QrCodeCover
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SecureScreen
import whitewallet.core.designsystem.SensitiveClipboard
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCircularProgress
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletFilterChip
import whitewallet.core.designsystem.WalletNoteFace
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.amountInputLabel
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.designsystem.printBitmap
import whitewallet.core.designsystem.recentTime
import whitewallet.core.designsystem.showsWorking
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.CreateNoteResult
import whitewallet.core.wallet.NoteAction
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayQuote
import whitewallet.core.wallet.ReclaimResult
import whitewallet.core.wallet.SharedNote
import whitewallet.core.wallet.SharedNoteState
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.RevealsNoteSecret

data class CreateNoteState(
    val availableMsat: Long? = null,
    /** Whole sats, as typed. */
    val amountText: String = "",
    val quote: PayQuote? = null,
    val quoting: Boolean = false,
    val creating: Boolean = false,
    val problem: PayProblem? = null,
    /** The split was sent but not confirmed; nothing was handed out. */
    val pending: Boolean = false,
    /** Set once the note exists: the screen moves on to it. */
    val createdId: String? = null,
    /** The phone is offline: a note is made at its mint, so none can be now. */
    val offline: Boolean = false,
    /** The mint the user picked to make it from; null leaves it to the wallet. */
    val from: String? = null,
    /** The mints it can be made from, home first; then those holding money whose signing key doesn't match. */
    val mints: List<SendMint> = emptyList(),
    /** The brand names mints (brand `mints.visible`). */
    val mintsVisible: Boolean = true,
) {
    /** Another mint can be picked: the brand names mints and the account can pay from more than one. */
    val canPickMint: Boolean
        get() = mintsVisible && mints.count { !it.keyChanged } > 1

    /** The mint the note is made from: the one picked, else the one the quote says the wallet chose. */
    val payingHost: String?
        get() = from ?: (quote as? PayQuote.Ok)?.host

    val amountMsat: Long?
        get() = amountText.toLongOrNull()?.takeIf { it > 0 }?.times(1000)

    /** Offered at work ([creating]) too: the button locks itself then and keeps its look. */
    val canCreate: Boolean
        get() = !offline && !quoting && (quote as? PayQuote.Ok)?.amountMsat == amountMsat
}

@HiltViewModel
class CreateNoteViewModel @Inject constructor(private val wallet: Wallet, savedState: SavedStateHandle, defaults: AppDefaults) :
    ViewModel() {
    // Opened from Send for a mint: made from there.
    private val _state =
        MutableStateFlow(
            CreateNoteState(from = savedState.get<String>("from")?.takeIf { it.isNotBlank() }, mintsVisible = defaults.showMints)
        )
    val state: StateFlow<CreateNoteState> = _state
    private var quoteJob: Job? = null

    init {
        viewModelScope.launch {
            val mints = wallet.sendMints()
            _state.update { it.copy(availableMsat = wallet.sendableNoteMsat(it.from), mints = mints) }
        }
        viewModelScope.launch {
            // Back online, a failure to connect from while offline is no news.
            wallet.followOffline { offline, back ->
                _state.update { it.copy(offline = offline, problem = it.problem.takeUnless { back && it == PayProblem.Unreachable }) }
            }
        }
    }

    /** From a note's result screen back to its form, the amount kept, for another try. */
    fun dismissResult() = _state.update { it.copy(problem = null, pending = false) }

    fun onAmountChange(value: String) {
        _state.update { it.copy(amountText = value.filter(Char::isDigit).take(12), problem = null, pending = false) }
        requote()
    }

    /** Makes it from [host] from now on (null: as the wallet chooses). */
    fun makeFrom(host: String?) {
        if (_state.value.creating) return
        _state.update { s ->
            // What one note can be: from that mint, or from whichever holds the most.
            val most =
                if (host == null) s.mints.filterNot { it.keyChanged }.maxOfOrNull { it.maxMsat }
                else s.mints.firstOrNull { it.host == host }?.maxMsat
            s.copy(from = host, availableMsat = most ?: 0)
        }
        requote()
    }

    private fun requote() {
        quoteJob?.cancel()
        val amount = _state.value.amountMsat
        if (amount == null) {
            _state.update { it.copy(quote = null, quoting = false) }
            return
        }
        _state.update { it.copy(quoting = true) }
        quoteJob = viewModelScope.launch {
            delay(QUOTE_DEBOUNCE_MILLIS)
            val quote = wallet.quoteNote(amount, _state.value.from)
            _state.update { it.copy(quote = quote, quoting = false) }
        }
    }

    fun create() {
        val s = _state.value
        val amount = s.amountMsat ?: return
        if (!s.canCreate || s.creating) return
        _state.update { it.copy(creating = true, problem = null, pending = false) }
        viewModelScope.launch {
            when (val result = wallet.createNote(amount, s.from)) {
                is CreateNoteResult.Created -> _state.update { it.copy(creating = false, createdId = result.note.id) }
                CreateNoteResult.Pending -> _state.update { it.copy(creating = false, pending = true) }
                is CreateNoteResult.Failed -> _state.update { it.copy(creating = false, problem = result.problem) }
            }
        }
    }

    private companion object {
        const val QUOTE_DEBOUNCE_MILLIS = 300L
    }
}

/** Send as a note: choose the amount; the note is then shown to hand over ([onCreated]). */
@Composable
fun CreateNoteRoute(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    /** Opens a mint's details: where a new signing key is accepted. */
    onOpenMint: (String) -> Unit = {},
    viewModel: CreateNoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }
    CreateNoteScreen(state, onBack, viewModel::onAmountChange, viewModel::create, viewModel::makeFrom, onOpenMint, viewModel::dismissResult)
}

@Composable
fun CreateNoteScreen(
    state: CreateNoteState,
    onBack: () -> Unit,
    onAmountChange: (String) -> Unit,
    onCreate: () -> Unit,
    onMakeFrom: (String?) -> Unit = {},
    onOpenMint: (String) -> Unit = {},
    /** From the result screen back to the form, for another try. */
    onTryAgain: () -> Unit = {},
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.note_create_title), onBack.takeIf { !state.creating }) }) { padding ->
        // What can be sent is read first (a moment), so it doesn't come in under the field afterwards.
        if (!pageLoaded(state.availableMsat != null, Modifier.padding(padding))) return@Scaffold
        // No note: on the result screen, as a wait or a problem. Offline the form says so before the user tries.
        val problem = state.problem?.takeUnless { state.offline && it == PayProblem.Unreachable }
        if (state.pending || problem != null) {
            ResultScreen(
                if (problem != null) ResultTone.Problem else ResultTone.Waiting,
                Anchor.SendResult,
                problem?.let { WalletStatus.Problem(problemKind(it)) } ?: WalletStatus.None,
                stringResource(if (problem != null) R.string.note_create_failed_title else DesignR.string.no_answer_title),
                done = SuccessAction(stringResource(R.string.send_done), onBack),
                modifier = Modifier.padding(padding),
                more = SuccessAction(stringResource(R.string.send_try_again), onTryAgain),
                lines = listOf(if (problem != null) problemText(problem) else stringResource(R.string.note_create_pending)),
            )
            return@Scaffold
        }
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.note_create_body))
            // Money on a mint whose signing key doesn't match can't go into a note; with nothing else to make one from, that is all there
            // is to say.
            val stuck = state.mints.filter { it.keyChanged }.takeIf { state.from == null }.orEmpty()
            if (stuck.isNotEmpty()) {
                KeyChangedHeld(stuck, state.mintsVisible, onOpenMint)
                if (state.mints.all { it.keyChanged }) return@ScreenColumn
            }
            WalletTextField(
                value = state.amountText,
                onValueChange = onAmountChange,
                label = { Text(amountInputLabel()) },
                singleLine = true,
                enabled = !state.creating,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = state.availableMsat?.let { { Text(stringResource(R.string.note_available, formatAmount(it))) } },
                modifier = Modifier.fillMaxWidth(),
            )
            // Which mint it is made from, once known, where there is another to pick.
            var picking by rememberSaveable { mutableStateOf(false) }
            state.payingHost
                ?.takeIf { state.mintsVisible && (state.canPickMint || state.from != null) }
                ?.let {
                    MintChoiceLine(
                        stringResource(R.string.send_from, it),
                        { picking = true }.takeIf { state.canPickMint && !state.creating },
                    )
                }
            if (picking && state.canPickMint) {
                SendFromSheet(state.mints, state.from, state.amountMsat, onDismiss = { picking = false }) {
                    picking = false
                    onMakeFrom(it)
                }
            }
            when (val quote = state.quote) {
                is PayQuote.Ok ->
                    Text(
                        if (quote.feeMsat > 0) stringResource(R.string.send_fee, formatAmount(quote.feeMsat))
                        else stringResource(R.string.send_no_fee),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                is PayQuote.Unreachable ->
                    Text(
                        stringResource(R.string.send_not_exact, formatAmount(quote.allMsat), formatAmount(quote.maxWithChangeMsat)),
                        color = MaterialTheme.colorScheme.error,
                    )
                is PayQuote.Invalid ->
                    Text(
                        if (quote.problem == PayProblem.TooMuch && quote.limitMsat != null)
                            stringResource(R.string.send_too_much, formatAmount(quote.limitMsat!!))
                        else problemText(quote.problem),
                        color = MaterialTheme.colorScheme.error,
                    )
                null -> Unit
            }
            if (state.offline) Text(stringResource(R.string.send_offline), textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.note_bearer_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(stringResource(R.string.note_create), onCreate, enabled = state.canCreate, busy = state.creating)
        }
    }
}

data class SharedNoteUiState(
    val note: SharedNote? = null,
    val loading: Boolean = true,
    val confirmReclaim: Boolean = false,
    val reclaiming: Boolean = false,
    /** How taking it back ended, on this visit: drawn as the take back's own success or result screen. */
    val takenBack: ReclaimResult? = null,
    /** Whether the brand lets users print it (`notes.actions`). */
    val canPrint: Boolean = false,
    /** The phone is offline: the note can't be taken back now. */
    val offline: Boolean = false,
)

@HiltViewModel
class SharedNoteViewModel @Inject constructor(private val wallet: Wallet, savedState: SavedStateHandle, defaults: AppDefaults) :
    ViewModel() {
    private val pollMillis = defaults.timings.sharedNotePollMillis

    /** How soon a copied link is cleared from the clipboard. */
    val clipboardClearMillis = defaults.timings.clipboardClearMillis
    private val id: String = checkNotNull(savedState["id"])
    private val _state = MutableStateFlow(SharedNoteUiState(canPrint = NoteAction.Print in defaults.noteActions))
    val state: StateFlow<SharedNoteUiState> = _state

    init {
        viewModelScope.launch {
            // Back online, a failure to connect from while offline is no news.
            wallet.followOffline { offline, back ->
                _state.update {
                    val unreachable = (it.takenBack as? ReclaimResult.Failed)?.problem == PayProblem.Unreachable
                    it.copy(offline = offline, takenBack = it.takenBack.takeUnless { back && unreachable })
                }
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(note = wallet.sharedNote(id), loading = false) }
            // While the screen is open, notice when the recipient claims it.
            while (_state.value.note?.state == SharedNoteState.Waiting) {
                delay(pollMillis)
                if (_state.value.reclaiming) continue
                wallet.checkSharedNote(id)?.let { note -> _state.update { it.copy(note = note) } }
            }
        }
    }

    fun askReclaim() = _state.update { it.copy(confirmReclaim = true) }

    fun dismissReclaim() = _state.update { it.copy(confirmReclaim = false) }

    fun reclaim() {
        if (_state.value.reclaiming) return
        _state.update { it.copy(confirmReclaim = false, reclaiming = true, takenBack = null) }
        viewModelScope.launch {
            val result = wallet.reclaimNote(id)
            val note = wallet.sharedNote(id)
            _state.update { it.copy(reclaiming = false, note = note ?: it.note, takenBack = result) }
        }
    }
}

/** A sent note: its QR code and link while nobody has claimed it (share, copy, take back), else where it ended up. */
@Composable
fun SharedNoteRoute(onBack: () -> Unit, viewModel: SharedNoteViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.note_share_title)
    val printJob = stringResource(R.string.note_title)
    SecureScreen {
        SharedNoteScreen(
            state,
            onBack = onBack,
            onShare = { link ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(send, shareTitle))
            },
            onCopy = { link -> SensitiveClipboard.copy(context, "note", link, viewModel.clipboardClearMillis) },
            onPrint = { page -> printBitmap(context, printJob, page) },
            onAskReclaim = viewModel::askReclaim,
            onDismissReclaim = viewModel::dismissReclaim,
            onReclaim = viewModel::reclaim,
        )
    }
}

// Shows, shares, copies and prints the link: the note's spend, handed out on purpose.
@OptIn(RevealsNoteSecret::class)
@Composable
fun SharedNoteScreen(
    state: SharedNoteUiState,
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onAskReclaim: () -> Unit,
    onDismissReclaim: () -> Unit,
    onReclaim: () -> Unit,
    onPrint: (Bitmap) -> Unit = {},
) {
    var printing by remember { mutableStateOf(false) }
    // The QR code is money: covered until the user taps, and covered again whenever the app leaves the screen.
    var qrShown by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { qrShown = false }
    // Some wallets read only bech32 LNURL1: the user picks the form when theirs doesn't scan; Share, Copy and Print follow it.
    var format by rememberSaveable { mutableStateOf(NoteLinkFormat.Lnurlw) }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.note_title), onBack) }) { padding ->
        // A take back on this visit ends on its own screen, as one from the note's link does; a note opened later says how it ended below.
        state.takenBack?.let {
            TakeBackEnded(it, onBack, onReclaim, Modifier.padding(padding))
            return@Scaffold
        }
        ScreenColumn(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            val note = state.note
            if (note == null) {
                // Read from the phone (a moment): nothing meanwhile, not a spinner that is gone at once.
                if (!state.loading) Text(stringResource(R.string.note_not_found))
                return@ScreenColumn
            }
            WalletStatusScope(sharedNoteStatus(note)) { BrandDecoration(Anchor.NoteShared) }
            // The QR code only while it waits for its recipient; the app draws it, the brand may frame it.
            val link = note.link?.takeIf { note.state == SharedNoteState.Waiting }
            val qrDescription = stringResource(R.string.note_qr_description)
            WalletNoteFace(
                size = NoteFaceSize.Large,
                note.amountMsat,
                status =
                    when (note.state) {
                        SharedNoteState.Waiting -> NoteStatus.Waiting
                        SharedNoteState.Spent -> NoteStatus.Spent
                        SharedNoteState.Reclaimed -> NoteStatus.TakenBack
                        SharedNoteState.Unverified -> NoteStatus.Unclear
                    },
                statusText = if (note.certified) null else stringResource(R.string.note_uncertified),
                qrCode =
                    link?.let { l ->
                        {
                            if (qrShown) QrCode(format.qr(l), description = qrDescription)
                            else QrCodeCover(stringResource(R.string.note_qr_show), onReveal = { qrShown = true })
                        }
                    },
                place = NoteFacePlace.Shared,
                qrCovered = link != null && !qrShown,
            )
            if (link != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoteLinkFormat.entries.forEach {
                        WalletFilterChip(selected = format == it, onClick = { format = it }, label = { Text(stringResource(it.label)) })
                    }
                }
            }
            when (note.state) {
                SharedNoteState.Waiting ->
                    if (link != null) {
                        Text(
                            stringResource(R.string.note_bearer_warning),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(stringResource(R.string.note_waiting), style = MaterialTheme.typography.bodyMedium)
                        if (!note.confirmed && !note.unknownToMint)
                            Text(stringResource(R.string.note_unconfirmed), style = MaterialTheme.typography.bodyMedium)
                        note.checkedAtMillis?.let {
                            Text(stringResource(R.string.note_checked_at, recentTime(it)), style = MaterialTheme.typography.bodySmall)
                        }
                        if (note.unknownToMint) Text(stringResource(R.string.note_unknown_to_mint), color = MaterialTheme.colorScheme.error)
                        if (!note.certified) Text(stringResource(R.string.note_uncertified), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            WalletButton(
                                kind = ButtonKind.Secondary,
                                onClick = { onShare(format.text(link)) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.note_share))
                            }
                            WalletButton(
                                kind = ButtonKind.Secondary,
                                onClick = { onCopy(format.text(link)) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.note_copy))
                            }
                            if (state.canPrint) {
                                WalletButton(
                                    kind = ButtonKind.Secondary,
                                    onClick = { printing = true },
                                    enabled = !printing,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.note_print))
                                }
                            }
                        }
                        if (state.offline) Text(stringResource(R.string.note_reclaim_offline), textAlign = TextAlign.Center)
                        WalletButton(kind = ButtonKind.Text, onClick = onAskReclaim, enabled = !state.reclaiming && !state.offline) {
                            Text(stringResource(R.string.note_reclaim))
                        }
                        if (showsWorking(state.reclaiming)) WalletCircularProgress()
                    } else if (note.takeBackAsked) {
                        // No link once the user asked for it back: it is taken back as soon as its mint knows it again.
                        Text(stringResource(DesignR.string.note_take_back_when_known), textAlign = TextAlign.Center)
                        PrimaryButton(stringResource(R.string.send_done), onBack)
                    }
                SharedNoteState.Spent -> Text(stringResource(R.string.note_spent), textAlign = TextAlign.Center)
                SharedNoteState.Reclaimed -> Text(stringResource(R.string.note_reclaimed), textAlign = TextAlign.Center)
                SharedNoteState.Unverified -> Text(stringResource(R.string.note_unverified), textAlign = TextAlign.Center)
            }
            if (note.state != SharedNoteState.Waiting) PrimaryButton(stringResource(R.string.send_done), onBack)
        }
    }
    // On paper what the screen shows, so that whoever finds the paper knows it is money: the note and the warning.
    val printed = state.note?.takeIf { it.state == SharedNoteState.Waiting && it.link != null }
    PrintPage(
        printing && printed != null,
        onPrinted = {
            printing = false
            onPrint(it)
        },
    ) {
        printed?.let { note ->
            val qrDescription = stringResource(R.string.note_qr_description)
            WalletNoteFace(
                size = NoteFaceSize.Large,
                note.amountMsat,
                status = NoteStatus.Waiting,
                statusText = if (note.certified) null else stringResource(R.string.note_uncertified),
                qrCode = { QrCode(format.qr(checkNotNull(note.link)), description = qrDescription) },
                place = NoteFacePlace.Paper,
            )
            Text(stringResource(R.string.note_print_warning), textAlign = TextAlign.Center)
        }
    }
    if (state.confirmReclaim) {
        WalletDialog(
            onDismissRequest = onDismissReclaim,
            title = { Text(stringResource(R.string.note_reclaim_title)) },
            text = { Text(stringResource(R.string.note_reclaim_body)) },
            confirmButton = { WalletButton(kind = ButtonKind.Text, onClick = onReclaim) { Text(stringResource(R.string.note_reclaim)) } },
            dismissButton = {
                WalletButton(kind = ButtonKind.Text, onClick = onDismissReclaim) { Text(stringResource(R.string.note_cancel)) }
            },
        )
    }
}

/** Waiting for its recipient, spent like a payment that went out, or back in the wallet once taken back. */
private fun sharedNoteStatus(note: SharedNote): WalletStatus =
    when (note.state) {
        SharedNoteState.Waiting -> WalletStatus.WaitingForRecipient
        SharedNoteState.Spent -> WalletStatus.Sent(note.amountMsat)
        SharedNoteState.Reclaimed -> WalletStatus.Idle
        SharedNoteState.Unverified -> WalletStatus.None
    }

/**
 * How a take back on the note's page ended, on the screens a take back from its link ends on: the success screen with the amount back, else
 * the result screen. Only a refused or unanswered take back can be tried again.
 */
@Composable
private fun TakeBackEnded(result: ReclaimResult, onDone: () -> Unit, onTryAgain: () -> Unit, modifier: Modifier) {
    val done = SuccessAction(stringResource(R.string.send_done), onDone)
    if (result is ReclaimResult.Reclaimed) {
        SuccessScreen(
            anchor = Anchor.NoteTakenBack,
            status = WalletStatus.Done(result.amountMsat),
            title = stringResource(DesignR.string.take_back_done_title),
            amountMsat = result.amountMsat,
            done = done,
            modifier = modifier,
        )
        return
    }
    val (tone, title, lines) =
        when (result) {
            is ReclaimResult.Reclaimed -> error("on the success screen")
            ReclaimResult.AlreadySpent -> Triple(ResultTone.Problem, stringResource(R.string.note_already_spent), emptyList())
            ReclaimResult.Pending ->
                Triple(ResultTone.Waiting, stringResource(DesignR.string.no_answer_title), listOf(stringResource(R.string.note_reclaim_pending)))
            // Its mint doesn't know it: taken back once the mint does.
            ReclaimResult.WhenKnown ->
                Triple(
                    ResultTone.Waiting,
                    stringResource(DesignR.string.take_back_waiting_title),
                    listOf(stringResource(DesignR.string.note_take_back_when_known)),
                )
            // The mint's own words, if it gave any, under the app's.
            is ReclaimResult.Failed ->
                Triple(
                    ResultTone.Problem,
                    stringResource(DesignR.string.take_back_failed),
                    listOfNotNull(problemText(result.problem), result.mintMessage?.let { stringResource(DesignR.string.mint_message, it) }),
                )
        }
    ResultScreen(
        tone,
        Anchor.ClaimResult,
        if (result is ReclaimResult.Failed) WalletStatus.Problem(problemKind(result.problem))
        else if (tone == ResultTone.Problem) WalletStatus.Problem(ProblemKind.Other)
        else WalletStatus.None,
        title,
        done = done,
        modifier = modifier,
        more = SuccessAction(stringResource(R.string.send_try_again), onTryAgain).takeIf { result is ReclaimResult.Failed },
        lines = lines,
    )
}
