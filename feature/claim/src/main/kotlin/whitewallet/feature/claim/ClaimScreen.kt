package whitewallet.feature.claim

import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.brand.ui.Anchor
import whitewallet.brand.WalletStatus
import whitewallet.brand.ProblemKind
import whitewallet.core.scanner.QrScanner
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.WalletNoteFace
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.NoteStatus
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.ClaimPreview
import whitewallet.core.wallet.ClaimResult
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletTextField

@Composable
fun ClaimRoute(onBack: () -> Unit, viewModel: ClaimViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.scanning) { viewModel.stopScan() }
    ClaimScreen(state, onBack, viewModel::onInputChange, viewModel::check, viewModel::claim, viewModel::startScan, viewModel::onScanned, viewModel::stopScan)
}

@Composable
fun ClaimScreen(
    state: ClaimState,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onCheck: () -> Unit,
    onClaim: () -> Unit,
    onStartScan: () -> Unit = {},
    onScanned: (String) -> Unit = {},
    onStopScan: () -> Unit = {},
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.claim_title), onBack) }) { padding ->
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            when (val result = state.result) {
                null -> Unit
                else -> {
                    val status = when (result) {
                        is ClaimResult.Claimed -> WalletStatus.PaymentReceived(result.amountMsat)
                        is ClaimResult.Failed -> WalletStatus.Problem(ProblemKind.Other)
                        ClaimResult.Pending -> WalletStatus.None
                    }
                    WalletStatusScope(status) { BrandDecoration(Anchor.ClaimResult) }
                    ResultMessage(result)
                    PrimaryButton(stringResource(R.string.claim_finish), onBack)
                    return@ScreenColumn
                }
            }
            if (state.scanning) {
                Text(stringResource(R.string.claim_scan_hint), textAlign = TextAlign.Center)
                QrScanner(onScanned = onScanned, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large))
                WalletButton(kind = ButtonKind.Text, onClick = onStopScan) { Text(stringResource(R.string.claim_scan_cancel)) }
                return@ScreenColumn
            }
            WalletTextField(
                value = state.input,
                onValueChange = onInputChange,
                label = { Text(stringResource(R.string.claim_input)) },
                minLines = 3,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                WalletButton(kind = ButtonKind.Secondary, onClick = onStartScan, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.claim_scan)) }
                WalletButton(
                    kind = ButtonKind.Secondary,
                    onClick = {
                        scope.launch {
                            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                            if (!text.isNullOrBlank()) onScanned(text)
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.claim_paste)) }
            }

            when (val preview = state.preview) {
                is ClaimPreview.Ready -> {
                    ReadyCard(preview, state.showMints)
                    PrimaryButton(stringResource(R.string.claim_action, formatAmount(preview.amountMsat)), onClaim, busy = state.busy)
                }
                else -> {
                    preview?.let { Text(previewMessage(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                    PrimaryButton(stringResource(R.string.claim_check), onCheck, enabled = state.input.isNotBlank(), busy = state.busy)
                }
            }
        }
    }
}

@Composable
private fun ReadyCard(preview: ClaimPreview.Ready, showMints: Boolean) {
    WalletCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            WalletNoteFace(
                NoteFaceSize.Large,
                preview.amountMsat,
                Modifier.fillMaxWidth(),
                status = if (preview.certified) NoteStatus.Ready else NoteStatus.Uncertified,
                mint = preview.link.host.takeIf { showMints },
                trusted = preview.trusted,
            )
            // Without showMints, only what needs the user: an amount that can't be verified, and the bearer warning.
            if (showMints) {
                Text(stringResource(if (preview.isOwnMint) R.string.claim_home_mint else R.string.claim_mint, preview.link.host), style = MaterialTheme.typography.bodyMedium)
                // Trust is the user's own list (Settings → Mints), by signing key; nothing is blocked either way.
                Text(
                    stringResource(if (preview.trusted) R.string.claim_trusted else R.string.claim_not_trusted),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (preview.trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showMints || !preview.certified) {
                Text(
                    stringResource(if (preview.certified) R.string.claim_certified else R.string.claim_not_certified),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (preview.certified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            if (showMints && !preview.isOwnMint) Text(stringResource(R.string.claim_other_mint, preview.link.host), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.claim_bearer_warning), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun previewMessage(preview: ClaimPreview): String = when (preview) {
    is ClaimPreview.Invalid -> stringResource(R.string.claim_invalid, preview.reason)
    ClaimPreview.AlreadySpent -> stringResource(R.string.claim_spent)
    ClaimPreview.AlreadyInWallet -> stringResource(R.string.claim_in_wallet)
    is ClaimPreview.NetworkError -> stringResource(R.string.claim_network, preview.reason)
    is ClaimPreview.Ready -> ""
}

@Composable
private fun ResultMessage(result: ClaimResult) {
    val (text, isError) = when (result) {
        is ClaimResult.Claimed -> stringResource(R.string.claim_done, formatAmount(result.amountMsat)) to false
        is ClaimResult.Failed -> stringResource(R.string.claim_failed, result.reason ?: result.kind.name) to true
        ClaimResult.Pending -> stringResource(R.string.claim_pending) to false
    }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}
