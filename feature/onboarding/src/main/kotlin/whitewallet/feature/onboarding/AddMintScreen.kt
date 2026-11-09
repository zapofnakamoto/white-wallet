package whitewallet.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.MintPreview
import whitewallet.core.wallet.ScanProgress

/**
 * Adding a mint: in a wallet's setup ([setup]), from the main screen or from Settings → Mints. Pick it, see what it is (nothing saved yet),
 * add it while the wallet looks for what its phrase holds there, then see what was found. [onFinish] says where to go on.
 */
@Composable
fun AddMintRoute(setup: Boolean, onFinish: (AddMintFinish) -> Unit, viewModel: AddMintViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.init(setup) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finish) { state.finish?.let(onFinish) }
    AddMintScreen(
        state,
        AddMintActions(
            onBack = viewModel::back,
            mint = MintActions(viewModel::onMintSelected, viewModel::onCustomHostChange),
            onNext = viewModel::next,
            onAdd = viewModel::add,
            onDone = viewModel::done,
            onSkip = viewModel::skip,
            onAbandon = viewModel::abandon,
            onKeep = viewModel::keepSetup,
        ),
    )
}

class AddMintActions(
    val onBack: () -> Unit = {},
    val mint: MintActions = MintActions({}, {}),
    val onNext: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onAbandon: () -> Unit = {},
    val onKeep: () -> Unit = {},
)

@Composable
fun AddMintScreen(state: AddMintState, actions: AddMintActions) {
    BackHandler(enabled = state.step != AddMintStep.Adding) { actions.onBack() }
    if (state.confirmAbandon) AbandonDialog(actions)
    val title =
        stringResource(
            when (state.step) {
                AddMintStep.Adding -> R.string.add_mint_adding_title
                AddMintStep.Result -> if (state.failed == null) R.string.add_mint_done_title else R.string.add_mint_failed_title
                else -> if (state.setup) R.string.add_mint_setup_title else R.string.add_mint_title
            }
        )
    val back = actions.onBack.takeIf { state.step == AddMintStep.Pick || state.step == AddMintStep.Info }
    Scaffold(topBar = { WalletTopBar(title, back) }) { padding ->
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            when (state.step) {
                AddMintStep.Pick -> PickContent(state, actions)
                AddMintStep.Info -> state.preview?.let { InfoContent(it, state, actions) }
                AddMintStep.Adding -> AddingContent(state.progress)
                AddMintStep.Result -> ResultContent(state, actions.onDone)
            }
        }
    }
}

