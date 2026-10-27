package whitewallet.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCheckbox
import whitewallet.core.designsystem.WalletSuggestionChip
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.ConnectResult
import whitewallet.core.wallet.MoneyMoved
import whitewallet.core.wallet.MovePreview
import whitewallet.core.wallet.NoteProblem
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.ServiceMoveResult
import whitewallet.core.wallet.Wallet

/** What the screen is for: a new home mint (and maybe its money with it), or only moving one mint's money. */
enum class MoneyMoveMode { ChangeHome, Move }

/** The live check of the name on the new home mint. */
enum class NameCheck { Idle, Checking, Available, Yours, Taken, Unknown }

sealed interface MoneyMoveError {
    data class Name(val detail: String) : MoneyMoveError
    data class Registration(val detail: String) : MoneyMoveError
    data class Service(val detail: String) : MoneyMoveError
    data class Network(val detail: String) : MoneyMoveError
}

/** After the change or move: the new primary address (if the home changed and the account has one), and per mint what happened. */
data class MoneyMoveOutcome(val homeChanged: Boolean, val address: String?, val moved: List<MoneyMoved>) {
    /** Mints whose money didn't move and might on another try. */
    val retryable: List<String> get() = moved.filter { it.result is ServiceMoveResult.Failed }.map { it.host }
}

data class MoneyMoveState(
    val mode: MoneyMoveMode = MoneyMoveMode.ChangeHome,
    /** [MoneyMoveMode.Move]: the mint whose money moves. */
    val from: String? = null,
    /** The mints the user knows (Settings → Mints) that can be picked; a new one is added there first. */
    val targets: List<String> = emptyList(),
    val target: String? = null,
    /** [MoneyMoveMode.ChangeHome]: the account has a primary address, so it needs a name on the new home. */
    val withAddress: Boolean = false,
    val username: String = "",
    val nameCheck: NameCheck = NameCheck.Idle,
    val suggestions: List<String> = emptyList(),
    /** The account collects at its home mint: what's within the fee cap moves along, without a choice per mint. */
    val collects: Boolean = false,
    val preview: MovePreview? = null,
    /** Mints the user unchecked (only when the account doesn't collect). */
    val excluded: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: MoneyMoveError? = null,
    val outcome: MoneyMoveOutcome? = null,
    /** The home already changed (a retry after it): the summary only moves money. */
    val homeChanged: Boolean = false,
) {
    val changingHome: Boolean get() = mode == MoneyMoveMode.ChangeHome && !homeChanged

    /** The lines that can move: quoted, and when collecting decides, within its fee cap. */
    val movable: List<MovePreview.Line>
        get() = preview?.lines.orEmpty().filter { it.quote is MovePreview.Quote.Ok && !(changingHome && collects && it.overCap(preview!!.feeCapPercent)) }

    /** What moves when the user confirms: every movable line, less the unchecked ones (a choice only without collecting). */
    val chosen: List<MovePreview.Line> get() = movable.filter { collects || it.host !in excluded }
    val chosenMsat: Long get() = chosen.sumOf { it.amountMsat }
    val chosenFeeMsat: Long get() = chosen.sumOf { (it.quote as MovePreview.Quote.Ok).feeMsat }

    /** Per-mint checkboxes: only where the user decides what moves. */
    val canChoose: Boolean get() = changingHome && !collects && movable.isNotEmpty()

    val canContinue: Boolean
        get() = !busy && target != null && (!changingHome || !withAddress || username.isNotBlank() && nameCheck != NameCheck.Taken)
}

