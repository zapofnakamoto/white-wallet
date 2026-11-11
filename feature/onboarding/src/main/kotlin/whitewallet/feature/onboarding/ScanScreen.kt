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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletLinearProgress
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.designsystem.recentTime
import whitewallet.core.model.MintScan
import whitewallet.core.wallet.MintInfo
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
        ScreenColumn(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.scan_body), textAlign = TextAlign.Center)
            Column(Modifier.fillMaxWidth()) {
                state.mints.forEach { mint ->
                    val current = state.scanning && mint.host == state.host
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(mint.host, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (current) stringResource(R.string.scan_mint_scanning) else scanStatus(mint),
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
                val result = state.result
                result?.let { ScanOutcome(it) }
                when {
                    result == null -> PrimaryButton(stringResource(R.string.scan_action), onScan, enabled = state.mints.isNotEmpty())
                    // A scan that got through is over: leaving is the way on, scanning again the exception.
                    result.stoppedAt.isEmpty() -> {
                        PrimaryButton(stringResource(R.string.scan_done), onBack)
                        WalletButton(kind = ButtonKind.Text, onClick = onScan) { Text(stringResource(R.string.scan_again)) }
                    }
                    else -> {
                        PrimaryButton(stringResource(R.string.scan_again), onScan)
                        WalletButton(kind = ButtonKind.Text, onClick = onBack) { Text(stringResource(R.string.add_mint_later)) }
                    }
                }
            }
        }
    }
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

@Composable
private fun ScanOutcome(result: ScanResult) {
    if (result.foundMsat > 0) {
        Text(
            stringResource(R.string.add_mint_found_total, formatAmount(result.foundMsat)),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
    } else if (result.stoppedAt.isEmpty()) {
        Text(stringResource(R.string.scan_result_none), textAlign = TextAlign.Center)
    }
    if (result.stoppedAt.isNotEmpty()) {
        Text(stringResource(R.string.scan_result_stopped), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}
