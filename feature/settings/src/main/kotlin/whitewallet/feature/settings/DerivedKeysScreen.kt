package whitewallet.feature.settings

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import whitewallet.core.wallet.DiscoveryResult
import whitewallet.core.wallet.IndexHealth
import whitewallet.core.wallet.SetIndexResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.localTime
import whitewallet.core.designsystem.recentTime
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.DerivedKey
import whitewallet.core.wallet.KeyIndexes
import whitewallet.core.wallet.KeyProbe
import whitewallet.core.wallet.KeyScanResult
import whitewallet.core.wallet.KeyStatus
import whitewallet.core.wallet.RotateAllResult
import whitewallet.core.wallet.RotateResult
import whitewallet.core.wallet.Wallet

data class DerivedKeysState(
    val hosts: List<String> = emptyList(),
    /** The mint of the account's primary address. */
    val homeHost: String? = null,
    val host: String? = null,
    /** LUD-26 purpose: 0 wallet, 1 change, 2 Lightning Address. */
    val purpose: Int = 2,
    val indexes: KeyIndexes? = null,
    val keys: List<DerivedKey> = emptyList(),
    val expanded: Long? = null,
    val busy: Boolean = false,
    /** Gap limit for Scan ahead, Recheck all, Find index and the list; session only. */
    val gapLimit: Int = DEFAULT_GAP,
    /** The gap limit as typed. */
    val gapInput: String = DEFAULT_GAP.toString(),
    val health: IndexHealth? = null,
    /** The outcome of the last index action (scan ahead, recheck all, find, set). */
    val indexMessage: IndexMessage? = null,
    /** Non-null while the "Set index" dialog is open: the typed value. */
    val setIndexInput: String? = null,
    /** A rotate waiting for confirmation. */
    val rotateRequest: RotateRequest? = null,
    /** The outcome of the last rotate on this page. */
    val lastRotate: RotateOutcome? = null,
) {
    /** Keys on this tab whose note is outstanding in the wallet. */
    val rotatable: List<DerivedKey> get() = keys.filter { it.noteState == NoteState.Outstanding }

    /** The tab's one index: the next key the wallet will use (purpose 2: where the address scan starts). */
    val index: Long get() = indexes?.of(purpose) ?: 0
}

private const val DEFAULT_GAP = 20

sealed interface IndexMessage {
    data class Scanned(val result: KeyScanResult) : IndexMessage
    data class Found(val result: DiscoveryResult) : IndexMessage
    data class Set(val result: SetIndexResult) : IndexMessage
}

sealed interface RotateRequest {
    val nextWalletIndex: Long
    data class One(val key: DerivedKey, override val nextWalletIndex: Long) : RotateRequest
    data class All(val count: Int, val totalMsat: Long, override val nextWalletIndex: Long) : RotateRequest
}

sealed interface RotateOutcome {
    data class One(val result: RotateResult) : RotateOutcome
    data class All(val result: RotateAllResult) : RotateOutcome
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class DerivedKeysViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val _state = MutableStateFlow(DerivedKeysState())
    val state: StateFlow<DerivedKeysState> = _state

    init {
        viewModelScope.launch {
            val hosts = wallet.keyHosts()
            val home = wallet.homeHost.first()
            _state.update { it.copy(hosts = hosts, homeHost = home, host = hosts.firstOrNull()) }
            reload()
        }
        // Stay in sync with changes made anywhere else (refresh, auto-rotate, claims, other screens):
        // observe the same store Home observes, re-subscribing when the service changes.
        viewModelScope.launch {
            _state.map { it.host }.distinctUntilChanged().filterNotNull()
                .flatMapLatest { wallet.keyViewChanges(it) }
                .debounce(SYNC_DEBOUNCE_MILLIS)
                .collect { reload() }
        }
    }

    fun selectHost(host: String) = update { copy(host = host, expanded = null, lastRotate = null, indexMessage = null) }
    fun selectPurpose(purpose: Int) = update { copy(purpose = purpose, expanded = null, lastRotate = null, indexMessage = null) }
    fun toggle(index: Long) = _state.update { it.copy(expanded = if (it.expanded == index) null else index) }

