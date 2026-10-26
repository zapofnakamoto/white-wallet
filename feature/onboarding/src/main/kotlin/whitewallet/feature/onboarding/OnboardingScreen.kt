package whitewallet.feature.onboarding

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import whitewallet.brand.ui.Anchor
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.BrandTitle
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.R as DesignR
import whitewallet.brand.ui.ButtonKind
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletSuggestionChip
import whitewallet.core.designsystem.WalletTextField

/**
 * First launch, adding an account, or choosing a new address for the active one (see [OnboardingMode]).
 * [logo] and [appName] come from the app module so the brand stays in brand/; a brand design may replace both (the
 * [Anchor.OnboardingHeader] and [Anchor.OnboardingTitle] decorations).
 */
@Composable
fun OnboardingRoute(
    appName: String,
    logo: @Composable () -> Unit,
    onDone: () -> Unit,
    mode: OnboardingMode = OnboardingMode.FirstRun,
    onBack: (() -> Unit)? = null,
    /** Offers restoring from a recovery phrase instead (first run and new account). */
    onRestore: (() -> Unit)? = null,
    /** Preselects the service for a new account (e.g. the local regtest mint). */
    presetHost: String? = null,
    /** For [OnboardingMode.RestoreAddress]: the restore was cancelled; whether another account remains. */
    onRestoreCancelled: (remains: Boolean) -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val defaultName = stringResource(R.string.default_account_name)
    val newName = stringResource(R.string.new_account_default_name)
    LaunchedEffect(Unit) { viewModel.init(mode, defaultName, newName, presetHost) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    LaunchedEffect(state.restoreCancelled) { state.restoreCancelled?.let(onRestoreCancelled) }
    OnboardingScreen(
        appName, logo, state, onBack, viewModel::onUsernameChange, ServiceActions(viewModel::onServiceSelected, viewModel::onCustomHostChange), viewModel::onSuggestion,
        viewModel::start, viewModel::onContinue, viewModel::onAccountNameChange, onRestore, viewModel::cancelRestore,
    )
}

@Composable
fun OnboardingScreen(
    appName: String,
    logo: @Composable () -> Unit,
    state: OnboardingState,
    onBack: (() -> Unit)?,
    onUsernameChange: (String) -> Unit,
    service: ServiceActions,
    onSuggestion: (String) -> Unit,
    onStart: () -> Unit,
    onContinue: () -> Unit,
    onAccountNameChange: (String) -> Unit,
    onRestore: (() -> Unit)? = null,
    onCancelRestore: () -> Unit = {},
) {
    val claimed = state.claimed
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val askCancel = { confirmCancel = true }.takeIf { state.restoringAddress && claimed == null && !state.busy }
    // Back on the address step of a restore asks to cancel it; there is nothing else to go back to.
    BackHandler(enabled = askCancel != null) { askCancel?.invoke() }
    if (confirmCancel) CancelRestoreDialog(onConfirm = { confirmCancel = false; onCancelRestore() }, onDismiss = { confirmCancel = false })
    if (state.mode != OnboardingMode.FirstRun) {
        val title = stringResource(
            when {
                state.changingAddress -> R.string.onboarding_change_title
                state.addingAddress -> R.string.onboarding_add_title
                state.addingElsewhere -> R.string.onboarding_add_elsewhere_title
                state.restoringAddress -> R.string.restore_address_title
                else -> R.string.onboarding_new_account_title
            },
        )
        Scaffold(topBar = { WalletTopBar(title, (onBack ?: askCancel).takeIf { claimed == null }) }) { padding ->
            ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
                if (claimed != null) {
                    ClaimedContent(claimed, onContinue)
                } else if (state.changingAddress) {
                    Text(stringResource(R.string.onboarding_change_body, state.host), textAlign = TextAlign.Center)
                    AddressForm(state, onUsernameChange, service, onSuggestion, onStart, stringResource(R.string.onboarding_claim))
                } else if (state.restoringAddress) {
                    Text(
                        if (state.restoredMsat > 0) stringResource(R.string.restore_address_found, formatAmount(state.restoredMsat))
                        else stringResource(R.string.restore_address_found_none),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(stringResource(R.string.restore_address_body, state.host), textAlign = TextAlign.Center)
                    AddressForm(state, onUsernameChange, service, onSuggestion, onStart, stringResource(R.string.restore_address_submit))
                    WalletButton(kind = ButtonKind.Text, onClick = { confirmCancel = true }, enabled = !state.busy) { Text(stringResource(R.string.restore_cancel)) }
                } else if (state.addingAddress) {
                    Text(stringResource(R.string.onboarding_add_body, state.host), textAlign = TextAlign.Center)
                    AddressForm(state, onUsernameChange, service, onSuggestion, onStart, stringResource(R.string.onboarding_claim))
                } else if (state.addingElsewhere) {
                    Text(stringResource(R.string.onboarding_add_elsewhere_body), textAlign = TextAlign.Center)
                    AddressForm(state, onUsernameChange, service, onSuggestion, onStart, stringResource(R.string.onboarding_claim))
                } else {
                    Text(
                        stringResource(if (state.withAddress) R.string.onboarding_new_account_body else R.string.onboarding_new_account_body_no_address),
                        textAlign = TextAlign.Center,
                    )
                    WalletTextField(
                        value = state.accountName,
                        onValueChange = onAccountNameChange,
                        label = { Text(stringResource(R.string.onboarding_account_name)) },
                        singleLine = true,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.withAddress) {
                        AddressForm(state, onUsernameChange, service, onSuggestion, onStart, stringResource(R.string.onboarding_create_account))
                    } else {
                        MintForm(state, service, onStart, stringResource(R.string.onboarding_create_account))
                    }
                    onRestore?.let { WalletButton(kind = ButtonKind.Text, onClick = it, enabled = !state.busy) { Text(stringResource(R.string.onboarding_restore_instead)) } }
                }
            }
        }
        return
    }
    Surface(Modifier.fillMaxSize()) {
        ScreenColumn(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            if (claimed != null) {
                ClaimedContent(claimed, onContinue)
                return@ScreenColumn
            }
            BrandDecoration(Anchor.OnboardingHeader) { logo() }
            BrandTitle(Anchor.OnboardingTitle, appName) { Text(appName, style = MaterialTheme.typography.headlineMedium) }
            Text(
                stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (state.withAddress) {
                AddressForm(state, onUsernameChange, service, onSuggestion, onStart, stringResource(R.string.onboarding_start))
            } else {
                // Without an address and with a single mint, this is just the button.
                MintForm(state, service, onStart, stringResource(R.string.onboarding_start))
            }
            onRestore?.let { WalletButton(kind = ButtonKind.Text, onClick = it, enabled = !state.busy) { Text(stringResource(R.string.onboarding_restore)) } }
            Text(
                stringResource(R.string.onboarding_custody),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** What the service dropdown can ask for. */
class ServiceActions(val onSelect: (ServiceOption) -> Unit, val onCustomHost: (String) -> Unit)

/**
 * The address field is the main control (large text, first); the service choice below it is secondary.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressForm(
    state: OnboardingState,
    onUsernameChange: (String) -> Unit,
    service: ServiceActions,
    onSuggestion: (String) -> Unit,
    onStart: () -> Unit,
    submitLabel: String,
) {
    WalletTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.onboarding_username)) },
        suffix = { Text("@${state.host}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingText = { AvailabilityText(state.availability) },
        isError = state.availability is Availability.Taken || state.availability is Availability.Invalid,
        textStyle = MaterialTheme.typography.headlineSmall,
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onStart() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.suggestions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            state.suggestions.forEach { name -> WalletSuggestionChip(onClick = { onSuggestion(name) }, label = { Text(name) }) }
        }
    }
    if (state.canChangeHost) ServicePicker(state.serviceOptions, state.selectedService, state.customHost, state.busy, service)
    ErrorText(state)
    PrimaryButton(submitLabel, onStart, enabled = state.canSubmit, busy = state.busy)
}

/** A new account without a Lightning Address: the mint (when there is a choice) and the button. */
@Composable
private fun MintForm(state: OnboardingState, service: ServiceActions, onStart: () -> Unit, submitLabel: String) {
    if (state.canChangeHost) ServicePicker(state.serviceOptions, state.selectedService, state.customHost, state.busy, service)
    ErrorText(state)
    PrimaryButton(submitLabel, onStart, enabled = state.canSubmit, busy = state.busy)
}

@Composable
private fun ErrorText(state: OnboardingState) {
    state.error?.let { error ->
        val message = when (error) {
            is OnboardingError.Username -> stringResource(R.string.onboarding_error_username, error.detail)
            is OnboardingError.Registration -> stringResource(R.string.onboarding_error_registration, error.detail)
            is OnboardingError.Service -> stringResource(R.string.onboarding_error_service, error.detail)
            is OnboardingError.Network -> stringResource(R.string.onboarding_error_network, error.detail)
            is OnboardingError.Limit -> pluralStringResource(R.plurals.onboarding_error_limit, error.limit, error.limit)
            is OnboardingError.AccountLimit -> pluralStringResource(R.plurals.onboarding_error_account_limit, error.limit, error.limit)
        }
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

/** Secondary: a compact dropdown of suggested services, the local dev mint in developer mode, and "Custom". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServicePicker(
    options: List<ServiceOption>,
    selected: ServiceOption,
    customHost: String,
    busy: Boolean,
    service: ServiceActions,
) {
    var expanded by remember { mutableStateOf(false) }
    val small = MaterialTheme.typography.bodyMedium
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!busy) expanded = it }) {
        WalletTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.onboarding_service)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            textStyle = small,
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(optionLabel(option), style = small)
                            if (option is ServiceOption.LocalDev) {
                                Text(stringResource(R.string.onboarding_service_local), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        service.onSelect(option)
                    },
                )
            }
        }
    }
    if (selected == ServiceOption.Custom) {
        WalletTextField(
            value = customHost,
            onValueChange = service.onCustomHost,
            label = { Text(stringResource(R.string.onboarding_service_custom_host)) },
            placeholder = { Text("mint.example.com", style = small) },
            textStyle = small,
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun optionLabel(option: ServiceOption): String = option.host ?: stringResource(R.string.onboarding_service_custom)

@Composable
private fun AvailabilityText(availability: Availability) {
    val positive = MaterialTheme.walletColors.success
    val (text, color) = when (availability) {
        Availability.Idle -> return
        Availability.Checking -> stringResource(R.string.availability_checking) to Color.Unspecified
        Availability.Available -> stringResource(R.string.availability_available) to positive
        Availability.Yours -> stringResource(R.string.availability_yours) to positive
        Availability.Taken -> stringResource(R.string.availability_taken) to MaterialTheme.colorScheme.error
        is Availability.Invalid -> availability.reason to MaterialTheme.colorScheme.error
        Availability.Unknown -> stringResource(R.string.availability_unknown) to Color.Unspecified
    }
    Text(text, color = color)
}

/** Success: the address is claimed (and, when [Claimed.confirmed], verified to point at this wallet). */
@Composable
private fun ClaimedContent(claimed: Claimed, onContinue: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Icon(
        painterResource(DesignR.drawable.ic_check_circle),
        contentDescription = null,
        tint = if (claimed.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.size(72.dp),
    )
    Text(
        stringResource(if (claimed.confirmed) R.string.claimed_title else R.string.claimed_title_unconfirmed),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Text(claimed.address, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    Text(
        stringResource(if (claimed.confirmed) R.string.claimed_body else R.string.claimed_body_unconfirmed),
        textAlign = TextAlign.Center,
    )
    WalletButton(kind = ButtonKind.Secondary, onClick = {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", claimed.address))) }
        copied = true
    }) { Text(stringResource(if (copied) R.string.claimed_copied else R.string.claimed_copy)) }
    PrimaryButton(stringResource(R.string.claimed_continue), onContinue)
}

/** Cancelling only removes the account from this device; the money stays with the recovery phrase. */
@Composable
private fun CancelRestoreDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    WalletDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_cancel_title)) },
        text = { Text(stringResource(R.string.restore_cancel_body)) },
        confirmButton = { WalletButton(kind = ButtonKind.Text, onClick = onConfirm) { Text(stringResource(R.string.restore_cancel_confirm)) } },
        dismissButton = { WalletButton(kind = ButtonKind.Text, onClick = onDismiss) { Text(stringResource(R.string.restore_cancel_keep)) } },
    )
}
