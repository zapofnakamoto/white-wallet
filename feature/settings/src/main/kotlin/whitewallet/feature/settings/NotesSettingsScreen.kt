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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import whitewallet.brand.NoteStrategy
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletFilterChip
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletRadioButton
import whitewallet.core.designsystem.WalletSwitch
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.MovePreview
import whitewallet.core.wallet.NotCollected
import whitewallet.core.wallet.PolicyRun
import whitewallet.core.wallet.Wallet

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
    /** The fee caps to pick from: the brand's, and [collectFeeCap] if it is another one. */
    val collectFeeCaps: List<Int> = emptyList(),
    val homeHost: String? = null,
    /** Payments take notes on other mints before those at [homeHost] (a wallet setting). */
    val payFromOtherMintsFirst: Boolean = false,
    val lastPolicyRun: PolicyRun? = null,
    /** A policy change is being applied (it may merge or rotate notes right away). */
    val applyingPolicy: Boolean = false,
    /** Turning collecting on: what it would move right away, and at what fee, to confirm first. */
    val collectPreview: MovePreview? = null,
    /** The brand lets users choose whether they are told what the wallet did to their notes by itself. */
    val noticesOffered: Boolean = false,
    val notices: Boolean = false,
)

/** The account's rules for its notes. The notes themselves are managed in the notes view (feature/notes). */
@HiltViewModel
class NotesSettingsViewModel @Inject constructor(private val wallet: Wallet, private val defaults: AppDefaults) : ViewModel() {
    private val applying = MutableStateFlow(false)
    private val collectPreview = MutableStateFlow<MovePreview?>(null)
    private val collect = combine(wallet.collectAtHome, wallet.collectFeeCap, ::Pair)
    private val home =
        combine(
            wallet.homeHost,
            wallet.maintenanceNoticesChoice.map(defaults.maintenanceNotices::active),
            wallet.payFromOtherMintsFirst,
            ::Triple,
        )

    val state: StateFlow<NotesSettingsState> =
        combine(
                wallet.noteStrategy,
                wallet.autoRotate,
                wallet.lastPolicyRun,
                combine(applying, collectPreview, ::Pair),
                combine(collect, home, ::Pair),
            ) { strategy, rotate, run, (busy, preview), (collect, home) ->
                NotesSettingsState(
                    noteStrategy = strategy,
                    strategies = wallet.noteStrategies,
                    autoRotate = rotate,
                    lastPolicyRun = run,
                    applyingPolicy = busy,
                    collectPreview = preview,
                    collectOffered = wallet.collectDefaults.offered,
                    collectAtHome = collect.first,
                    collectFeeCap = collect.second,
                    collectFeeCaps = wallet.collectDefaults.feeCapChoices(collect.second),
                    homeHost = home.first,
                    noticesOffered = defaults.maintenanceNotices.offered,
                    notices = home.second,
                    payFromOtherMintsFirst = home.third,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesSettingsState())

    fun setNoteStrategy(strategy: NoteStrategy) = applyPolicy { wallet.setNoteStrategy(strategy.id) }

    fun setAutoRotate(enabled: Boolean) = applyPolicy { wallet.setAutoRotate(enabled) }

    /** Off right away; on after the user saw what it would move now ([confirmCollect]), unless it would move nothing. */
    fun setCollectAtHome(enabled: Boolean) {
        if (!enabled) return applyPolicy { wallet.setCollectAtHome(false) }
        if (applying.value) return
        applying.value = true
        viewModelScope.launch {
            val preview = wallet.homeHost.first()?.let { wallet.previewMove(it) }
            if (preview == null || preview.lines.isEmpty()) {
                try {
                    wallet.setCollectAtHome(true)
                } finally {
                    applying.value = false
                }
            } else {
                collectPreview.value = preview
                applying.value = false
            }
        }
    }

    fun confirmCollect() {
        collectPreview.value = null
        applyPolicy { wallet.setCollectAtHome(true) }
    }

    fun dismissCollect() {
        collectPreview.value = null
    }

    fun setCollectFeeCap(percent: Int) = applyPolicy { wallet.setCollectFeeCap(percent) }

    fun setPayFromOtherMintsFirst(enabled: Boolean) {
        viewModelScope.launch { wallet.setPayFromOtherMintsFirst(enabled) }
    }

    fun setNotices(enabled: Boolean) {
        viewModelScope.launch { wallet.setMaintenanceNotices(enabled) }
    }

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
        state,
        onBack,
        onNoteStrategy = viewModel::setNoteStrategy,
        onAutoRotate = viewModel::setAutoRotate,
        collect =
            CollectActions(viewModel::setCollectAtHome, viewModel::setCollectFeeCap, viewModel::confirmCollect, viewModel::dismissCollect),
        onNotices = viewModel::setNotices,
        onPayFromOtherMintsFirst = viewModel::setPayFromOtherMintsFirst,
    )
}