    /** Accepts 1..[MAX_GAP]; the list and the action ranges follow it. */
    fun onGapInput(value: String) {
        val digits = value.filter(Char::isDigit).take(3)
        val gap = digits.toIntOrNull()?.takeIf { it in 1..MAX_GAP }
        if (gap == null) _state.update { it.copy(gapInput = digits) } else update { copy(gapInput = digits, gapLimit = gap) }
    }

    /** From the index until gap unused keys follow the last used one. */
    fun scanAhead() = action { s ->
        val result = wallet.scanKeysAhead(s.host!!, s.purpose, s.gapLimit)
        _state.update { it.copy(indexMessage = IndexMessage.Scanned(result)) }
    }

    /** From 0 until gap unused keys follow the last used one (at least index + gap). */
    fun recheckAll() = action { s ->
        val result = wallet.recheckAllKeys(s.host!!, s.purpose, s.gapLimit)
        _state.update { it.copy(indexMessage = IndexMessage.Scanned(result)) }
    }

    fun recheck(index: Long) = action { s -> wallet.recheckKey(s.host!!, s.purpose, index) }

    fun findIndex() = action { s ->
        val result = wallet.findKeyIndex(s.host!!, s.purpose, s.gapLimit)
        _state.update { it.copy(indexMessage = IndexMessage.Found(result)) }
    }

    fun openSetIndex() = _state.update { it.copy(setIndexInput = (it.indexes?.of(it.purpose) ?: 0).toString()) }
    fun onSetIndexInput(value: String) = _state.update { it.copy(setIndexInput = value.filter(Char::isDigit).take(9)) }
    fun dismissSetIndex() = _state.update { it.copy(setIndexInput = null) }

    fun confirmSetIndex() {
        val value = _state.value.setIndexInput?.toLongOrNull() ?: return
        _state.update { it.copy(setIndexInput = null) }
        action { s ->
            val result = wallet.setKeyIndex(s.host!!, s.purpose, value)
            _state.update { it.copy(indexMessage = IndexMessage.Set(result)) }
        }
    }

    fun askRotate(index: Long) {
        val s = _state.value
        val key = s.keys.firstOrNull { it.id.index == index && it.noteState == NoteState.Outstanding } ?: return
        viewModelScope.launch { _state.update { it.copy(rotateRequest = RotateRequest.One(key, wallet.nextWalletIndex(s.host!!))) } }
    }

    fun askRotateAll() {
        val s = _state.value
        val notes = s.rotatable.takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            _state.update { it.copy(rotateRequest = RotateRequest.All(notes.size, notes.sumOf { k -> k.noteAmountMsat ?: 0 }, wallet.nextWalletIndex(s.host!!))) }
        }
    }

    fun dismissRotate() = _state.update { it.copy(rotateRequest = null) }

    fun confirmRotate() {
        val request = _state.value.rotateRequest ?: return
        _state.update { it.copy(rotateRequest = null) }
        action { s ->
            val outcome = when (request) {
                is RotateRequest.One -> RotateOutcome.One(wallet.rotateKey(s.host!!, s.purpose, request.key.id.index))
                is RotateRequest.All -> RotateOutcome.All(wallet.rotateAllKeys(s.host!!, s.purpose))
            }
            _state.update { it.copy(lastRotate = outcome, expanded = null) }
        }
    }

    private fun update(change: DerivedKeysState.() -> DerivedKeysState) {
        _state.update(change)
        viewModelScope.launch { reload() }
    }

    private fun action(block: suspend (DerivedKeysState) -> Unit) {
        val s = _state.value
        if (s.busy || s.host == null) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block(s)
            } finally {
                reload()
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun reload() {
        val s = _state.value
        val host = s.host ?: return
        val indexes = wallet.keyIndexes(host)
        val health = wallet.keyIndexHealth(host, s.purpose)
        val keys = wallet.derivedKeys(host, s.purpose, s.gapLimit)
        _state.update { it.copy(indexes = indexes, keys = keys, health = health) }
    }

    private companion object {
        const val MAX_GAP = 200

        /** A rotate-all writes many rows; reload once they settle. */
        const val SYNC_DEBOUNCE_MILLIS = 150L
    }
}