@HiltViewModel
class MoneyMoveViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val _state = MutableStateFlow(MoneyMoveState())
    val state: StateFlow<MoneyMoveState> = _state
    private var initialized = false
    private var checkJob: Job? = null

    /** [host]: for a new home, the one to preselect; for a move, the mint whose money moves. */
    fun init(mode: MoneyMoveMode, host: String?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val home = wallet.homeHost.first()
            val from = host.takeIf { mode == MoneyMoveMode.Move }
            // Known mints only, home first (as Settings → Mints lists them); never the one the money or account leaves.
            val targets = wallet.mints.first().map { it.host }.filter { it != (if (mode == MoneyMoveMode.Move) from else home) }
            val preset = host.takeIf { mode == MoneyMoveMode.ChangeHome && it in targets } ?: targets.firstOrNull()
            val profile = wallet.profile.first()
            _state.update {
                it.copy(
                    mode = mode, from = from, targets = targets, target = preset, withAddress = profile != null,
                    username = profile?.username.orEmpty(), collects = wallet.collectAtHome.first(),
                )
            }
            scheduleCheck(immediately = true)
        }
    }

    fun onTarget(host: String) {
        _state.update { it.copy(target = host, preview = null, error = null) }
        scheduleCheck(immediately = true)
    }

    fun onUsername(value: String) {
        _state.update { it.copy(username = value, error = null, suggestions = emptyList(), preview = null) }
        scheduleCheck()
    }

    fun onToggle(host: String) = _state.update { it.copy(excluded = if (host in it.excluded) it.excluded - host else it.excluded + host) }

    /** To the summary: what each mint's money would do, at what fee. Changing home with nothing to move skips it. */
    fun review() {
        val s = _state.value
        val target = s.target ?: return
        if (!s.canContinue) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val preview = wallet.previewMove(target, s.from) ?: return@launch fail(MoneyMoveError.Service(target))
            if (s.changingHome && preview.lines.isEmpty()) return@launch run(s.copy(preview = preview), move = false)
            _state.update { it.copy(busy = false, preview = preview) }
        }
    }

    fun back() = _state.update { if (it.busy) it else it.copy(preview = null) }

    /** From the summary: change the home (if that's the job), then move what [move] says. */
    fun confirm(move: Boolean) {
        val s = _state.value
        if (s.busy || s.preview == null) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch { run(s, move) }
    }

    /** From the result: the money that didn't move, quoted again for the user to confirm; the home stays as it is now. */
    fun retry() {
        val outcome = _state.value.outcome ?: return
        val target = _state.value.target ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val preview = wallet.previewMove(target) ?: return@launch fail(MoneyMoveError.Service(target))
            val again = preview.copy(lines = preview.lines.filter { it.host in outcome.retryable })
            _state.update { it.copy(busy = false, outcome = null, preview = again, homeChanged = it.homeChanged || outcome.homeChanged, excluded = emptySet()) }
        }
    }

    private suspend fun run(s: MoneyMoveState, move: Boolean) {
        val target = s.target ?: return
        val lines = if (move) s.chosen else emptyList()
        if (!s.changingHome) return _state.update { it.copy(busy = false, outcome = MoneyMoveOutcome(false, null, wallet.moveMoney(target, lines))) }
        // The change and the move in one step: a refresh in between would collect the money itself.
        val moved = if (s.withAddress) {
            val changed = wallet.changeHome(target, s.username, lines)
            when (val r = changed.result) {
                is OnboardResult.Ready -> return _state.update { it.copy(busy = false, outcome = MoneyMoveOutcome(true, r.profile.address, changed.moved)) }
                is OnboardResult.UsernameTaken -> return _state.update {
                    it.copy(busy = false, preview = null, nameCheck = NameCheck.Taken, suggestions = r.suggestions)
                }
                is OnboardResult.UsernameInvalid -> return fail(MoneyMoveError.Name(r.reason))
                is OnboardResult.RegistrationFailed -> return fail(MoneyMoveError.Registration(r.reason.orEmpty()))
                is OnboardResult.NetworkError -> return fail(MoneyMoveError.Network(r.reason))
                is OnboardResult.ServiceInvalid -> return fail(MoneyMoveError.Service(r.reason))
                is OnboardResult.LimitReached, is OnboardResult.AccountLimitReached -> return fail(MoneyMoveError.Registration(r.toString()))
            }
        } else {
            val changed = wallet.changeHome(target, lines)
            when (val r = changed.result) {
                is ConnectResult.Connected -> changed.moved
                is ConnectResult.Failed -> return fail((r.result as? OnboardResult.NetworkError)?.let { MoneyMoveError.Network(it.reason) } ?: MoneyMoveError.Service(r.result.toString()))
            }
        }
        _state.update { it.copy(busy = false, outcome = MoneyMoveOutcome(true, null, moved)) }
    }

    private fun fail(error: MoneyMoveError) = _state.update { it.copy(busy = false, error = error, preview = null) }

    private fun scheduleCheck(immediately: Boolean = false) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            if (!immediately) delay(DEBOUNCE_MILLIS)
            val s = _state.value
            val target = s.target
            if (!s.changingHome || !s.withAddress || target == null || s.username.isBlank()) return@launch _state.update { it.copy(nameCheck = NameCheck.Idle) }
            _state.update { it.copy(nameCheck = NameCheck.Checking) }
            val check = when (wallet.checkAddress(s.username, target)) {
                AddressStatus.Available -> NameCheck.Available
                AddressStatus.Ours -> NameCheck.Yours
                AddressStatus.Taken, AddressStatus.Unconfirmed -> NameCheck.Taken
                is AddressStatus.Invalid -> NameCheck.Taken
                is AddressStatus.CheckFailed -> NameCheck.Unknown
            }
            _state.update { it.copy(nameCheck = check) }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 500L
    }
}

