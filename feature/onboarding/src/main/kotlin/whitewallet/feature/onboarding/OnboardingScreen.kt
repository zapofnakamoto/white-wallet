package whitewallet.feature.onboarding

import android.content.ClipData
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.walletColors
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.QrCode
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletDialog
import whitewallet.core.designsystem.WalletSuggestionChip
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.screenLoaded

/**
 * A Lightning address for the active account (see [OnboardingMode]): the setup's address step, or one from Settings. [onDone] goes on, with
 * whether the wallet's phrase is written down (a restore: no backup step); [onAbandoned] once the setup was given up.
 */
@Composable
fun OnboardingRoute(
    onDone: (backedUp: Boolean) -> Unit,
    mode: OnboardingMode = OnboardingMode.Setup,
    onBack: (() -> Unit)? = null,
    /** For [OnboardingMode.Setup]: the mint of the address. */
    presetHost: String? = null,
    onAbandoned: (remains: Boolean) -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.init(mode, presetHost) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone(state.backedUp) }
    LaunchedEffect(state.abandoned) { state.abandoned?.let(onAbandoned) }
    OnboardingScreen(
        state,
        onBack,
        onUsernameChange = viewModel::onUsernameChange,
        mint = MintActions(viewModel::onMintSelected, viewModel::onCustomHostChange),
        onSuggestion = viewModel::onSuggestion,
        onStart = viewModel::start,
        onContinue = viewModel::onContinue,
        onAbandon = viewModel::abandonSetup,
        onSkipAddress = viewModel::skipAddress,
        fee = FeeActions(viewModel::acceptFee, viewModel::declineFee, viewModel::payFee),
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onBack: (() -> Unit)?,
    onUsernameChange: (String) -> Unit,
    mint: MintActions,
    onSuggestion: (String) -> Unit,
    onStart: () -> Unit,
    onContinue: () -> Unit,
    onAbandon: () -> Unit = {},
    onSkipAddress: () -> Unit = {},
    fee: FeeActions = FeeActions({}, {}, {}),
) {
    // Before the mint and the first name are known (a moment): nothing, so the form doesn't show one thing and then another.
    if (!screenLoaded(state.ready)) return
    val claimed = state.claimed
    state.feePrompt?.let { AddressFeeDialog(it, state.canSkip, fee) }
    state.held?.let { held ->
        Surface(Modifier.fillMaxSize()) {
            ScreenColumn(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(24.dp))
                HeldContent(held, state, fee.onPay, onContinue)
            }
        }
        return
    }
    var confirmAbandon by rememberSaveable { mutableStateOf(false) }
    val askAbandon = { confirmAbandon = true }.takeIf { state.inSetup && claimed == null && !state.busy }
    // Back on the setup's address step asks whether to give the setup up; the mint before it is added already.
    BackHandler(enabled = askAbandon != null) { askAbandon?.invoke() }
    if (confirmAbandon)
        AbandonSetupDialog(
            onConfirm = {
                confirmAbandon = false
                onAbandon()
            },
            onDismiss = { confirmAbandon = false },
        )
    val title =
        stringResource(
            when (state.mode) {
                OnboardingMode.Setup -> R.string.setup_address_title
                OnboardingMode.ChangeAddress -> R.string.onboarding_change_title
                OnboardingMode.AddAddress -> R.string.onboarding_add_title
                OnboardingMode.AddAddressElsewhere -> R.string.onboarding_add_elsewhere_title
            }
        )
    Scaffold(topBar = { WalletTopBar(title, (onBack ?: askAbandon).takeIf { claimed == null }) }) { padding ->
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            if (claimed != null) {
                ClaimedContent(claimed, onContinue)
                return@ScreenColumn
            }
            Text(
                when (state.mode) {
                    OnboardingMode.Setup -> stringResource(R.string.setup_address_body, state.host)
                    OnboardingMode.ChangeAddress -> stringResource(R.string.onboarding_change_body, state.host)
                    OnboardingMode.AddAddress -> stringResource(R.string.onboarding_add_body, state.host)
                    OnboardingMode.AddAddressElsewhere -> stringResource(R.string.onboarding_add_elsewhere_body)
                },
                textAlign = TextAlign.Center,
            )
            AddressForm(
                state,
                onUsernameChange,
                mint,
                onSuggestion,
                onStart,
                submitLabel = stringResource(if (state.inSetup) R.string.setup_address_submit else R.string.onboarding_claim),
            )
            SkipAddress(state, onSkipAddress)
        }
    }
}