@Composable
fun DerivedKeysRoute(onBack: () -> Unit, viewModel: DerivedKeysViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DerivedKeysScreen(
        state, onBack, viewModel::selectHost, viewModel::selectPurpose, viewModel::toggle, viewModel::recheck,
        IndexActions(
            viewModel::scanAhead, viewModel::recheckAll, viewModel::findIndex, viewModel::onGapInput,
            viewModel::openSetIndex, viewModel::onSetIndexInput, viewModel::confirmSetIndex, viewModel::dismissSetIndex,
        ),
        RotateActions(viewModel::askRotate, viewModel::askRotateAll, viewModel::confirmRotate, viewModel::dismissRotate),
    )
}

/** Developer page: the active account's derived note keys (public `Q`/`cp1` only), local indexes and lookups. */
@Composable
fun DerivedKeysScreen(
    state: DerivedKeysState,
    onBack: () -> Unit,
    onHost: (String) -> Unit,
    onPurpose: (Int) -> Unit,
    onToggle: (Long) -> Unit,
    onRecheck: (Long) -> Unit,
    index: IndexActions,
    rotate: RotateActions,
) {
    val purposes = listOf(R.string.keys_purpose_wallet, R.string.keys_purpose_change, R.string.keys_purpose_address)
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.keys_title), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (state.hosts.size > 1) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.hosts.forEach { h ->
                            val label = if (h == state.homeHost) stringResource(R.string.keys_home_mint, h) else h
                            FilterChip(selected = h == state.host, onClick = { onHost(h) }, label = { Text(label) })
                        }
                    }
                }
            }
            item {
                PrimaryTabRow(selectedTabIndex = state.purpose) {
                    purposes.forEachIndexed { purpose, label ->
                        Tab(selected = state.purpose == purpose, onClick = { onPurpose(purpose) }, text = { Text(stringResource(label)) })
                    }
                }
            }
            item { IndexCard(state, rotate.onAskAll, index) }
            if (state.host == null) item { Text(stringResource(R.string.keys_no_service), modifier = Modifier.padding(16.dp)) }
            items(state.keys, key = { it.id.index }) { key ->
                KeyRow(
                    key, expanded = state.expanded == key.id.index, busy = state.busy, onToggle = { onToggle(key.id.index) },
                    onRecheck = { onRecheck(key.id.index) }, onRotate = { rotate.onAsk(key.id.index) },
                )
                HorizontalDivider()
            }
        }
    }
    state.rotateRequest?.let { RotateDialog(it, purposes, rotate.onConfirm, rotate.onDismiss) }
    state.setIndexInput?.let { SetIndexDialog(it, (state.health?.highestUsed ?: -1) + 1, index) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndexCard(state: DerivedKeysState, onRotateAll: () -> Unit, index: IndexActions) {
    val n = state.index
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.keys_index, n), style = MaterialTheme.typography.titleLarge)
                    state.host?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedTextField(
                    value = state.gapInput,
                    onValueChange = index.onGapInput,
                    label = { Text(stringResource(R.string.keys_gap_limit)) },
                    isError = state.gapInput.toIntOrNull()?.let { it !in 1..200 } ?: true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                )
            }
            state.health?.let { HealthText(it) }
            state.indexMessage?.let { IndexMessageText(it) }
            state.lastRotate?.let { RotateOutcomeText(it) }
            val enabled = !state.busy && state.host != null
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = index.onScanAhead, enabled = enabled) { Text(stringResource(R.string.keys_scan_ahead, n)) }
                OutlinedButton(onClick = index.onRecheckAll, enabled = enabled) { Text(stringResource(R.string.keys_recheck_all)) }
                OutlinedButton(onClick = index.onFind, enabled = enabled) { Text(stringResource(R.string.keys_find_index)) }
                OutlinedButton(onClick = index.onOpenSet, enabled = enabled) { Text(stringResource(R.string.keys_set_index)) }
                if (state.rotatable.isNotEmpty()) {
                    OutlinedButton(onClick = onRotateAll, enabled = enabled) { Text(stringResource(R.string.keys_rotate_all, state.rotatable.size)) }
                }
            }
        }
    }
}