/** What the screen can ask for. */
class MoneyMoveActions(
    val onTarget: (String) -> Unit = {},
    val onUsername: (String) -> Unit = {},
    val onReview: () -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onConfirm: (move: Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDone: () -> Unit = {},
)

@Composable
fun MoneyMoveRoute(mode: MoneyMoveMode, host: String?, onClose: () -> Unit, viewModel: MoneyMoveViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.init(mode, host) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoneyMoveScreen(
        state, onClose,
        MoneyMoveActions(
            viewModel::onTarget, viewModel::onUsername, viewModel::review, viewModel::onToggle, viewModel::confirm, viewModel::back, viewModel::retry, onClose,
        ),
    )
}

/**
 * Changing the home mint, or moving one mint's money to another: pick a known mint (and the name there), see what the
 * money would do and what it costs, confirm, then see what happened, with another try for what didn't move.
 */
@Composable
fun MoneyMoveScreen(state: MoneyMoveState, onClose: () -> Unit, actions: MoneyMoveActions) {
    val title = stringResource(if (state.mode == MoneyMoveMode.ChangeHome) R.string.service_change_home else R.string.money_move_title)
    Scaffold(topBar = { WalletTopBar(title, onClose.takeIf { state.outcome == null && !state.busy }) }) { padding ->
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            val outcome = state.outcome
            val preview = state.preview
            when {
                outcome != null -> OutcomeContent(outcome, state, actions)
                preview != null -> ReviewContent(preview, state, actions)
                else -> PickContent(state, actions)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickContent(state: MoneyMoveState, actions: MoneyMoveActions) {
    if (state.targets.isEmpty()) {
        Text(stringResource(R.string.money_move_no_mints), textAlign = TextAlign.Center)
        return
    }
    if (state.mode == MoneyMoveMode.Move) state.from?.let { Text(stringResource(R.string.money_move_from, it), textAlign = TextAlign.Center) }
    else Text(stringResource(if (state.withAddress) R.string.money_change_body else R.string.money_change_body_no_address), textAlign = TextAlign.Center)
    MintPicker(state.targets, state.target, state.busy, actions.onTarget)
    if (state.changingHome && state.withAddress) {
        WalletTextField(
            value = state.username,
            onValueChange = actions.onUsername,
            label = { Text(stringResource(R.string.money_name_label)) },
            supportingText = {
                Column {
                    state.target?.let { if (state.username.isNotBlank()) Text(stringResource(R.string.money_address_preview, "${state.username.trim()}@$it")) }
                    NameCheckText(state.nameCheck)
                }
            },
            isError = state.nameCheck == NameCheck.Taken,
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                state.suggestions.forEach { name -> WalletSuggestionChip(onClick = { actions.onUsername(name) }, label = { Text(name) }) }
            }
        }
    }
    state.error?.let { ErrorText(it) }
    PrimaryButton(stringResource(R.string.money_continue), actions.onReview, enabled = state.canContinue, busy = state.busy)
}

/** The known mints to pick from; a new one is added on the Mints page first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MintPicker(targets: List<String>, selected: String?, busy: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!busy) expanded = it }) {
        WalletTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.money_move_target)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenuItems(expanded, targets, { expanded = false }) { host ->
            expanded = false
            onSelect(host)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.material3.ExposedDropdownMenuBoxScope.DropdownMenuItems(
    expanded: Boolean,
    targets: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        targets.forEach { host -> DropdownMenuItem(text = { Text(host) }, onClick = { onSelect(host) }) }
    }
}

@Composable
private fun NameCheckText(check: NameCheck) {
    val (text, color) = when (check) {
        NameCheck.Idle -> return
        NameCheck.Checking -> stringResource(R.string.money_name_checking) to MaterialTheme.colorScheme.onSurfaceVariant
        NameCheck.Available -> stringResource(R.string.money_name_available) to MaterialTheme.walletColors.success
        NameCheck.Yours -> stringResource(R.string.money_name_yours) to MaterialTheme.walletColors.success
        NameCheck.Taken -> stringResource(R.string.money_name_taken) to MaterialTheme.colorScheme.error
        NameCheck.Unknown -> stringResource(R.string.money_name_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, color = color)
}

@Composable
private fun ErrorText(error: MoneyMoveError) {
    val text = when (error) {
        is MoneyMoveError.Name -> stringResource(R.string.money_error_name, error.detail)
        is MoneyMoveError.Registration -> stringResource(R.string.money_error_registration, error.detail)
        is MoneyMoveError.Service -> stringResource(R.string.money_error_service, error.detail)
        is MoneyMoveError.Network -> stringResource(R.string.money_error_network, error.detail)
    }
    Text(text, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
}

/**
 * The summary: per mint, what arrives and the fee, or why it stays. The confirming button names the amount that moves,
 * so nothing is paid by mistake. Without collecting, the user may leave mints out (a section that starts closed).
 */
@Composable
private fun ReviewContent(preview: MovePreview, state: MoneyMoveState, actions: MoneyMoveActions) {
    Text(stringResource(R.string.money_review_to, preview.toHost), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    if (state.changingHome && state.withAddress) {
        Text(stringResource(R.string.money_review_address, "${state.username.trim()}@${preview.toHost}"), textAlign = TextAlign.Center)
    }
    preview.lines.forEach { line -> Text(reviewLine(line, state), textAlign = TextAlign.Center) }
    if (state.canChoose) ChooseSection(state, actions.onToggle)
    if (state.chosenMsat > 0) Text(stringResource(R.string.money_review_fee_total, formatAmount(state.chosenFeeMsat)), style = MaterialTheme.typography.titleSmall)
    val moving = state.chosenMsat
    when {
        !state.changingHome -> PrimaryButton(stringResource(R.string.money_move_amount, formatAmount(moving)), { actions.onConfirm(true) }, enabled = moving > 0, busy = state.busy)
        moving > 0 -> {
            PrimaryButton(stringResource(R.string.money_change_and_move, formatAmount(moving)), { actions.onConfirm(true) }, busy = state.busy)
            if (!state.collects) {
                WalletButton(kind = ButtonKind.Secondary, onClick = { actions.onConfirm(false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.service_change_home))
                }
                Text(stringResource(R.string.money_review_stays_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        else -> PrimaryButton(stringResource(R.string.service_change_home), { actions.onConfirm(false) }, busy = state.busy)
    }
    WalletButton(kind = ButtonKind.Text, onClick = actions.onBack, enabled = !state.busy) { Text(stringResource(R.string.money_back)) }
}

@Composable
private fun reviewLine(line: MovePreview.Line, state: MoneyMoveState): String {
    val amount = formatAmount(line.amountMsat)
    val cap = state.preview?.feeCapPercent ?: 0
    return when (val q = line.quote) {
        is MovePreview.Quote.Ok ->
            if (state.changingHome && state.collects && line.overCap(cap)) stringResource(R.string.money_review_over_cap, amount, line.host, cap)
            else stringResource(R.string.money_review_moves, amount, line.host, formatAmount(q.arrivesMsat), formatAmount(q.feeMsat))
        is MovePreview.Quote.TooSmall -> stringResource(R.string.money_review_too_small, amount, line.host)
        is MovePreview.Quote.Unavailable -> stringResource(R.string.money_review_unavailable, amount, line.host)
    }
}

/** A checkbox per mint whose money can move, all checked: opened on request. */
@Composable
private fun ChooseSection(state: MoneyMoveState, onToggle: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    WalletButton(kind = ButtonKind.Text, onClick = { open = !open }) {
        Text(stringResource(if (open) R.string.money_review_choose_hide else R.string.money_review_choose))
    }
    if (!open) return
    state.movable.forEach { line ->
        val checked = line.host !in state.excluded
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(checked, enabled = !state.busy, role = Role.Checkbox) { onToggle(line.host) },
        ) {
            WalletCheckbox(checked = checked, onCheckedChange = null, enabled = !state.busy)
            Text(stringResource(R.string.money_review_choice, formatAmount(line.amountMsat), line.host))
        }
    }
}

/** What happened: the new home and address, then per mint what moved or why it stayed, with another try. */
@Composable
private fun OutcomeContent(outcome: MoneyMoveOutcome, state: MoneyMoveState, actions: MoneyMoveActions) {
    if (outcome.homeChanged) {
        Text(stringResource(R.string.money_home_changed, state.target.orEmpty()), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        outcome.address?.let { Text(stringResource(R.string.money_result_address, it), textAlign = TextAlign.Center) }
        if (outcome.moved.isEmpty()) Text(stringResource(R.string.money_result_money_stays), textAlign = TextAlign.Center)
    }
    outcome.moved.forEach { m ->
        val amount = formatAmount(m.amountMsat)
        val (text, error) = when (val r = m.result) {
            is ServiceMoveResult.Moved -> stringResource(R.string.money_result_moved, formatAmount(r.arrivedMsat), m.host, formatAmount(r.feeMsat)) to false
            ServiceMoveResult.Pending -> stringResource(R.string.money_result_pending, amount, m.host) to false
            is ServiceMoveResult.Failed -> stringResource(R.string.money_result_failed, amount, m.host, reason(r.problem)) to true
        }
        Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
    if (outcome.retryable.isNotEmpty()) {
        WalletButton(kind = ButtonKind.Secondary, onClick = actions.onRetry, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.money_try_again))
        }
    }
    PrimaryButton(stringResource(R.string.money_done), actions.onDone, enabled = !state.busy)
}

@Composable
private fun reason(problem: NoteProblem): String = stringResource(
    when (problem) {
        NoteProblem.PaymentFailed -> R.string.money_reason_payment
        NoteProblem.Declined -> R.string.money_reason_price
        NoteProblem.Unreachable -> R.string.money_reason_offline
        NoteProblem.NotFound -> R.string.money_reason_gone
        else -> R.string.money_reason_refused
    },
)
