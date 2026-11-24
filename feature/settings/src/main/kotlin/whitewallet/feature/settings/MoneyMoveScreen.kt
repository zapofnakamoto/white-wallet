package whitewallet.feature.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Page
import whitewallet.core.designsystem.BrandBody
import whitewallet.core.designsystem.EmptyState
import whitewallet.core.designsystem.HostText
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ResultScreen
import whitewallet.core.designsystem.ResultTone
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SuccessAction
import whitewallet.core.designsystem.SuccessScreen
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCheckbox
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletMintMark
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.designsystem.showsWorking
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.ConnectResult
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.core.wallet.MintMoveResult
import whitewallet.core.wallet.MoneyMoved
import whitewallet.core.wallet.MovePreview
import whitewallet.core.wallet.NoteProblem
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.Wallet

/**
 * What the screen is for: a new home mint (and maybe its money with it), moving one mint's money, or moving the money on every other mint
 * to the home mint ([MoveHome]).
 */
enum class MoneyMoveMode {
    ChangeHome,
    Move,
    MoveHome,
}

sealed interface MoneyMoveError {
    /** [host] answered, but not as a mint the money can move to: named, without the app's own words for why (English). */
    data class Mint(val host: String) : MoneyMoveError

    /** No answer: nothing about why is shown (it would be the system's words, in English). Offline, the page says that instead. */
    data object Network : MoneyMoveError
}

/** After the change or move: whether the home mint changed, and per mint what happened. */
data class MoneyMoveOutcome(val homeChanged: Boolean, val moved: List<MoneyMoved>) {
    /** Mints whose money didn't move and might on another try. */
    val retryable: List<String>
        get() = moved.filter { it.result is MintMoveResult.Failed }.map { it.host }

    /** All the user asked for is done: the home changed, or money moved, and every mint's money arrived. */
    val complete: Boolean
        get() = (homeChanged || moved.isNotEmpty()) && moved.all { it.result is MintMoveResult.Moved }
}

data class MoneyMoveState(
    val mode: MoneyMoveMode = MoneyMoveMode.ChangeHome,
    /** [MoneyMoveMode.Move]: the mint whose money moves. */
    val from: String? = null,
    /** The mints the user knows (Settings → Mints) that can be picked (see [moveTargets]); a new one is added there first. */
    val targets: List<String> = emptyList(),
    val target: String? = null,
    /** The account's home mint when the page opened: its mark's colours among [targets]. */
    val home: String? = null,
    /** The wallet collects at its home mint: what's within the fee cap moves along, without a choice per mint. */
    val collects: Boolean = false,
    val preview: MovePreview? = null,
    /** Mints the user unchecked (only when the account doesn't collect). */
    val excluded: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: MoneyMoveError? = null,
    val outcome: MoneyMoveOutcome? = null,
    /** The home already changed (a retry after it): the summary only moves money. */
    val homeChanged: Boolean = false,
    /** What the page is for and the mints to pick from are known: until then it draws nothing. */
    val loaded: Boolean = true,
    /** Adding a mint would give one to pick: the brand lets users add one, and it could be a target (not while collecting at home). */
    val canAddMint: Boolean = false,
    /** The phone is offline: nothing can be asked or moved, and the page says so before the user tries. */
    val offline: Boolean = false,
) {
    /** Nothing to pick, and nothing picked yet: the page's empty state. */
    val nothingToPick: Boolean
        get() = targets.isEmpty() && preview == null && outcome == null

    val changingHome: Boolean
        get() = mode == MoneyMoveMode.ChangeHome && !homeChanged

    /** The lines that can move: quoted, and when collecting decides, within its fee cap. */
    val movable: List<MovePreview.Line>
        get() =
            preview?.lines.orEmpty().filter {
                it.quote is MovePreview.Quote.Ok && !(changingHome && collects && it.overCap(preview!!.feeCapPercent))
            }

    /** What moves when the user confirms: every movable line, less the unchecked ones (a choice only without collecting). */
    val chosen: List<MovePreview.Line>
        get() = movable.filter { collects || it.host !in excluded }

    val chosenMsat: Long
        get() = chosen.sumOf { it.amountMsat }

    val chosenFeeMsat: Long
        get() = chosen.sumOf { (it.quote as MovePreview.Quote.Ok).feeMsat }

    /** Per-mint checkboxes: only where the user decides what moves (collecting decides otherwise), and for several mints. */
    val canChoose: Boolean
        get() = (changingHome || mode == MoneyMoveMode.MoveHome) && !collects && movable.isNotEmpty()

    /** Offered at work ([busy]) too: the button locks itself then and keeps its look. */
    val canContinue: Boolean
        get() = !offline && target != null
}