@Composable
private fun HealthText(health: IndexHealth) {
    val verified = health.lastVerifiedAtMillis?.let { stringResource(R.string.keys_verified_at, localTime(it, TIME_SKELETON)) }
        ?: stringResource(R.string.keys_not_verified)
    Text(verified, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (health.behind) {
        Text(stringResource(R.string.keys_behind, health.highestUsed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun IndexMessageText(message: IndexMessage) {
    val (text, error) = when (message) {
        is IndexMessage.Scanned -> message.result.let { r ->
            val line = stringResource(R.string.keys_scanned, r.from, r.until - 1, r.outstanding, r.imported, r.indexAfter)
            (r.stoppedEarly?.let { line + "\n" + stringResource(R.string.keys_scan_stopped, it) } ?: line) to (r.stoppedEarly != null)
        }
        is IndexMessage.Found -> message.result.let { r ->
            r.stoppedEarly?.let { stringResource(R.string.keys_scan_stopped, it) to true }
                ?: (stringResource(R.string.keys_found, r.next, r.checked, r.imported) to false)
        }
        is IndexMessage.Set -> when (val r = message.result) {
            is SetIndexResult.Applied -> stringResource(R.string.keys_set_applied, r.index) to false
            is SetIndexResult.BelowFloor -> stringResource(R.string.keys_set_below, r.floor) to true
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
}

/** What the index controls can ask for. */
class IndexActions(
    val onScanAhead: () -> Unit,
    val onRecheckAll: () -> Unit,
    val onFind: () -> Unit,
    val onGapInput: (String) -> Unit,
    val onOpenSet: () -> Unit,
    val onSetInput: (String) -> Unit,
    val onConfirmSet: () -> Unit,
    val onDismissSet: () -> Unit,
)

@Composable
private fun SetIndexDialog(input: String, floor: Long, actions: IndexActions) {
    val value = input.toLongOrNull()
    AlertDialog(
        onDismissRequest = actions.onDismissSet,
        title = { Text(stringResource(R.string.keys_set_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.keys_set_body, floor))
                OutlinedTextField(
                    value = input,
                    onValueChange = actions.onSetInput,
                    label = { Text(stringResource(R.string.keys_set_field)) },
                    isError = value != null && value < floor,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirmSet, enabled = value != null && value >= floor) { Text(stringResource(R.string.keys_set_apply)) }
        },
        dismissButton = { TextButton(onClick = actions.onDismissSet) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

/** What the rotate controls can ask for. */
class RotateActions(val onAsk: (Long) -> Unit, val onAskAll: () -> Unit, val onConfirm: () -> Unit, val onDismiss: () -> Unit)

@Composable
private fun RotateDialog(request: RotateRequest, purposes: List<Int>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body) = when (request) {
        is RotateRequest.One -> stringResource(R.string.keys_rotate_title) to stringResource(
            R.string.keys_rotate_body,
            formatAmount(request.key.noteAmountMsat ?: 0),
            request.key.id.index,
            stringResource(purposes[request.key.id.purpose]),
            request.nextWalletIndex,
        )
        is RotateRequest.All -> stringResource(R.string.keys_rotate_all_title, request.count) to
            stringResource(R.string.keys_rotate_all_body, formatAmount(request.totalMsat), request.nextWalletIndex)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.keys_rotate)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun RotateOutcomeText(outcome: RotateOutcome) {
    val (text, error) = when (outcome) {
        is RotateOutcome.One -> when (val r = outcome.result) {
            is RotateResult.Rotated -> stringResource(R.string.keys_rotated, formatAmount(r.amountMsat), r.fromIndex, r.toIndex) to false
            is RotateResult.Failed -> stringResource(R.string.keys_rotate_failed, r.reason ?: "") to true
            RotateResult.Pending -> stringResource(R.string.keys_rotate_pending) to false
            is RotateResult.NotRotatable -> stringResource(R.string.keys_rotate_not_possible, r.reason) to true
        }
        is RotateOutcome.All -> outcome.result.let { r ->
            val summary = stringResource(R.string.keys_rotated_all, r.rotated, formatAmount(r.totalMsat), r.failed, r.pending)
            (r.stoppedEarly?.let { summary + "\n" + stringResource(R.string.keys_scan_stopped, it) } ?: summary) to (r.failed > 0 || r.stoppedEarly != null)
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
}

@Composable
private fun KeyRow(
    key: DerivedKey,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRecheck: () -> Unit,
    onRotate: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("#${key.id.index}", fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp))
            Text(key.cp1.take(10) + "…" + key.cp1.takeLast(6), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            StatusText(key.status)
        }
        Text(statusHint(key.status, key.id.purpose), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        (key.failure ?: key.probe?.takeIf { it is KeyProbe.Refused })?.let {
            Text(lookupText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (expanded) {
            Text("Q   ${key.q}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(key.cp1, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (key.failure == null || key.probe != null) Text(lookupText(key.probe), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRecheck, enabled = !busy) { Text(stringResource(R.string.keys_recheck)) }
                if (key.noteState == NoteState.Outstanding) {
                    TextButton(onClick = onRotate, enabled = !busy) { Text(stringResource(R.string.keys_rotate)) }
                }
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("cp1", key.cp1))) } }) {
                    Text(stringResource(R.string.logs_copy))
                }
            }
        }
    }
}

@Composable
private fun StatusText(status: KeyStatus) {
    val (text, color) = when (status) {
        is KeyStatus.Funded -> formatAmount(status.amountMsat) to MaterialTheme.colorScheme.primary
        KeyStatus.Confirming -> stringResource(R.string.keys_status_confirming) to MaterialTheme.colorScheme.secondary
        KeyStatus.Spent -> stringResource(R.string.keys_status_spent) to MaterialTheme.colorScheme.tertiary
        KeyStatus.Skipped -> stringResource(R.string.keys_status_skipped) to MaterialTheme.colorScheme.onSurfaceVariant
        KeyStatus.HandedOut -> stringResource(R.string.keys_status_handed_out) to MaterialTheme.colorScheme.onSurfaceVariant
        KeyStatus.Next -> stringResource(R.string.keys_status_next) to MaterialTheme.colorScheme.primary
        KeyStatus.Free -> stringResource(R.string.keys_status_free) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun statusHint(status: KeyStatus, purpose: Int): String = stringResource(
    when (status) {
        is KeyStatus.Funded -> R.string.keys_hint_funded
        KeyStatus.Confirming -> R.string.keys_hint_confirming
        KeyStatus.Spent -> R.string.keys_hint_spent
        KeyStatus.Skipped -> R.string.keys_hint_skipped
        KeyStatus.HandedOut -> R.string.keys_hint_handed_out
        KeyStatus.Next -> if (purpose == ADDRESS_PURPOSE) R.string.keys_hint_next_address else R.string.keys_hint_next
        KeyStatus.Free -> R.string.keys_hint_free
    },
)

/** What the service said at the last lookup this session. */
@Composable
private fun lookupText(probe: KeyProbe?): String {
    if (probe == null) return stringResource(R.string.keys_lookup_none)
    val time = checkedAt(probe.checkedAtMillis)
    val answer = when (probe) {
        is KeyProbe.Outstanding -> stringResource(
            R.string.keys_detail_outstanding,
            formatAmount(probe.amountMsat),
            stringResource(if (probe.certified) R.string.keys_certified else R.string.keys_uncertified),
        ) + if (probe.imported) " · " + stringResource(R.string.keys_imported) else ""
        is KeyProbe.Spent -> stringResource(R.string.keys_status_spent)
        is KeyProbe.Unused -> stringResource(R.string.keys_lookup_unused)
        is KeyProbe.Refused -> stringResource(R.string.keys_lookup_refused, probe.reason ?: "")
        is KeyProbe.Failed -> stringResource(R.string.keys_lookup_failed, probe.reason)
    }
    return stringResource(R.string.keys_lookup, answer, time)
}

/** Time of day for today's lookups; date and time for older (saved) ones. */
private fun checkedAt(millis: Long): String = recentTime(millis)

private const val ADDRESS_PURPOSE = 2

private const val TIME_SKELETON = "jm"