@Composable
private fun PickContent(state: AddMintState, actions: AddMintActions) {
    Text(stringResource(if (state.setup) R.string.add_mint_setup_body else R.string.add_mint_body), textAlign = TextAlign.Center)
    if (state.showMintPicker) MintPicker(state.mintOptions, state.selectedMint, state.customHost, state.busy, actions.mint)
    else Text(state.host, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    state.error?.let { error ->
        Text(
            when (error) {
                AddMintError.NotAllowed -> stringResource(R.string.add_mint_error_not_allowed)
                is AddMintError.Unreachable -> stringResource(R.string.add_mint_error_unreachable, error.detail)
                is AddMintError.Invalid -> stringResource(R.string.add_mint_error_invalid, error.detail)
            },
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
    PrimaryButton(stringResource(R.string.add_mint_next), actions.onNext, enabled = state.canNext, busy = state.busy)
    if (state.canSkip) {
        WalletButton(kind = ButtonKind.Text, onClick = actions.onSkip, enabled = !state.busy) {
            Text(stringResource(R.string.onboarding_skip_mint))
        }
        Hint(stringResource(R.string.onboarding_skip_mint_hint))
    }
}

/** What the mint says about itself, before anything is saved: the user decides here. */
@Composable
private fun InfoContent(preview: MintPreview.Ready, state: AddMintState, actions: AddMintActions) {
    Text(preview.host, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    preview.description?.let { Text(it, textAlign = TextAlign.Center) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoRow(stringResource(R.string.add_mint_fees), fees(preview.feeBaseMsat, preview.feePpm))
        InfoRow(
            stringResource(R.string.add_mint_limits),
            stringResource(R.string.add_mint_limits_value, formatAmount(preview.minSendableMsat), formatAmount(preview.maxSendableMsat)),
        )
        InfoRow(
            stringResource(R.string.add_mint_address_fee),
            preview.addressFeeMsat?.let { formatAmount(it) } ?: stringResource(R.string.add_mint_address_free),
        )
        InfoRow(stringResource(R.string.add_mint_key), preview.signingKey?.let(::shortKey) ?: stringResource(R.string.add_mint_key_none))
    }
    if (preview.knownKey != null || preview.host in state.known) Hint(stringResource(R.string.add_mint_known))
    if (preview.legacy) Hint(stringResource(R.string.add_mint_legacy))
    Hint(stringResource(R.string.onboarding_mint_custody))
    PrimaryButton(stringResource(if (state.setup) R.string.add_mint_use else R.string.add_mint_add), actions.onAdd)
    WalletButton(kind = ButtonKind.Text, onClick = actions.onBack) { Text(stringResource(R.string.add_mint_other)) }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun fees(baseMsat: Long?, ppm: Long?): String {
    if (baseMsat == null && ppm == null) return stringResource(R.string.add_mint_fees_unknown)
    val percent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 3 }.format((ppm ?: 0) / 10_000.0)
    return stringResource(R.string.add_mint_fees_value, formatAmount(baseMsat ?: 0), percent)
}

private fun shortKey(key: String): String = if (key.length <= 20) key else "${key.take(8)}…${key.takeLast(8)}"

/** While the scan looks: per account, how many keys it looked up and what it found so far. */
@Composable
private fun AddingContent(progress: ScanProgress?) {
    Text(stringResource(R.string.add_mint_adding_body), textAlign = TextAlign.Center)
    WalletLinearProgress(Modifier.fillMaxWidth())
    progress?.accounts?.forEach { account ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.add_mint_account, account.index + 1), style = MaterialTheme.typography.titleSmall)
                Text(
                    pluralStringResource(R.plurals.add_mint_keys_checked, account.keysChecked, account.keysChecked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (account.foundMsat > 0) formatAmount(account.foundMsat)
                else stringResource(if (account.done) R.string.add_mint_account_empty else R.string.add_mint_account_looking),
                color = if (account.foundMsat > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultContent(state: AddMintState, onDone: () -> Unit) {
    val failed = state.failed
    when {
        failed != null -> {
            Text(
                if (state.keyChanged) stringResource(R.string.add_mint_failed_key)
                else stringResource(R.string.add_mint_failed_body, failed),
                textAlign = TextAlign.Center,
            )
            if (state.found.isNotEmpty()) Hint(stringResource(R.string.add_mint_failed_kept, formatAmount(state.foundMsat)))
        }
        state.progress == null -> Text(stringResource(R.string.add_mint_added_body), textAlign = TextAlign.Center)
        state.found.isEmpty() -> Text(stringResource(R.string.add_mint_found_none), textAlign = TextAlign.Center)
        else -> {
            Text(
                stringResource(R.string.add_mint_found_total, formatAmount(state.foundMsat)),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(pluralStringResource(R.plurals.add_mint_found_accounts, state.found.size, state.found.size), textAlign = TextAlign.Center)
            state.found.forEach { account ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(account.name)
                    Text(formatAmount(account.amountMsat), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    PrimaryButton(stringResource(if (failed != null) R.string.add_mint_try_again else R.string.add_mint_ok), onDone)
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

/** Giving a setup up removes the wallet from this phone; its money stays with the recovery phrase. */
@Composable
private fun AbandonDialog(actions: AddMintActions) {
    WalletDialog(
        onDismissRequest = actions.onKeep,
        title = { Text(stringResource(R.string.setup_abandon_title)) },
        text = { Text(stringResource(R.string.setup_abandon_body)) },
        confirmButton = {
            WalletButton(kind = ButtonKind.Text, onClick = actions.onAbandon) { Text(stringResource(R.string.setup_abandon_confirm)) }
        },
        dismissButton = {
            WalletButton(kind = ButtonKind.Text, onClick = actions.onKeep) { Text(stringResource(R.string.setup_abandon_keep)) }
        },
    )
}