/** What the address fee steps can ask for. */
class FeeActions(val onAccept: () -> Unit, val onDecline: () -> Unit, val onPay: () -> Unit)

@Composable
private fun AddressFeeDialog(amountMsat: Long, canSkip: Boolean, fee: FeeActions) {
    WalletDialog(
        onDismissRequest = fee.onDecline,
        title = { Text(stringResource(R.string.address_fee_title)) },
        text = { Text(stringResource(R.string.address_fee_body, formatAmount(amountMsat))) },
        confirmButton = {
            WalletButton(kind = ButtonKind.Text, onClick = fee.onAccept) { Text(stringResource(R.string.address_fee_reserve)) }
        },
        dismissButton = {
            WalletButton(kind = ButtonKind.Text, onClick = fee.onDecline) {
                Text(stringResource(if (canSkip) R.string.address_fee_skip else R.string.address_fee_back))
            }
        },
    )
}

/** The name is held: the invoice to pay from another wallet, or from the balance, and the way on without waiting. */
@Composable
private fun HeldContent(held: HeldAddress, state: OnboardingState, onPay: () -> Unit, onContinue: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val invoice = held.address.feeInvoice.orEmpty()
    Text(stringResource(R.string.address_held_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(
        held.address.address,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
    Text(stringResource(R.string.address_held_body, formatAmount(held.amountMsat)), textAlign = TextAlign.Center)
    QrCode("lightning:${invoice.uppercase()}", description = stringResource(R.string.address_held_qr))
    WalletButton(
        kind = ButtonKind.Secondary,
        onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("invoice", invoice))) } },
    ) {
        Text(stringResource(R.string.address_held_copy))
    }
    if (held.canPayFromBalance) {
        WalletButton(kind = ButtonKind.Secondary, onClick = onPay, enabled = !state.busy) {
            Text(stringResource(R.string.address_held_pay_balance))
        }
    }
    ErrorText(state)
    PrimaryButton(stringResource(R.string.address_held_later), onContinue)
}

/** What the mint dropdown can ask for. */
class MintActions(val onSelect: (MintOption) -> Unit, val onCustomHost: (String) -> Unit)

