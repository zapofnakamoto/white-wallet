package whitewallet.feature.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.Tone
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletCheckbox
import whitewallet.core.designsystem.WalletDialog

/**
 * Deletes the wallet from this device, following the Bitcoin Design Guide (Settings > Security): an explanation
 * screen in plain language, then a second, final confirmation. The phone's lock is asked for before deleting.
 */
@Composable
fun DeleteWalletRoute(
    onBack: () -> Unit,
    onBackup: () -> Unit,
    /** Called with true if another account is now active, false if no account is left. */
    onDeleted: (otherAccountRemains: Boolean) -> Unit,
    viewModel: DeleteWalletViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val context = LocalContext.current
    val authTitle = stringResource(R.string.delete_auth_title)
    val authSubtitle = stringResource(R.string.delete_auth_subtitle)
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted(state.otherAccountRemains) }
    DeleteWalletScreen(
        state = state,
        onBack = onBack,
        onBackup = onBackup,
        onAcknowledge = viewModel::onAcknowledge,
        onContinue = viewModel::onContinue,
        onDismissConfirm = viewModel::onDismissConfirm,
        onConfirm = {
            if (activity != null && DeviceAuth.isAvailable(context)) {
                DeviceAuth.authenticate(activity, authTitle, authSubtitle, viewModel::delete, viewModel::onDismissConfirm)
            } else {
                viewModel.delete()
            }
        },
    )
}

@Composable
fun DeleteWalletScreen(
    state: DeleteWalletState,
    onBack: () -> Unit,
    onBackup: () -> Unit,
    onAcknowledge: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onDismissConfirm: () -> Unit,
    onConfirm: () -> Unit,
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.delete_title), onBack) }) { padding ->
        ScreenColumn(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.delete_what), modifier = Modifier.fillMaxWidth())
            state.host?.let { host -> Text(stringResource(R.string.delete_funds, host), modifier = Modifier.fillMaxWidth()) }
            state.profile?.let { profile ->
                Text(stringResource(R.string.delete_address, profile.address), modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.delete_balance, formatAmount(state.balanceMsat)), style = MaterialTheme.typography.titleMedium)

            if (state.atRisk) {
                WalletCard(tone = Tone.Danger, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.delete_not_backed_up), modifier = Modifier.padding(16.dp))
                }
                PrimaryButton(stringResource(R.string.delete_backup_first), onBackup)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    WalletCheckbox(checked = state.acknowledged, onCheckedChange = onAcknowledge)
                    Text(stringResource(R.string.delete_acknowledge, formatAmount(state.balanceMsat)))
                }
            } else if (state.backupVerified) {
                Text(stringResource(R.string.delete_backed_up), modifier = Modifier.fillMaxWidth())
            }
            if (state.unresolved > 0) {
                Text(pluralStringResource(R.plurals.delete_unresolved, state.unresolved, state.unresolved), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }

            WalletButton(
                kind = ButtonKind.Secondary,
                onClick = onContinue,
                enabled = state.canContinue,
                tone = Tone.Danger,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.delete_continue)) }
        }
    }
    if (state.confirming) {
        WalletDialog(
            onDismissRequest = onDismissConfirm,
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body)) },
            confirmButton = {
                WalletButton(kind = ButtonKind.Text, onClick = onConfirm, tone = Tone.Danger) {
                    Text(stringResource(R.string.delete_confirm_action))
                }
            },
            dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = onDismissConfirm) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}
