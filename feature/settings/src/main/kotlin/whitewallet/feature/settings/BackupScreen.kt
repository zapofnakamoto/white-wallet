package whitewallet.feature.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SecureScreen
import whitewallet.core.designsystem.SensitiveClipboard
import whitewallet.core.designsystem.WalletTopBar

@Composable
fun BackupRoute(onDone: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val context = LocalContext.current
    val authTitle = stringResource(R.string.backup_auth_title)
    val authSubtitle = stringResource(R.string.backup_auth_subtitle)
    BackupScreen(
        state = state,
        onBack = onDone,
        onShow = {
            if (activity == null || !DeviceAuth.isAvailable(context)) {
                viewModel.onNoScreenLock()
            } else {
                DeviceAuth.authenticate(activity, authTitle, authSubtitle, viewModel::onAuthenticated, viewModel::onAuthCancelled)
            }
        },
        onContinueWithoutLock = viewModel::onAuthenticated,
        onCopy = { words -> SensitiveClipboard.copy(context, "recovery phrase", words.joinToString(" ")) },
        onWroteItDown = viewModel::onWroteItDown,
        onTap = viewModel::onTap,
        onSkipVerify = viewModel::onSkipVerify,
    )
}

@Composable
fun BackupScreen(
    state: BackupState,
    onBack: () -> Unit,
    onShow: () -> Unit,
    onContinueWithoutLock: () -> Unit,
    onCopy: (List<String>) -> Unit,
    onWroteItDown: () -> Unit,
    onTap: (Int) -> Unit,
    onSkipVerify: () -> Unit,
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.backup_title), onBack) }) { padding ->
        ScreenColumn(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            when (val step = state.step) {
                BackupStep.Intro -> Intro(onShow, onBack)
                BackupStep.NoScreenLock -> NoScreenLock(onContinueWithoutLock, onBack)
                is BackupStep.Display -> SecureScreen { Display(step.words, state.addressesToWriteDown, onCopy, onWroteItDown) }
                is BackupStep.Verify -> SecureScreen { Verify(step, state.alreadyVerified, onTap, onSkipVerify) }
                BackupStep.Success -> Success(onBack)
            }
        }
    }
}

@Composable
private fun Intro(onShow: () -> Unit, onNotNow: () -> Unit) {
    listOf(
        R.string.backup_intro_next, R.string.backup_intro_what, R.string.backup_intro_purpose,
        R.string.backup_intro_loss, R.string.backup_intro_share, R.string.backup_intro_handling,
    ).forEach { Text(stringResource(it), modifier = Modifier.fillMaxWidth()) }
    PrimaryButton(stringResource(R.string.backup_show), onShow)
    TextButton(onClick = onNotNow) { Text(stringResource(R.string.backup_not_now)) }
}

@Composable
private fun NoScreenLock(onContinue: () -> Unit, onCancel: () -> Unit) {
    Text(stringResource(R.string.backup_no_lock_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
    Text(stringResource(R.string.backup_no_lock_body), textAlign = TextAlign.Center)
    OutlinedButton(onClick = onContinue) { Text(stringResource(R.string.backup_continue_anyway)) }
    TextButton(onClick = onCancel) { Text(stringResource(R.string.backup_not_now)) }
}

@Composable
private fun Display(words: List<String>, addresses: List<String>, onCopy: (List<String>) -> Unit, onWroteItDown: () -> Unit) {
    var confirmCopy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Text(stringResource(R.string.backup_display_warning), textAlign = TextAlign.Center)
    Card(Modifier.fillMaxWidth()) {
        // Two columns, numbered top to bottom: 1-6 on the left, 7-12 on the right.
        val half = (words.size + 1) / 2
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(0 until half, half until words.size).forEach { range ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    range.forEach { i -> Text("${i + 1}. ${words[i]}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
    if (addresses.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.backup_addresses, addresses.size), style = MaterialTheme.typography.bodyMedium)
                addresses.forEach { Text(it, fontFamily = FontFamily.Monospace) }
            }
        }
    }
    PrimaryButton(stringResource(R.string.backup_wrote_it_down), onWroteItDown)
    TextButton(onClick = { confirmCopy = true }) { Text(stringResource(R.string.backup_copy)) }
    if (copied) Text(stringResource(R.string.backup_copied), style = MaterialTheme.typography.bodySmall)
    if (confirmCopy) {
        AlertDialog(
            onDismissRequest = { confirmCopy = false },
            title = { Text(stringResource(R.string.backup_copy_title)) },
            text = { Text(stringResource(R.string.backup_copy_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onCopy(words)
                    copied = true
                    confirmCopy = false
                }) { Text(stringResource(R.string.backup_copy_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmCopy = false }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Verify(step: BackupStep.Verify, alreadyVerified: Boolean, onTap: (Int) -> Unit, onSkip: () -> Unit) {
    Text(stringResource(R.string.backup_verify_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.backup_verify_body), textAlign = TextAlign.Center)
    Card(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            step.words.indices.forEach { slot ->
                val word = step.progress.getOrNull(slot)?.let { step.words[it] } ?: "____"
                Text("${slot + 1}. $word", fontFamily = FontFamily.Monospace)
            }
        }
    }
    step.wrongNumber?.let { Text(stringResource(R.string.backup_verify_wrong, it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        step.choices.forEach { position ->
            SuggestionChip(
                onClick = { onTap(position) },
                label = { Text(step.words[position]) },
                enabled = position !in step.progress,
            )
        }
    }
    if (alreadyVerified) TextButton(onClick = onSkip) { Text(stringResource(R.string.backup_verify_skip)) }
}

@Composable
private fun Success(onDone: () -> Unit) {
    Text(stringResource(R.string.backup_success_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.backup_success_body), textAlign = TextAlign.Center)
    PrimaryButton(stringResource(R.string.backup_done), onDone)
}
