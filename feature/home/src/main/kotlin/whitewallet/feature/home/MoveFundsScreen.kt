package whitewallet.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.MoveFailure
import whitewallet.core.wallet.MoveResult
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletRadioButton
import whitewallet.core.designsystem.WalletSwitch
import whitewallet.core.designsystem.WalletTextField

@Composable
fun MoveFundsRoute(onBack: () -> Unit, viewModel: MoveFundsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoveFundsScreen(state, onBack, viewModel::onTarget, viewModel::onEverything, viewModel::onAmount, viewModel::move)
}

@Composable
fun MoveFundsScreen(
    state: MoveFundsState,
    onBack: () -> Unit,
    onTarget: (String) -> Unit,
    onEverything: (Boolean) -> Unit,
    onAmount: (String) -> Unit,
    onMove: () -> Unit,
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.move_title), onBack) }) { padding ->
        ScreenColumn(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            state.result?.let { result ->
                ResultText(result)
                PrimaryButton(stringResource(R.string.move_done), onBack)
                return@ScreenColumn
            }
            val from = state.from ?: return@ScreenColumn
            Text(stringResource(R.string.move_from, from.account.name, formatAmount(from.balanceMsat)), modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.move_to), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            state.targets.forEach { target ->
                WalletListItem(
                    leadingContent = { WalletRadioButton(selected = target.account.id == state.targetId, onClick = { onTarget(target.account.id) }) },
                    headlineContent = { Text(target.account.name) },
                    supportingContent = { Text(target.profile?.address ?: target.account.host) },
                    modifier = Modifier.clickable { onTarget(target.account.id) },
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
            Text(stringResource(R.string.move_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            PrimaryButton(stringResource(R.string.move_action), onMove, enabled = state.canMove, busy = state.busy)
        }
    }
}

@Composable
private fun ResultText(result: MoveResult) {
    val (text, error) = when (result) {
        is MoveResult.Moved -> stringResource(R.string.move_moved, formatAmount(result.amountMsat)) to false
        MoveResult.Pending -> stringResource(R.string.move_pending) to false
        is MoveResult.Failed -> when (result.reason) {
            MoveFailure.InsufficientFunds -> stringResource(R.string.move_insufficient)
            MoveFailure.SplitAcrossMints -> stringResource(R.string.move_split_mints)
            MoveFailure.NothingToMove -> stringResource(R.string.move_nothing)
            else -> stringResource(R.string.move_failed, result.detail ?: result.reason.name)
        } to true
    }
    Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}
