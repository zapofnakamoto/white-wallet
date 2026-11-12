package whitewallet.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletAccountMark
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletRadioButton
import whitewallet.core.designsystem.WalletSwitch
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.MoveCheck
import whitewallet.core.wallet.MoveFailure
import whitewallet.core.wallet.MovePending
import whitewallet.core.wallet.MoveResult
import whitewallet.core.wallet.MoveStayed
import whitewallet.core.wallet.StayReason

@Composable
fun MoveFundsRoute(onBack: () -> Unit, onMint: (host: String) -> Unit, viewModel: MoveFundsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoveFundsScreen(
        state,
        onBack,
        onTarget = viewModel::onTarget,
        onEverything = viewModel::onEverything,
        onAmount = viewModel::onAmount,
        onMove = viewModel::move,
        onMint = onMint,
        onConfirmPartial = viewModel::confirmPartial,
        onCancelPartial = viewModel::cancelPartial,
    )
}

@Composable
fun MoveFundsScreen(
    state: MoveFundsState,
    onBack: () -> Unit,
    onTarget: (String) -> Unit,
    onEverything: (Boolean) -> Unit,
    onAmount: (String) -> Unit,
    onMove: () -> Unit,
    onMint: (host: String) -> Unit = {},
    onConfirmPartial: () -> Unit = {},
    onCancelPartial: () -> Unit = {},
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.move_title), onBack) }) { padding ->
        ScreenColumn(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            // The account the money leaves and the wallet it goes to, as the form names them.
            val fromName =
                state.from?.account?.let {
                    if (state.showWallets) stringResource(R.string.home_account_in_wallet, walletLabel(state, it.walletId), it.name)
                    else it.name
                } ?: ""
            val toWallet =
                state.targets.firstOrNull { it.account.id == state.targetId }?.let { walletLabel(state, it.account.walletId) } ?: ""
            // A move that can't be whole is the user's to decide, before any of it is made.
            state.partial?.let { PartialDialog(it, fromName, toWallet, state.showMints, onConfirmPartial, onCancelPartial) }
            state.result?.let { result ->
                ResultText(result)
                // What was sent without an answer is out of the balance until a refresh finds out: nothing the user can do but wait.
                WaitingLines(
                    result.pending,
                    state.showMints,
                    if (result is MoveResult.Pending) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                )
                val stayed = result.stayed
                // What didn't move is safe where it was: said with the amount, why, and what the user can do about it.
                if (result !is MoveResult.Failed && stayed.isNotEmpty()) {
                    Text(
                        stringResource(R.string.move_stayed, formatAmount(stayed.sumOf { it.amountMsat }), fromName),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                }
                StayedReasons(stayed, toWallet, state.showMints, TextAlign.Center)
                // A mint that didn't answer may answer now: the same move again takes what stayed.
                val retry = stayed.any { it.reason == StayReason.Unreachable }
                if (retry) PrimaryButton(stringResource(R.string.move_retry), onMove, busy = state.busy)
                // A signing key the mint changed stays so until the user looked at the mint: its page is this wallet's. (A key the other
                // wallet differs on is looked at there, as the reason says: only the wallet in use shows its mints.)
                if (state.showMints) {
                    stayed
                        .filter { it.reason == StayReason.KeyChanged }
                        .map { it.host }
                        .distinct()
                        .forEach { host ->
                            WalletButton(kind = ButtonKind.Secondary, onClick = { onMint(host) }) {
                                Text(stringResource(R.string.move_mint_details, host))
                            }
                        }
                }
                if (retry) WalletButton(kind = ButtonKind.Text, onClick = onBack) { Text(stringResource(R.string.move_done)) }
                else PrimaryButton(stringResource(R.string.move_done), onBack)
                return@ScreenColumn
            }
            val from = state.from ?: return@ScreenColumn
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                WalletAccountMark(from.account.name, state.places[from.account.id] ?: 0, size = 24.dp)
                val name =
                    if (state.showWallets)
                        stringResource(R.string.home_account_in_wallet, walletLabel(state, from.account.walletId), from.account.name)
                    else from.account.name
                Text(stringResource(R.string.move_from, name, formatAmount(from.balanceMsat)), modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.move_to), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            // Where the money goes stays an explicit radio choice; the mark only says which account it is.
            var showOthers by rememberSaveable { mutableStateOf(false) }
            val folded = state.foldOthers && !showOthers
            Column(Modifier.selectableGroup()) {
                state.targetsByWallet.forEach { (walletId, targets) ->
                    if (folded && walletId != from.account.walletId) return@forEach
                    if (state.showWallets) {
                        Text(
                            walletLabel(state, walletId),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        )
                    }
                    targets.forEach { target ->
                        val selected = target.account.id == state.targetId
                        WalletListItem(
                            leadingContent = { WalletAccountMark(target.account.name, state.places[target.account.id] ?: 0) },
                            headlineContent = { Text(target.account.name) },
                            trailingContent = { WalletRadioButton(selected = selected, onClick = null) },
                            modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { onTarget(target.account.id) },
                        )
                    }
                }
            }
            // The other wallets stay folded away until asked for, as in the account switcher.
            if (folded) {
                val others = state.targetsByWallet.size - 1
                WalletListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.accounts_other_wallets, others, others)) },
                    trailingContent = { Icon(painterResource(DesignR.drawable.ic_arrow_drop_down), contentDescription = null) },
                    modifier = Modifier.clickable(role = Role.Button) { showOthers = true },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.move_everything), modifier = Modifier.weight(1f))
                WalletSwitch(checked = state.everything, onCheckedChange = onEverything)
            }
            if (!state.everything) {
                WalletTextField(
                    value = state.amountSats,
                    onValueChange = onAmount,
                    label = { Text(stringResource(R.string.move_amount)) },
                    suffix = { Text("sats") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // A wallet that takes a note at a mint it doesn't have gets that mint in its list: said before the money goes there.
            state.targets
                .firstOrNull { it.account.id == state.targetId }
                ?.let { target ->
                    val wallet = walletLabel(state, target.account.walletId)
                    state.addsMints.forEach { host ->
                        Text(
                            stringResource(R.string.move_adds_mint, host, wallet),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            Text(stringResource(R.string.move_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            PrimaryButton(stringResource(R.string.move_action), onMove, enabled = state.canMove, busy = state.busy)
        }
    }
}

/** A wallet by the name the user gave it, else its number. */
@Composable
private fun walletLabel(state: MoveFundsState, walletId: String): String =
    state.walletNames[walletId] ?: stringResource(R.string.accounts_wallet, state.walletNumbers[walletId] ?: 1)

/** What a move left where it was, per mint; nothing for a move that was whole or didn't fail at a mint. */
private val MoveResult.stayed: List<MoveStayed>
    get() =
        when (this) {
            is MoveResult.Moved -> stayed
            is MoveResult.Failed -> stayed
            is MoveResult.Pending -> stayed
        }

/** What a move sent without getting an answer, per mint. */
private val MoveResult.pending: List<MovePending>
    get() =
        when (this) {
            is MoveResult.Moved -> pending
            is MoveResult.Failed -> emptyList()
            is MoveResult.Pending -> pending
        }

/** What is waiting for its mint's answer, a line per mint; where mints aren't named, one line for all of it. */
@Composable
private fun WaitingLines(pending: List<MovePending>, showMints: Boolean, style: TextStyle) {
    if (pending.isEmpty()) return
    val lines =
        if (showMints) pending.map { stringResource(R.string.move_waiting, it.host, formatAmount(it.amountMsat)) }
        else listOf(stringResource(R.string.move_waiting_unnamed, formatAmount(pending.sumOf { it.amountMsat })))
    lines.forEach { Text(it, style = style, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
}

/**
 * Why money stayed (or would stay), a line per mint; where mints aren't named, a line per kind of reason. [toWallet] is the wallet it was
 * to go to, by its name.
 */
@Composable
private fun StayedReasons(stayed: List<MoveStayed>, toWallet: String, showMints: Boolean, align: TextAlign) {
    val lines =
        if (showMints)
            stayed.map {
                when (it.reason) {
                    StayReason.Unreachable -> stringResource(R.string.move_stayed_unreachable, it.host)
                    StayReason.KeyChanged -> stringResource(R.string.move_stayed_key, it.host)
                    StayReason.OtherWalletKey -> stringResource(R.string.move_stayed_other_key, it.host, toWallet)
                    StayReason.Refused -> stringResource(R.string.move_stayed_refused, it.host)
                }
            }
        else
            stayed
                .map { it.reason == StayReason.Unreachable }
                .distinct()
                .sortedDescending()
                .map {
                    stringResource(if (it) R.string.move_stayed_unreachable_unnamed else R.string.move_stayed_blocked_unnamed)
                }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = align, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun PartialDialog(
    check: MoveCheck,
    fromName: String,
    toWallet: String,
    showMints: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    WalletDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.move_partial_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.move_partial_body, formatAmount(check.stayed.sumOf { it.amountMsat }), fromName))
                StayedReasons(check.stayed, toWallet, showMints, TextAlign.Start)
            }
        },
        confirmButton = {
            WalletButton(onClick = onConfirm) { Text(stringResource(R.string.move_partial_confirm, formatAmount(check.movableMsat))) }
        },
        dismissButton = {
            WalletButton(kind = ButtonKind.Text, onClick = onCancel) { Text(stringResource(R.string.move_partial_cancel)) }
        },
    )
}

@Composable
private fun ResultText(result: MoveResult) {
    val (text, error) =
        when (result) {
            is MoveResult.Moved -> stringResource(R.string.move_moved, formatAmount(result.amountMsat)) to false
            // Nothing is known to have moved: what is waiting says it all.
            is MoveResult.Pending -> return
            is MoveResult.Failed ->
                when (result.reason) {
                    MoveFailure.InsufficientFunds -> stringResource(R.string.move_insufficient) to true
                    MoveFailure.SplitAcrossMints -> stringResource(R.string.move_split_mints) to true
                    MoveFailure.NothingToMove -> stringResource(R.string.move_nothing) to true
                    // A mint was why: the money is safe where it was, and the reasons follow.
                    else if result.stayed.isNotEmpty() -> stringResource(R.string.move_none) to false
                    else -> stringResource(R.string.move_failed, result.detail ?: result.reason.name) to true
                }
        }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}