class CollectActions(
    val onCollect: (Boolean) -> Unit = {},
    val onFeeCap: (Int) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * Note settings: the note strategy, collecting at the home mint, which mint pays first, auto-rotate, whether to be told what the wallet did
 * by itself, and what the last automatic run did.
 */
@Composable
fun NotesSettingsScreen(
    state: NotesSettingsState,
    onBack: () -> Unit,
    onNoteStrategy: (NoteStrategy) -> Unit,
    onAutoRotate: (Boolean) -> Unit,
    collect: CollectActions = CollectActions(),
    onNotices: (Boolean) -> Unit = {},
    onPayFromOtherMintsFirst: (Boolean) -> Unit = {},
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_notes), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            NotePolicySettings(state, onNoteStrategy, onAutoRotate)
            CollectSettings(state, collect)
            state.homeHost?.let { home ->
                WalletListItem(
                    headlineContent = { Text(stringResource(R.string.notes_pay_from_others_first)) },
                    supportingContent = { Text(stringResource(R.string.notes_pay_from_others_first_hint, home)) },
                    trailingContent = {
                        WalletSwitch(checked = state.payFromOtherMintsFirst, onCheckedChange = onPayFromOtherMintsFirst)
                    },
                    modifier = Modifier.clickable { onPayFromOtherMintsFirst(!state.payFromOtherMintsFirst) },
                )
            }
            if (state.noticesOffered) {
                WalletListItem(
                    headlineContent = { Text(stringResource(R.string.settings_notices)) },
                    supportingContent = { Text(stringResource(R.string.settings_notices_hint)) },
                    trailingContent = { WalletSwitch(checked = state.notices, onCheckedChange = onNotices) },
                    modifier = Modifier.clickable { onNotices(!state.notices) },
                )
            }
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
                leadingContent = {
                    WalletRadioButton(
                        selected = current.id == strategy.id,
                        onClick = { onNoteStrategy(strategy) },
                        enabled = !state.applyingPolicy,
                    )
                },
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
            Text(
                if (included) stringResource(R.string.settings_auto_renew_included, stringResource(current.title))
                else stringResource(R.string.settings_auto_renew_hint)
            )
        },
        trailingContent = {
            WalletSwitch(
                checked = included || state.autoRotate,
                onCheckedChange = onAutoRotate,
                enabled = !included && !state.applyingPolicy,
            )
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
        trailingContent = {
            WalletSwitch(checked = state.collectAtHome, onCheckedChange = actions.onCollect, enabled = !state.applyingPolicy)
        },
    )
    state.collectPreview?.let { CollectDialog(it, home, actions) }
    if (state.collectAtHome) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.notes_collect_fee_cap), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.collectFeeCaps.forEach { percent ->
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

/** What turning collecting on would move right away, per mint: within the fee cap it moves, otherwise it stays. */
@Composable
private fun CollectDialog(preview: MovePreview, home: String, actions: CollectActions) {
    WalletDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(R.string.notes_collect_title, home)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notes_collect_confirm_body))
                preview.lines.forEach { line ->
                    val amount = formatAmount(line.amountMsat)
                    Text(
                        when (val q = line.quote) {
                            is MovePreview.Quote.Ok ->
                                if (line.overCap(preview.feeCapPercent))
                                    stringResource(R.string.money_review_over_cap, amount, line.host, preview.feeCapPercent)
                                else
                                    stringResource(
                                        R.string.money_review_moves,
                                        amount,
                                        line.host,
                                        formatAmount(q.arrivesMsat),
                                        formatAmount(q.feeMsat),
                                    )
                            is MovePreview.Quote.TooSmall -> stringResource(R.string.money_review_too_small, amount, line.host)
                            is MovePreview.Quote.Unavailable -> stringResource(R.string.money_review_unavailable, amount, line.host)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            WalletButton(kind = ButtonKind.Text, onClick = actions.onConfirm) { Text(stringResource(R.string.notes_collect_confirm)) }
        },
        dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = actions.onDismiss) { Text(stringResource(R.string.money_back)) } },
    )
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
                is NotCollected.Reason.FeeTooHigh ->
                    Text(
                        stringResource(
                            R.string.notes_collect_fee_too_high,
                            formatAmount(n.amountMsat),
                            n.host,
                            formatAmount(reason.feeMsat),
                            reason.capPercent,
                        ),
                        style = small,
                    )
                is NotCollected.Reason.TooSmall ->
                    Text(stringResource(R.string.notes_collect_too_small, formatAmount(n.amountMsat), n.host), style = small)
                is NotCollected.Reason.Failed ->
                    Text(stringResource(R.string.notes_collect_failed, formatAmount(n.amountMsat), n.host), style = small)
            }
        }
        run.stoppedEarly?.let {
            Text(stringResource(R.string.settings_policy_stopped, it), style = small, color = MaterialTheme.colorScheme.error)
        }
    }
}