/**
 * The name field is the main control (first); the whole address it makes is spelled out below it, with whether it's free. The mint choice
 * below that is secondary.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressForm(
    state: OnboardingState,
    onUsernameChange: (String) -> Unit,
    mint: MintActions,
    onSuggestion: (String) -> Unit,
    onStart: () -> Unit,
    submitLabel: String,
) {
    WalletTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.onboarding_username)) },
        supportingText = {
            Column {
                if (state.username.isNotBlank())
                    Text(stringResource(R.string.onboarding_address_preview, "${state.username.trim()}@${state.host}"))
                AvailabilityText(state.availability)
            }
        },
        isError = state.availability is Availability.Taken || state.availability is Availability.Invalid,
        textStyle = MaterialTheme.typography.titleLarge,
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
    if (state.showMintPicker) MintPicker(options = state.mintOptions, selected = state.selectedMint, state.customHost, state.busy, mint)
    // Said before the button: what the address costs is part of choosing it.
    state.feeToShow?.let { Text(stringResource(R.string.address_fee_notice, formatAmount(it)), textAlign = TextAlign.Center) }
    ErrorText(state)
    PrimaryButton(submitLabel, onStart, enabled = state.canSubmit, busy = state.busy)
}

/** Where the brand lets users go on without a Lightning address: the way past the name, and what they can do instead. */
@Composable
private fun SkipAddress(state: OnboardingState, onSkip: () -> Unit) {
    if (!state.canSkipAddress) return
    WalletButton(kind = ButtonKind.Text, onClick = onSkip, enabled = state.canSkip) {
        Text(stringResource(R.string.onboarding_skip_address))
    }
    Text(
        stringResource(R.string.onboarding_skip_address_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ErrorText(state: OnboardingState) {
    state.error?.let { error ->
        val message =
            when (error) {
                is OnboardingError.FeeTooHigh ->
                    stringResource(R.string.onboarding_error_fee, formatAmount(error.amountMsat), formatAmount(error.maxMsat))
                is OnboardingError.FeeNotPaid -> stringResource(R.string.onboarding_error_fee_not_paid)
                is OnboardingError.MintTooOld -> stringResource(R.string.onboarding_error_mint_too_old)
                is OnboardingError.HoldExpired -> stringResource(R.string.onboarding_error_hold_expired)
                is OnboardingError.Username -> stringResource(R.string.onboarding_error_username, error.detail)
                is OnboardingError.Registration -> stringResource(R.string.onboarding_error_registration, error.detail)
                is OnboardingError.Mint -> stringResource(R.string.onboarding_error_mint, error.detail)
                is OnboardingError.MintWithoutSigningKey ->
                    stringResource(
                        if (error.canAllow) R.string.onboarding_error_mint_without_key_allow else R.string.onboarding_error_mint_without_key
                    )
                is OnboardingError.Network -> stringResource(R.string.onboarding_error_network, error.detail)
                is OnboardingError.Limit -> pluralStringResource(R.plurals.onboarding_error_limit, error.limit, error.limit)
            }
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

/** Secondary: a compact dropdown of suggested mints, the local dev mint in developer mode, and "Custom". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MintPicker(
    options: List<MintOption>,
    selected: MintOption,
    customHost: String,
    busy: Boolean,
    mint: MintActions,
) {
    var expanded by remember { mutableStateOf(false) }
    val small = MaterialTheme.typography.bodyMedium
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!busy) expanded = it }) {
        WalletTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.onboarding_mint)) },
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
                            if (option is MintOption.LocalDev) {
                                Text(
                                    stringResource(R.string.onboarding_mint_local),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        mint.onSelect(option)
                    },
                )
            }
        }
    }
    if (selected == MintOption.Custom) {
        WalletTextField(
            value = customHost,
            onValueChange = mint.onCustomHost,
            label = { Text(stringResource(R.string.onboarding_mint_address)) },
            placeholder = { Text("mint.example.com", style = small) },
            textStyle = small,
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable private fun optionLabel(option: MintOption): String = option.host ?: stringResource(R.string.onboarding_mint_custom)

@Composable
private fun AvailabilityText(availability: Availability) {
    val positive = MaterialTheme.walletColors.success
    val (text, color) =
        when (availability) {
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
    Text(
        claimed.address,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(if (claimed.confirmed) R.string.claimed_body else R.string.claimed_body_unconfirmed),
        textAlign = TextAlign.Center,
    )
    WalletButton(
        kind = ButtonKind.Secondary,
        onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", claimed.address))) }
            copied = true
        },
    ) {
        Text(stringResource(if (copied) R.string.claimed_copied else R.string.claimed_copy))
    }
    PrimaryButton(stringResource(R.string.claimed_continue), onContinue)
}

/** Giving the setup up removes the wallet from this phone; its money stays with the recovery phrase. */
@Composable
private fun AbandonSetupDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    WalletDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_abandon_title)) },
        text = { Text(stringResource(R.string.setup_abandon_body)) },
        confirmButton = {
            WalletButton(kind = ButtonKind.Text, onClick = onConfirm) { Text(stringResource(R.string.setup_abandon_confirm)) }
        },
        dismissButton = {
            WalletButton(kind = ButtonKind.Text, onClick = onDismiss) { Text(stringResource(R.string.setup_abandon_keep)) }
        },
    )
}
