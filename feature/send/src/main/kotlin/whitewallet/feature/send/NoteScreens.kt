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
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.NoteFacePlace
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.NoteStatus
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.PrintPage
import whitewallet.core.designsystem.QrCode
import whitewallet.core.designsystem.QrCodeCover
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SecureScreen
import whitewallet.core.designsystem.SensitiveClipboard
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCircularProgress
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletNoteFace
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.amountInputLabel
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.printBitmap
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.CreateNoteResult
import whitewallet.core.wallet.NoteAction
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayQuote
import whitewallet.core.wallet.ReclaimResult
import whitewallet.core.wallet.SharedNote
import whitewallet.core.wallet.SharedNoteState
import whitewallet.core.wallet.Wallet

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
) {
    val amountMsat: Long? get() = amountText.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
    val canCreate: Boolean get() = !creating && !quoting && (quote as? PayQuote.Ok)?.amountMsat == amountMsat
}

@HiltViewModel
class CreateNoteViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val _state = MutableStateFlow(CreateNoteState())
    val state: StateFlow<CreateNoteState> = _state
    private var quoteJob: Job? = null

    init {
        viewModelScope.launch { _state.update { it.copy(availableMsat = wallet.sendableNoteMsat()) } }
    }

    fun onAmountChange(value: String) {
        _state.update { it.copy(amountText = value.filter(Char::isDigit).take(12), problem = null, pending = false) }
        quoteJob?.cancel()
        val amount = _state.value.amountMsat
        if (amount == null) {
            _state.update { it.copy(quote = null, quoting = false) }
            return
        }
        _state.update { it.copy(quoting = true) }
        quoteJob = viewModelScope.launch {
            delay(QUOTE_DEBOUNCE_MILLIS)
            val quote = wallet.quoteNote(amount)
            _state.update { it.copy(quote = quote, quoting = false) }
        }
    }

    fun create() {
        val s = _state.value
        val amount = s.amountMsat ?: return
        if (!s.canCreate) return
        _state.update { it.copy(creating = true, problem = null, pending = false) }
        viewModelScope.launch {
            when (val result = wallet.createNote(amount)) {
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
fun CreateNoteRoute(onBack: () -> Unit, onCreated: (String) -> Unit, viewModel: CreateNoteViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }
    CreateNoteScreen(state, onBack, viewModel::onAmountChange, viewModel::create)
}

@Composable
fun CreateNoteScreen(state: CreateNoteState, onBack: () -> Unit, onAmountChange: (String) -> Unit, onCreate: () -> Unit) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.note_create_title), onBack.takeIf { !state.creating }) }) { padding ->
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.note_create_body))
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
            when (val quote = state.quote) {
                is PayQuote.Ok -> Text(
                    if (quote.feeMsat > 0) stringResource(R.string.send_fee, formatAmount(quote.feeMsat)) else stringResource(R.string.send_no_fee),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is PayQuote.Unreachable -> Text(
                    stringResource(R.string.send_not_exact, formatAmount(quote.allMsat), formatAmount(quote.maxWithChangeMsat)),
                    color = MaterialTheme.colorScheme.error,
                )
                is PayQuote.Invalid -> Text(
                    if (quote.problem == PayProblem.TooMuch && quote.limitMsat != null) stringResource(R.string.send_too_much, formatAmount(quote.limitMsat!!))
                    else problemText(quote.problem),
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }
            state.problem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            if (state.pending) Text(stringResource(R.string.note_create_pending), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Text(stringResource(R.string.note_bearer_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton(stringResource(R.string.note_create), onCreate, enabled = state.canCreate, busy = state.creating)
        }
    }
}

data class SharedNoteUiState(
    val note: SharedNote? = null,
    val loading: Boolean = true,
    val confirmReclaim: Boolean = false,
    val reclaiming: Boolean = false,
    val reclaimPending: Boolean = false,
    val alreadyRedeemed: Boolean = false,
    val problem: PayProblem? = null,
    /** Whether the brand lets users print it (`noteActions`). */
    val canPrint: Boolean = false,
)

@HiltViewModel
class SharedNoteViewModel @Inject constructor(private val wallet: Wallet, savedState: SavedStateHandle, defaults: AppDefaults) : ViewModel() {
    private val id: String = checkNotNull(savedState["id"])
    private val _state = MutableStateFlow(SharedNoteUiState(canPrint = NoteAction.Print in defaults.noteActions))
    val state: StateFlow<SharedNoteUiState> = _state

    init {
        viewModelScope.launch {
            _state.update { it.copy(note = wallet.sharedNote(id), loading = false) }
            // While the screen is open, notice when the recipient redeems it.
            while (_state.value.note?.state == SharedNoteState.Waiting) {
                delay(CHECK_EVERY_MILLIS)
                if (_state.value.reclaiming) continue
                wallet.checkSharedNote(id)?.let { note -> _state.update { it.copy(note = note) } }
            }
        }
    }

    fun askReclaim() = _state.update { it.copy(confirmReclaim = true) }

    fun dismissReclaim() = _state.update { it.copy(confirmReclaim = false) }

    fun reclaim() {
        if (_state.value.reclaiming) return
        _state.update { it.copy(confirmReclaim = false, reclaiming = true, problem = null) }
        viewModelScope.launch {
            val result = wallet.reclaimNote(id)
            val note = wallet.sharedNote(id)
            _state.update {
                when (result) {
                    is ReclaimResult.Reclaimed -> it.copy(reclaiming = false, note = note)
                    ReclaimResult.AlreadyRedeemed -> it.copy(reclaiming = false, note = note, alreadyRedeemed = true)
                    ReclaimResult.Pending -> it.copy(reclaiming = false, note = note, reclaimPending = true)
                    is ReclaimResult.Failed -> it.copy(reclaiming = false, problem = result.problem)
                }
            }
        }
    }

    private companion object {
        const val CHECK_EVERY_MILLIS = 10_000L
    }
}

/** A sent note: its QR code and link while nobody has redeemed it (share, copy, take back), else where it ended up. */
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
            onCopy = { link -> SensitiveClipboard.copy(context, "note", link) },
            onPrint = { page -> printBitmap(context, printJob, page) },
            onAskReclaim = viewModel::askReclaim,
            onDismissReclaim = viewModel::dismissReclaim,
            onReclaim = viewModel::reclaim,
        )
    }
}

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
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.note_title), onBack) }) { padding ->
        ScreenColumn(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            val note = state.note
            if (note == null) {
                if (state.loading) WalletCircularProgress() else Text(stringResource(R.string.note_not_found))
                return@ScreenColumn
            }
            WalletStatusScope(sharedNoteStatus(state, note)) { BrandDecoration(Anchor.NoteShared) }
            // The QR code only while it waits for its recipient; the app draws it, the brand may frame it.
            val link = note.link?.takeIf { note.state == SharedNoteState.Waiting }
            val qrDescription = stringResource(R.string.note_qr_description)
            WalletNoteFace(
                NoteFaceSize.Large,
                note.amountMsat,
                status = when (note.state) {
                    SharedNoteState.Waiting -> NoteStatus.Waiting
                    SharedNoteState.Redeemed -> NoteStatus.Redeemed
                    SharedNoteState.Reclaimed -> NoteStatus.TakenBack
                },
                statusText = if (note.certified) null else stringResource(R.string.note_uncertified),
                qrCode = link?.let { l ->
                    {
                        if (qrShown) QrCode(l, description = qrDescription)
                        else QrCodeCover(stringResource(R.string.note_qr_show), onReveal = { qrShown = true })
                    }
                },
                place = NoteFacePlace.Shared,
                qrCovered = link != null && !qrShown,
            )
            when (note.state) {
                SharedNoteState.Waiting -> if (link != null) {
                    Text(stringResource(R.string.note_bearer_warning), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.note_waiting), style = MaterialTheme.typography.bodyMedium)
                    if (!note.certified) Text(stringResource(R.string.note_uncertified), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        WalletButton(kind = ButtonKind.Secondary, onClick = { onShare(link) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.note_share)) }
                        WalletButton(kind = ButtonKind.Secondary, onClick = { onCopy(link) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.note_copy)) }
                        if (state.canPrint) {
                            WalletButton(kind = ButtonKind.Secondary, onClick = { printing = true }, enabled = !printing, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.note_print))
                            }
                        }
                    }
                    WalletButton(kind = ButtonKind.Text, onClick = onAskReclaim, enabled = !state.reclaiming) { Text(stringResource(R.string.note_reclaim)) }
                    if (state.reclaiming) WalletCircularProgress()
                }
                SharedNoteState.Redeemed -> Text(
                    stringResource(if (state.alreadyRedeemed) R.string.note_already_redeemed else R.string.note_redeemed),
                    textAlign = TextAlign.Center,
                )
                SharedNoteState.Reclaimed -> Text(
                    stringResource(if (state.reclaimPending) R.string.note_reclaim_pending else R.string.note_reclaimed),
                    textAlign = TextAlign.Center,
                )
            }
            state.problem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            if (note.state != SharedNoteState.Waiting) PrimaryButton(stringResource(R.string.send_done), onBack)
        }
    }
    // On paper what the screen shows, so that whoever finds the paper knows it is money: the note and the warning.
    val printed = state.note?.takeIf { it.state == SharedNoteState.Waiting && it.link != null }
    PrintPage(printing && printed != null, onPrinted = { printing = false; onPrint(it) }) {
        printed?.let { note ->
            val qrDescription = stringResource(R.string.note_qr_description)
            WalletNoteFace(
                NoteFaceSize.Large,
                note.amountMsat,
                status = NoteStatus.Waiting,
                statusText = if (note.certified) null else stringResource(R.string.note_uncertified),
                qrCode = { QrCode(checkNotNull(note.link), description = qrDescription) },
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
            dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = onDismissReclaim) { Text(stringResource(R.string.note_cancel)) } },
        )
    }
}

/** Waiting for its recipient, redeemed like a payment that went out, or back in the wallet once taken back. */
private fun sharedNoteStatus(state: SharedNoteUiState, note: SharedNote): WalletStatus {
    state.problem?.let { return WalletStatus.Problem(problemKind(it)) }
    return when (note.state) {
        SharedNoteState.Waiting -> WalletStatus.WaitingForRecipient
        SharedNoteState.Redeemed -> WalletStatus.Sent(note.amountMsat)
        SharedNoteState.Reclaimed -> if (state.reclaimPending) WalletStatus.None else WalletStatus.Idle
    }
}