@HiltViewModel
class MoneyMoveViewModel @Inject constructor(private val wallet: Wallet, private val defaults: AppDefaults) : ViewModel() {
    private val _state = MutableStateFlow(MoneyMoveState(loaded = false))
    val state: StateFlow<MoneyMoveState> = _state
    private var initialized = false

    /** [host]: for a new home, the one to preselect; for a move, the mint whose money moves. */
    fun init(mode: MoneyMoveMode, host: String?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            // No answer while offline, or from before the phone was back, is no news: the page says what it is now.
            wallet.followOffline { offline, back ->
                _state.update {
                    it.copy(offline = offline, error = it.error.takeUnless { (offline || back) && it == MoneyMoveError.Network })
                }
            }
        }
        viewModelScope.launch {
            val home = wallet.homeHost.first()
            val from = host.takeIf { mode == MoneyMoveMode.Move }
            val collects = wallet.collectAtHome.first()
            val addable = mode == MoneyMoveMode.ChangeHome || (mode == MoneyMoveMode.Move && !collects)
            var before: List<String>? = null
            // While the user picks, a mint added meanwhile (the empty page's Add a mint) joins the list, picked.
            wallet.mints.collect { mints ->
                val known = mints.map { it.host }
                val targets = moveTargets(mode, known, home, from, collects, allowed = defaults::mintAllowed)
                val canAdd = addable && (defaults.customMints || defaults.mintSuggestions.any { it !in known })
                _state.update { s ->
                    if (s.loaded && (s.preview != null || s.outcome != null || s.busy)) return@update s
                    val added = before?.let { old -> targets.firstOrNull { it !in old } }
                    val target =
                        when {
                            before == null -> host.takeIf { mode == MoneyMoveMode.ChangeHome && it in targets } ?: targets.firstOrNull()
                            added != null -> added
                            s.target in targets -> s.target
                            else -> targets.firstOrNull()
                        }
                    s.copy(
                        mode = mode,
                        from = from,
                        targets = targets,
                        target = target,
                        home = home,
                        collects = collects,
                        canAddMint = canAdd,
                        loaded = true,
                    )
                }
                before = known
            }
        }
    }

    fun onTarget(host: String) = _state.update { it.copy(target = host, preview = null, error = null) }

    fun onToggle(host: String) = _state.update { it.copy(excluded = if (host in it.excluded) it.excluded - host else it.excluded + host) }

    /** To the summary: what each mint's money would do, at what fee. Changing home with nothing to move skips it. */
    fun review() {
        val s = _state.value
        val target = s.target ?: return
        if (!s.canContinue || s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        launchFailing(target) {
            val preview = wallet.previewMove(target, s.from) ?: return@launchFailing fail(MoneyMoveError.Mint(target))
            if (s.changingHome && preview.lines.isEmpty()) return@launchFailing run(s.copy(preview = preview), move = false)
            _state.update { it.copy(busy = false, preview = preview) }
        }
    }

    fun back() = _state.update { if (it.busy) it else it.copy(preview = null) }

    /** From the summary: change the home (if that's the job), then move what [move] says. */
    fun confirm(move: Boolean) {
        val s = _state.value
        if (s.busy || s.offline || s.preview == null) return
        _state.update { it.copy(busy = true) }
        launchFailing(s.target ?: return) { run(s, move) }
    }

    /** From the result: the money that didn't move, quoted again for the user to confirm; the home stays as it is now. */
    fun retry() {
        val outcome = _state.value.outcome ?: return
        val target = _state.value.target ?: return
        _state.update { it.copy(busy = true) }
        launchFailing(target) {
            val preview = wallet.previewMove(target) ?: return@launchFailing fail(MoneyMoveError.Mint(target))
            val again = preview.copy(lines = preview.lines.filter { it.host in outcome.retryable })
            _state.update {
                it.copy(
                    busy = false,
                    outcome = null,
                    preview = again,
                    homeChanged = it.homeChanged || outcome.homeChanged,
                    excluded = emptySet(),
                )
            }
        }
    }

    private suspend fun run(s: MoneyMoveState, move: Boolean) {
        val target = s.target ?: return
        val lines = if (move) s.chosen else emptyList()
        if (!s.changingHome)
            return _state.update { it.copy(busy = false, outcome = MoneyMoveOutcome(false, wallet.moveMoney(target, lines))) }
        // The change and the move in one step: a refresh in between would collect the money itself.
        val changed = wallet.setHomeMint(target, lines)
        when (val r = changed.result) {
            is ConnectResult.Connected -> Unit
            is ConnectResult.Failed ->
                return fail((r.result as? OnboardResult.NetworkError)?.let { MoneyMoveError.Network } ?: MoneyMoveError.Mint(target))
        }
        _state.update { it.copy(busy = false, outcome = MoneyMoveOutcome(true, changed.moved)) }
    }

    /** Runs [block]; a network failure or a changed signing key at [host] ends it as an error. */
    private fun launchFailing(host: String, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: IOException) {
            fail(MoneyMoveError.Network)
        } catch (e: MintKeyChangedException) {
            fail(MoneyMoveError.Mint(host))
        }
    }

    private fun fail(error: MoneyMoveError) = _state.update { it.copy(busy = false, error = error, preview = null) }
}

