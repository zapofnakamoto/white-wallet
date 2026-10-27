package whitewallet.feature.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SecureScreen
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.wallet.PhraseCheck
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletTextField

/**
 * Restores an account from its recovery phrase on a chosen mint. [onRestored] gets the host, where the account's
 * address is picked next (addresses aren't part of the phrase).
 */
@Composable
fun RestoreRoute(
    asNewAccount: Boolean,
    onBack: () -> Unit,
    onRestored: (host: String) -> Unit,
    viewModel: RestoreViewModel = hiltViewModel(),
) {
    val defaultName = stringResource(R.string.default_account_name)
    val newName = stringResource(R.string.restore_default_account_name)
    LaunchedEffect(Unit) { viewModel.init(asNewAccount, defaultName, newName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.restoredOn) { state.restoredOn?.let(onRestored) }
    // The phrase is on screen: keep it out of screenshots and the recent-apps preview.
    SecureScreen {
        RestoreScreen(
            state, onBack, viewModel::onPhraseChange, viewModel::onAccountNameChange,
            MintActions(viewModel::onMintSelected, viewModel::onCustomHostChange), viewModel::restore, viewModel::useTestPhrase,
        )
    }
}

@Composable
fun RestoreScreen(
    state: RestoreState,
    onBack: () -> Unit,
    onPhraseChange: (String) -> Unit,
    onAccountNameChange: (String) -> Unit,
    mint: MintActions,
    onRestore: () -> Unit,
    onUseTestPhrase: () -> Unit = {},
) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.restore_title), onBack.takeIf { !state.busy }) }) { padding ->
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.restore_body), textAlign = TextAlign.Center)
            WalletTextField(
                value = state.phrase,
                onValueChange = onPhraseChange,
                label = { Text(stringResource(R.string.restore_phrase)) },
                supportingText = { PhraseHint(state) },
                isError = state.hint.let { it is PhraseCheck.UnknownWords || it is PhraseCheck.BadChecksum },
                minLines = 3,
                enabled = !state.busy,
                // A password field: keyboards don't learn or suggest what is typed here.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.developerMode) {
                WalletButton(kind = ButtonKind.Secondary, onClick = onUseTestPhrase, enabled = !state.busy) { Text(stringResource(R.string.restore_test_phrase)) }
            }
            if (state.askAccountName) {
                WalletTextField(
                    value = state.accountName,
                    onValueChange = onAccountNameChange,
                    label = { Text(stringResource(R.string.onboarding_account_name)) },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.canChangeHost) MintPicker(state.mintOptions, state.selectedMint, state.customHost, state.busy, mint)
            state.error?.let { error ->
                val message = when (error) {
                    is RestoreError.AlreadyInWallet -> stringResource(R.string.restore_error_already, error.accountName)
                    is RestoreError.AccountLimit -> pluralStringResource(R.plurals.restore_error_account_limit, error.limit, error.limit)
                    RestoreError.Mint -> stringResource(R.string.restore_error_mint)
                    RestoreError.Network -> stringResource(R.string.restore_error_network)
                }
                Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            PrimaryButton(stringResource(R.string.restore_start), onRestore, enabled = state.canSubmit, busy = state.busy)
            if (state.busy) {
                Text(
                    stringResource(R.string.restore_searching),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                stringResource(R.string.restore_what),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Says what is wrong with the phrase only once it could be complete, so typing isn't met with errors. */
@Composable
private fun PhraseHint(state: RestoreState) {
    val hint = state.hint
    val text = when (val check = hint) {
        is PhraseCheck.Valid -> stringResource(R.string.restore_phrase_valid)
        is PhraseCheck.UnknownWords -> stringResource(R.string.restore_phrase_unknown, check.words.first())
        PhraseCheck.BadChecksum -> stringResource(R.string.restore_phrase_checksum)
        is PhraseCheck.WrongCount -> if (check.count == 0) return else pluralStringResource(R.plurals.restore_phrase_count, check.count, check.count)
    }
    val color = when (hint) {
        is PhraseCheck.Valid -> MaterialTheme.colorScheme.primary
        is PhraseCheck.WrongCount -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Text(text, color = color)
}

