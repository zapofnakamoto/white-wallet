package whitewallet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.brand.NoteStrategy
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.CollectDefaults
import whitewallet.core.wallet.NotCollected
import whitewallet.core.wallet.PolicyRun
import whitewallet.core.wallet.Wallet
import whitewallet.core.designsystem.WalletFilterChip
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletRadioButton
import whitewallet.core.designsystem.WalletSwitch

data class NotesSettingsState(
    /** The account's note strategy; null until loaded. */
    val noteStrategy: NoteStrategy? = null,
    /** The strategies the brand offers, in its order. */
    val strategies: List<NoteStrategy> = emptyList(),
    val autoRotate: Boolean = false,
    /** The brand offers "collect at home mint". */
    val collectOffered: Boolean = false,
    /** Notes on other mints move to [homeHost] automatically, when the fee is at most [collectFeeCap] percent. */
    val collectAtHome: Boolean = false,
    val collectFeeCap: Int = 0,
    val homeHost: String? = null,
    val lastPolicyRun: PolicyRun? = null,
    /** A policy change is being applied (it may merge or rotate notes right away). */
    val applyingPolicy: Boolean = false,
)

/** The account's rules for its notes. The notes themselves are managed in the notes view (feature/notes). */
@HiltViewModel
class NotesSettingsViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val applying = MutableStateFlow(false)
    private val collect = combine(wallet.collectAtHome, wallet.collectFeeCap, ::Pair)

    val state: StateFlow<NotesSettingsState> =
        combine(wallet.noteStrategy, wallet.autoRotate, wallet.lastPolicyRun, applying, combine(collect, wallet.homeHost, ::Pair)) { strategy, rotate, run, busy, (collect, home) ->
            NotesSettingsState(
                noteStrategy = strategy, strategies = wallet.noteStrategies, autoRotate = rotate, lastPolicyRun = run, applyingPolicy = busy,
                collectOffered = wallet.collectDefaults.offered, collectAtHome = collect.first, collectFeeCap = collect.second, homeHost = home,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesSettingsState())

    fun setNoteStrategy(strategy: NoteStrategy) = applyPolicy { wallet.setNoteStrategy(strategy.id) }

    fun setAutoRotate(enabled: Boolean) = applyPolicy { wallet.setAutoRotate(enabled) }

    fun setCollectAtHome(enabled: Boolean) = applyPolicy { wallet.setCollectAtHome(enabled) }

    fun setCollectFeeCap(percent: Int) = applyPolicy { wallet.setCollectFeeCap(percent) }

    private fun applyPolicy(block: suspend () -> Unit) {
        if (applying.value) return
        applying.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                applying.value = false
            }
        }
    }
}

@Composable
fun NotesSettingsRoute(onBack: () -> Unit, viewModel: NotesSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NotesSettingsScreen(
        state, onBack, viewModel::setNoteStrategy, viewModel::setAutoRotate,
        CollectActions(viewModel::setCollectAtHome, viewModel::setCollectFeeCap),
    )
}

class CollectActions(
    val onCollect: (Boolean) -> Unit = {},
    val onFeeCap: (Int) -> Unit = {},
)

/** Note settings: the note strategy, collecting at the home mint, auto-rotate, and what the last automatic run did. */
@Composable
fun NotesSettingsScreen(
    state: NotesSettingsState,
    onBack: () -> Unit,
    onNoteStrategy: (NoteStrategy) -> Unit,
    onAutoRotate: (Boolean) -> Unit,
    collect: CollectActions = CollectActions(),
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_notes), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            NotePolicySettings(state, onNoteStrategy, onAutoRotate)
            CollectSettings(state, collect)
            LastRun(state)
        }
    }
}