/** What the screen can ask for. */
class MoneyMoveActions(
    val onTarget: (String) -> Unit = {},
    val onReview: () -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onConfirm: (move: Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDone: () -> Unit = {},
    /** The empty page's way forward: add a mint, then come back to pick it. */
    val onAddMint: () -> Unit = {},
)

@Composable
fun MoneyMoveRoute(
    mode: MoneyMoveMode,
    host: String?,
    onClose: () -> Unit,
    onAddMint: () -> Unit = {},
    viewModel: MoneyMoveViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.init(mode, host) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoneyMoveScreen(
        // The title is the route's from the first frame, before the view model knows it.
        if (state.loaded) state else state.copy(mode = mode),
        onClose,
        MoneyMoveActions(
            onTarget = viewModel::onTarget,
            onReview = viewModel::review,
            onToggle = viewModel::onToggle,
            onConfirm = viewModel::confirm,
            onBack = viewModel::back,
            onRetry = viewModel::retry,
            onDone = onClose,
            onAddMint = onAddMint,
        ),
    )
}

/**
 * Changing the home mint, or moving one mint's money to another: pick a known mint, see what the money would do and what it costs, confirm,
 * then see what happened, with another try for what didn't move.
 */
@Composable
fun MoneyMoveScreen(state: MoneyMoveState, onClose: () -> Unit, actions: MoneyMoveActions) {
    val title =
        stringResource(
            when (state.mode) {
                MoneyMoveMode.ChangeHome -> R.string.mint_change_home
                MoneyMoveMode.Move -> R.string.money_move_title
                MoneyMoveMode.MoveHome -> R.string.mint_move_all_here
            }
        )
    Scaffold(topBar = { WalletTopBar(title, onClose.takeIf { state.outcome == null && !state.busy }) }) { padding ->
        // Before the mints to pick from are known (a moment): only the title, not "no mints".
        if (!pageLoaded(state.loaded, Modifier.padding(padding))) return@Scaffold
        state.outcome?.let {
            if (it.complete) CompleteContent(it, state, actions, Modifier.padding(padding))
            else OutcomeContent(it, state, actions, Modifier.padding(padding))
            return@Scaffold
        }
        BrandBody(Page.MoneyMove, if (state.nothingToPick) WalletStatus.Empty else WalletStatus.Idle) {
            ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
                val preview = state.preview
                when {
                    preview != null -> ReviewContent(preview, state, actions)
                    else -> PickContent(state, actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickContent(state: MoneyMoveState, actions: MoneyMoveActions) {
    if (state.targets.isEmpty()) {
        val add = state.canAddMint
        val text =
            when {
                state.mode == MoneyMoveMode.ChangeHome -> if (add) R.string.money_change_no_mints_add else R.string.money_change_no_mints
                add -> R.string.money_move_no_mints_add
                else -> R.string.money_move_no_mints
            }
        // The page's column pads already.
        EmptyState(
            stringResource(text),
            padding = PaddingValues(),
            action = if (add) ({ PrimaryButton(stringResource(R.string.mints_add), actions.onAddMint) }) else null,
        )
        return
    }
    if (state.mode == MoneyMoveMode.MoveHome) Text(stringResource(R.string.money_move_all_body), textAlign = TextAlign.Center)
    else if (state.mode == MoneyMoveMode.Move)
        state.from?.let { Text(stringResource(R.string.money_move_from, it), textAlign = TextAlign.Center) }
    else Text(stringResource(R.string.money_change_body), textAlign = TextAlign.Center)
    MintPicker(state.targets, state.target, state.home, state.busy, actions.onTarget)
    state.error?.let { ErrorText(it) }
    if (state.offline) Text(stringResource(R.string.money_offline), textAlign = TextAlign.Center)
    PrimaryButton(stringResource(R.string.money_continue), actions.onReview, enabled = state.canContinue, busy = state.busy)
}

/** The known mints to pick from; a new one is added on the Mints page first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MintPicker(targets: List<String>, selected: String?, home: String?, busy: Boolean, onSelect: (String) -> Unit) {
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
        DropdownMenuItems(expanded, targets, home, { expanded = false }) { host ->
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
    home: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        targets.forEach { host ->
            DropdownMenuItem(
                text = { HostText(host) },
                onClick = { onSelect(host) },
                leadingIcon = { WalletMintMark(host, host == home) },
            )
        }
    }
}

@Composable
private fun ErrorText(error: MoneyMoveError) {
    val text =
        when (error) {
            is MoneyMoveError.Mint -> stringResource(R.string.money_error_mint, error.host)
            MoneyMoveError.Network -> stringResource(DesignR.string.couldnt_connect)
        }
    Text(text, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
}

/**
 * The summary: per mint, what arrives and the fee, or why it stays. The confirming button names the amount that moves, so nothing is paid
 * by mistake. Without collecting, the user may leave mints out (a section that starts closed).
 */
@Composable
private fun ReviewContent(preview: MovePreview, state: MoneyMoveState, actions: MoneyMoveActions) {
    Text(
        stringResource(R.string.money_review_to, preview.toHost),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    preview.lines.forEach { line -> Text(reviewLine(line, state), textAlign = TextAlign.Center) }
    if (state.canChoose) ChooseSection(state, actions.onToggle)
    if (state.chosenMsat > 0)
        Text(
            stringResource(R.string.money_review_fee_total, formatAmount(state.chosenFeeMsat)),
            style = MaterialTheme.typography.titleSmall,
        )
    val moving = state.chosenMsat
    if (state.offline) Text(stringResource(R.string.money_offline), textAlign = TextAlign.Center)
    when {
        !state.changingHome ->
            PrimaryButton(
                stringResource(R.string.money_move_amount, formatAmount(moving)),
                { actions.onConfirm(true) },
                enabled = moving > 0 && !state.offline,
                busy = state.busy,
            )
        moving > 0 -> {
            PrimaryButton(
                stringResource(R.string.money_change_and_move, formatAmount(moving)),
                { actions.onConfirm(true) },
                enabled = !state.offline,
                busy = state.busy,
            )
            if (!state.collects) {
                WalletButton(
                    kind = ButtonKind.Secondary,
                    onClick = { actions.onConfirm(false) },
                    enabled = !state.busy && !state.offline,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mint_change_home))
                }
                Text(
                    stringResource(R.string.money_review_stays_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        else ->
            PrimaryButton(
                stringResource(R.string.mint_change_home),
                { actions.onConfirm(false) },
                enabled = !state.offline,
                busy = state.busy,
            )
    }
    WalletButton(kind = ButtonKind.Text, onClick = actions.onBack, enabled = !state.busy) { Text(stringResource(R.string.money_back)) }
}

@Composable
private fun reviewLine(line: MovePreview.Line, state: MoneyMoveState): String {
    val amount = formatAmount(line.amountMsat)
    val cap = state.preview?.feeCapPercent ?: 0
    return when (val q = line.quote) {
        is MovePreview.Quote.Ok ->
            if (state.changingHome && state.collects && line.overCap(cap))
                stringResource(R.string.money_review_over_cap, amount, line.host, cap)
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

/**
 * All of it worked: the success screen. A new home says so (with what moved there along with it); a move says what arrived, where, and the
 * fees.
 */
@Composable
private fun CompleteContent(outcome: MoneyMoveOutcome, state: MoneyMoveState, actions: MoneyMoveActions, modifier: Modifier) {
    val arrived = outcome.moved.sumOf { (it.result as MintMoveResult.Moved).arrivedMsat }
    val fees = outcome.moved.sumOf { (it.result as MintMoveResult.Moved).feeMsat }
    val target = state.target.orEmpty()
    SuccessScreen(
        anchor = if (outcome.homeChanged) Anchor.HomeChanged else Anchor.MoneyMoved,
        status = WalletStatus.Done(arrived),
        title = stringResource(if (outcome.homeChanged) R.string.money_home_changed_title else R.string.money_moved_title),
        amountMsat = arrived.takeIf { outcome.moved.isNotEmpty() },
        done = SuccessAction(stringResource(DesignR.string.done), actions.onDone),
        modifier = modifier,
        lines =
            listOfNotNull(
                stringResource(if (outcome.homeChanged) R.string.money_home_changed else R.string.money_review_to, target),
                stringResource(R.string.money_result_money_stays).takeIf { outcome.moved.isEmpty() },
                fees.takeIf { it > 0 }?.let { stringResource(R.string.money_review_fee_total, formatAmount(it)) },
            ),
    )
}

/**
 * Not all of it done, on the result screen: the new home if there is one, then per mint what moved, waits or stayed and why, with another
 * try where one can help. A clock while some waits, a check mark when some arrived or the home changed, else a warning mark.
 */
@Composable
private fun OutcomeContent(outcome: MoneyMoveOutcome, state: MoneyMoveState, actions: MoneyMoveActions, modifier: Modifier) {
    val arrived = outcome.moved.sumOf { (it.result as? MintMoveResult.Moved)?.arrivedMsat ?: 0 }
    val tone =
        when {
            outcome.moved.any { it.result == MintMoveResult.Pending } -> ResultTone.Waiting
            outcome.homeChanged || outcome.moved.any { it.result is MintMoveResult.Moved } -> ResultTone.Success
            else -> ResultTone.Problem
        }
    ResultScreen(
        tone,
        Anchor.MoveResult,
        when (tone) {
            ResultTone.Success -> WalletStatus.Done(arrived)
            ResultTone.Waiting -> WalletStatus.None
            ResultTone.Problem -> WalletStatus.Problem(ProblemKind.Other)
        },
        when {
            outcome.homeChanged -> stringResource(R.string.money_home_changed_title)
            tone == ResultTone.Waiting -> stringResource(DesignR.string.no_answer_title)
            tone == ResultTone.Success -> stringResource(R.string.money_moved_title)
            else -> stringResource(R.string.money_nothing_moved_title)
        },
        done = SuccessAction(stringResource(DesignR.string.done), actions.onDone),
        modifier = modifier,
        more =
            SuccessAction(stringResource(DesignR.string.try_again), actions.onRetry).takeIf {
                outcome.retryable.isNotEmpty() && !state.busy
            },
        lines =
            listOfNotNull(
                stringResource(R.string.money_home_changed, state.target.orEmpty()).takeIf { outcome.homeChanged },
                stringResource(R.string.money_result_money_stays).takeIf { outcome.homeChanged && outcome.moved.isEmpty() },
            ) +
                outcome.moved.map { m ->
                    val amount = formatAmount(m.amountMsat)
                    when (val r = m.result) {
                        is MintMoveResult.Moved ->
                            stringResource(R.string.money_result_moved, formatAmount(r.arrivedMsat), m.host, formatAmount(r.feeMsat))
                        MintMoveResult.Pending -> stringResource(R.string.money_result_pending, amount, m.host)
                        is MintMoveResult.Failed -> stringResource(R.string.money_result_failed, amount, m.host, reason(r.problem))
                    }
                },
    ) {
        if (showsWorking(state.busy)) WalletLinearProgress(Modifier.fillMaxWidth())
    }
}

@Composable
private fun reason(problem: NoteProblem): String =
    stringResource(
        when (problem) {
            NoteProblem.PaymentFailed -> R.string.money_reason_payment
            NoteProblem.Declined -> R.string.money_reason_price
            NoteProblem.Unreachable -> R.string.money_reason_offline
            NoteProblem.KeyChanged -> R.string.money_reason_key_changed
            NoteProblem.NotFound -> R.string.money_reason_gone
            else -> R.string.money_reason_refused
        }
    )

/**
 * Where [mode] can take the money or the account: known mints only, home first (as Settings → Mints lists them), never the one the money or
 * account leaves, and only mints the brand [allowed] (`mints.custom`). While the account collects, money only moves home: collecting would
 * bring it back from anywhere else. Moving home is always to the home.
 */
internal fun moveTargets(
    mode: MoneyMoveMode,
    known: List<String>,
    home: String?,
    from: String?,
    collects: Boolean,
    allowed: (String) -> Boolean,
): List<String> =
    if (mode == MoneyMoveMode.MoveHome) listOfNotNull(home)
    else
        known
            .filter { it != (if (mode == MoneyMoveMode.Move) from else home) }
            .filter { mode == MoneyMoveMode.ChangeHome || !collects || it == home }
            .filter { it == home || allowed(it) }
