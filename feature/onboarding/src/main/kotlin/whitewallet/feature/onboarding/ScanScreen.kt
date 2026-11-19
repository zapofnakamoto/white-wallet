package whitewallet.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SuccessAction
import whitewallet.core.designsystem.SuccessScreen
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.designsystem.recentTime
import whitewallet.core.model.MintScan
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.ScanProgress
import whitewallet.core.wallet.ScanResult

/**
 * Looking through the active wallet's keys at each of its mints, as a restore does: from the wallet's page at any time, or from the main
 * screen's notice while a mint wasn't looked through ([start]: it begins at once).
 */
@Composable
fun ScanRoute(start: Boolean, onBack: () -> Unit, viewModel: ScanViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.init(start) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScanScreen(state, onBack, viewModel::scan)
}

@Composable
fun ScanScreen(state: ScanState, onBack: () -> Unit, onScan: () -> Unit) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.scan_notes_title), onBack) }) { padding ->
        if (!pageLoaded(state.loaded, Modifier.padding(padding))) return@Scaffold
        val result = state.result
        // A scan that got through is over: leaving is the way on (again would find the same).
        if (!state.scanning && result != null && result.stoppedAt.isEmpty()) {
            return@Scaffold SuccessScreen(
                anchor = Anchor.ScanDone,
                status = WalletStatus.Done(result.foundMsat),
                title = stringResource(R.string.scan_finished_title),
                done = SuccessAction(stringResource(R.string.success_done), onBack),
                modifier = Modifier.padding(padding),
                lines =
                    listOf(
                        if (result.foundMsat > 0) stringResource(R.string.add_mint_found_total, formatAmount(result.foundMsat))
                        else stringResource(R.string.scan_result_none)
                    ),
            )
        }
        ScreenColumn(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.scan_body), textAlign = TextAlign.Center)
            Column(Modifier.fillMaxWidth()) {
                state.mints.forEach { mint ->
                    val current = state.scanning && mint.host == state.host
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(mint.host, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (current) scanningText(state.progress) else scanStatus(mint),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (current || mint.scan == MintScan.Finished) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (state.scanning) {
                WalletLinearProgress(Modifier.fillMaxWidth())
                ScanAccounts(state.progress, empty = R.string.scan_account_nothing_new)
            } else {
                result?.let { StoppedOutcome(it) }
                when {
                    result == null -> PrimaryButton(stringResource(R.string.scan_action), onScan, enabled = state.mints.isNotEmpty())
                    else -> {
                        PrimaryButton(stringResource(R.string.scan_again), onScan)
                        WalletButton(kind = ButtonKind.Text, onClick = onBack) { Text(stringResource(R.string.add_mint_later)) }
                    }
                }
            }
        }
    }
}

/** What the scan does on the mint now: looking for new notes, then looking up the ones the wallet holds there. */
@Composable
private fun scanningText(progress: ScanProgress?): String {
    if (progress == null || progress.notesToCheck == 0) return stringResource(R.string.scan_mint_scanning)
    val total = progress.notesToCheck
    return pluralStringResource(R.plurals.scan_mint_checking_notes, total, minOf(progress.notesChecked + 1, total), total)
}

/** Whether, and when, the mint's keys were looked through; a scan from before this was recorded has no time. */
@Composable
private fun scanStatus(mint: MintInfo): String {
    val finished = mint.scannedAtMillis ?: 0
    return when (mint.scan) {
        MintScan.Never,
        null -> stringResource(R.string.scan_mint_never)
        MintScan.Unfinished -> stringResource(R.string.scan_mint_unfinished)
        MintScan.Finished ->
            if (finished > 0) stringResource(R.string.scan_mint_finished_at, recentTime(finished))
            else stringResource(R.string.scan_mint_finished)
    }
}

/** A scan that stopped at a mint: what it found until then, and that it stopped. */
@Composable
private fun StoppedOutcome(result: ScanResult) {
    if (result.foundMsat > 0) {
        Text(
            stringResource(R.string.add_mint_found_total, formatAmount(result.foundMsat)),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
    }
    Text(stringResource(R.string.scan_result_stopped), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
}