@Composable
private fun NotePolicySettings(state: NotesSettingsState, onNoteStrategy: (NoteStrategy) -> Unit, onAutoRotate: (Boolean) -> Unit) {
    val current = state.noteStrategy ?: return
    // A brand that offers a single strategy leaves nothing to choose.
    if (state.strategies.size > 1) {
        Text(
            stringResource(R.string.settings_note_strategy),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
        )
        state.strategies.forEach { strategy ->
            WalletListItem(
                leadingContent = { WalletRadioButton(selected = current.id == strategy.id, onClick = { onNoteStrategy(strategy) }, enabled = !state.applyingPolicy) },
                headlineContent = { Text(stringResource(strategy.title)) },
                supportingContent = { Text(stringResource(strategy.description)) },
                modifier = Modifier.clickable(enabled = !state.applyingPolicy) { onNoteStrategy(strategy) },
            )
        }
    }
    val included = current.rotatesAddressNotes
    WalletListItem(
        headlineContent = { Text(stringResource(R.string.settings_auto_renew)) },
        supportingContent = {
            Text(if (included) stringResource(R.string.settings_auto_renew_included, stringResource(current.title)) else stringResource(R.string.settings_auto_renew_hint))
        },
        trailingContent = {
            WalletSwitch(checked = included || state.autoRotate, onCheckedChange = onAutoRotate, enabled = !included && !state.applyingPolicy)
        },
    )
}

/** "Collect at home mint": the switch, and the fee cap while it is on. Hidden if the brand doesn't offer it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectSettings(state: NotesSettingsState, actions: CollectActions) {
    val home = state.homeHost ?: return
    if (!state.collectOffered) return
    WalletListItem(
        headlineContent = { Text(stringResource(R.string.notes_collect_title, home)) },
        supportingContent = { Text(stringResource(R.string.notes_collect_hint)) },
        trailingContent = { WalletSwitch(checked = state.collectAtHome, onCheckedChange = actions.onCollect, enabled = !state.applyingPolicy) },
    )
    if (state.collectAtHome) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.notes_collect_fee_cap), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (CollectDefaults.FEE_CAPS + state.collectFeeCap).distinct().sorted().forEach { percent ->
                    WalletFilterChip(
                        selected = percent == state.collectFeeCap,
                        onClick = { actions.onFeeCap(percent) },
                        label = { Text(stringResource(R.string.notes_collect_fee_cap_option, percent)) },
                        enabled = !state.applyingPolicy,
                    )
                }
            }
        }
    }
}

/** What the last automatic run did: the counts, and per mint what collecting moved or left ("Move anyway" is on the Notes page). */
@Composable
private fun LastRun(state: NotesSettingsState) {
    if (state.applyingPolicy) {
        WalletLinearProgress(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
    val run = state.lastPolicyRun ?: return
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (run.merged + run.split + run.rotated + run.failed > 0) {
            Text(stringResource(R.string.settings_policy_run, run.merged, run.split, run.rotated, run.failed), style = small)
        }
        run.collected.forEach { c ->
            Text(
                if (c.pending) stringResource(R.string.notes_collect_pending, c.host)
                else stringResource(R.string.notes_collect_moved, formatAmount(c.arrivedMsat), c.host, formatAmount(c.feeMsat)),
                style = small,
            )
        }
        run.notCollected.forEach { n ->
            when (val reason = n.reason) {
                is NotCollected.Reason.FeeTooHigh -> Text(
                    stringResource(R.string.notes_collect_fee_too_high, formatAmount(n.amountMsat), n.host, formatAmount(reason.feeMsat), reason.capPercent),
                    style = small,
                )
                is NotCollected.Reason.TooSmall -> Text(stringResource(R.string.notes_collect_too_small, formatAmount(n.amountMsat), n.host), style = small)
                is NotCollected.Reason.Failed -> Text(stringResource(R.string.notes_collect_failed, formatAmount(n.amountMsat), n.host), style = small)
            }
        }
        run.stoppedEarly?.let { Text(stringResource(R.string.settings_policy_stopped, it), style = small, color = MaterialTheme.colorScheme.error) }
    }
}
