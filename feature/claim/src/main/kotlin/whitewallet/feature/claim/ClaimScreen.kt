package whitewallet.feature.claim

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import whitewallet.brand.ProblemKind
import whitewallet.brand.WalletStatus
import whitewallet.brand.ui.Anchor
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.NoteFacePlace
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.NoteStatus
import whitewallet.core.designsystem.BrandDecoration
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.ScreenColumn
import whitewallet.core.designsystem.SecureScreen
import whitewallet.core.designsystem.WalletButton
import whitewallet.core.designsystem.WalletCard
import whitewallet.core.designsystem.WalletNoteFace
import whitewallet.core.designsystem.WalletStatusScope
import whitewallet.core.designsystem.WalletTextField
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.designsystem.recentTime
import whitewallet.core.model.ArchiveReason
import whitewallet.core.model.ReceivedState
import whitewallet.core.scanner.QrScanner
import whitewallet.core.wallet.ClaimPreview
import whitewallet.core.wallet.ClaimResult
import whitewallet.core.wallet.MeltProblem
import whitewallet.core.wallet.MeltReason
import whitewallet.core.wallet.ReclaimResult

@Composable
fun ClaimRoute(onBack: () -> Unit, onPay: (String) -> Unit = {}, viewModel: ClaimViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Something to pay rather than a note: Send takes it.
    LaunchedEffect(state.pay) {
        state.pay?.let {
            onPay(it)
            viewModel.payOpened()
        }
    }
    BackHandler(enabled = state.scanning) { viewModel.stopScan() }
    // The field holds a bearer link: no screenshots, no Recents thumbnail.
    SecureScreen {
        ClaimScreen(
            state,
            onBack,
            onInputChange = viewModel::onInputChange,
            onCheck = viewModel::check,
            onClaim = viewModel::claim,
            onStartScan = viewModel::startScan,
            onScanned = viewModel::onScanned,
            onStopScan = viewModel::stopScan,
            onKeep = viewModel::keep,
            onTakeBack = viewModel::takeBack,
            onFaucet = viewModel::noteFromFaucet,
            melt = MeltActions(viewModel::melt, viewModel::askLink, viewModel::meltInto, viewModel::keepForLightning),
        )
    }
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
    onKeep: () -> Unit = {},
    onTakeBack: () -> Unit = {},
    /** Developer: have the regtest faucet make a note on this local mint. */
    onFaucet: (String) -> Unit = {},
    melt: MeltActions = MeltActions(),
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.claim_title), onBack) }) { padding ->
        if (!pageLoaded(state.loaded, Modifier.padding(padding))) return@Scaffold
        // A link from outside being asked what it is: nothing else until it is known, here or in Send.
        if (state.opening) {
            ScreenColumn(Modifier.padding(padding)) { Text(stringResource(R.string.claim_opening), textAlign = TextAlign.Center) }
            return@Scaffold
        }
        ScreenColumn(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            when (val result = state.result) {
                null -> Unit
                else -> {
                    val status =
                        when (result) {
                            is ClaimResult.Claimed -> WalletStatus.PaymentReceived(result.amountMsat)
                            is ClaimResult.Failed -> WalletStatus.Problem(ProblemKind.Other)
                            ClaimResult.Pending,
                            is ClaimResult.Kept -> WalletStatus.None
                        }
                    WalletStatusScope(status) { BrandDecoration(Anchor.ClaimResult) }
                    ResultMessage(result)
                    if (result is ClaimResult.Kept)
                        Text(
                            stringResource(R.string.claim_kept_hint),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    // Its mint won't keep it here: received over Lightning, if the user agrees to the fee.
                    if (
                        result is ClaimResult.Kept &&
                            result.note.state == ReceivedState.LightningOnly &&
                            state.preview !is ClaimPreview.Melt
                    )
                        WalletButton(kind = ButtonKind.Secondary, onClick = onCheck) { Text(stringResource(R.string.claim_melt_receive)) }
                    PrimaryButton(stringResource(R.string.claim_finish), onBack)
                    return@ScreenColumn
                }
            }
            state.takenBack?.let { result ->
                val status =
                    when (result) {
                        is ReclaimResult.Failed -> WalletStatus.Problem(ProblemKind.Other)
                        else -> WalletStatus.None
                    }
                WalletStatusScope(status) { BrandDecoration(Anchor.ClaimResult) }
                TakeBackMessage(result)
                PrimaryButton(stringResource(R.string.claim_finish), onBack)
                return@ScreenColumn
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
                WalletButton(kind = ButtonKind.Secondary, onClick = onStartScan, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.claim_scan))
                }
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
                ) {
                    Text(stringResource(R.string.claim_paste))
                }
            }

            when (val preview = state.preview) {
                is ClaimPreview.Ready -> {
                    ReadyCard(preview, state.showMints)
                    PrimaryButton(stringResource(R.string.claim_action, formatAmount(preview.amountMsat)), onClaim, busy = state.busy)
                }
                is ClaimPreview.Keepable -> {
                    KeepCard(preview)
                    PrimaryButton(stringResource(R.string.claim_keep), onKeep, busy = state.busy)
                }
                is ClaimPreview.Melt -> MeltSection(preview, state, melt, onCheck)
                // The user's own note: taken back, as on its note screen, never claimed.
                is ClaimPreview.OwnShared ->
                    if (preview.reachable) {
                        OwnCard(preview)
                        PrimaryButton(
                            stringResource(R.string.claim_take_back, formatAmount(preview.amountMsat)),
                            onTakeBack,
                            busy = state.busy,
                        )
                    } else {
                        Text(stringResource(R.string.claim_own_offline), textAlign = TextAlign.Center)
                        PrimaryButton(stringResource(R.string.claim_check), onCheck, enabled = state.input.isNotBlank(), busy = state.busy)
                    }
                else -> {
                    preview?.let { Text(previewMessage(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                    PrimaryButton(stringResource(R.string.claim_check), onCheck, enabled = state.input.isNotBlank(), busy = state.busy)
                    FaucetButtons(state, onFaucet)
                }
            }
        }
    }
}

/** Developer mode in a debug build: the regtest faucet makes a note on a local mint, as `regtest.sh note` would, and it is checked here. */
@Composable
private fun FaucetButtons(state: ClaimState, onFaucet: (String) -> Unit) {
    state.faucetMints.forEach { mint ->
        WalletButton(kind = ButtonKind.Secondary, onClick = { onFaucet(mint) }, enabled = !state.busy && !state.faucet.busy) {
            Text(stringResource(R.string.regtest_note, formatAmount(FAUCET_SATS * 1_000), mint))
        }
    }
    state.faucet.error?.let {
        Text(
            stringResource(R.string.regtest_note_failed, it),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReadyCard(preview: ClaimPreview.Ready, showMints: Boolean) {
    WalletCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            WalletNoteFace(
                size = NoteFaceSize.Large,
                preview.amountMsat,
                modifier = Modifier.fillMaxWidth(),
                status = if (preview.certified) NoteStatus.Ready else NoteStatus.Uncertified,
                mint = preview.link.host.takeIf { showMints },
                trusted = preview.trusted,
                place = NoteFacePlace.Claim,
            )
            // Without showMints, only what needs the user: an amount that can't be verified, other spend paths, and the bearer warning.
            if (showMints) {
                Text(
                    stringResource(if (preview.isOwnMint) R.string.claim_home_mint else R.string.claim_mint, preview.link.host),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Trust is the user's own list (Settings → Mints), by signing key; nothing is blocked either way.
                Text(
                    stringResource(if (preview.trusted) R.string.claim_trusted else R.string.claim_not_trusted),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (preview.trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // A certified amount is the normal case and says nothing; only one that can't be verified needs the user.
            if (!preview.certified) {
                Text(
                    stringResource(R.string.claim_not_certified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (showMints && !preview.isOwnMint)
                Text(stringResource(R.string.claim_other_mint, preview.link.host), style = MaterialTheme.typography.bodySmall)
            if (preview.hasOtherPaths)
                Text(
                    stringResource(R.string.claim_other_paths),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            Text(stringResource(R.string.claim_bearer_warning), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** What a link received over Lightning can ask for (see [ClaimPreview.Melt]). */
class MeltActions(
    val melt: () -> Unit = {},
    val ask: () -> Unit = {},
    val into: (String) -> Unit = {},
    val keep: () -> Unit = {},
)

/**
 * A link received over Lightning: why it isn't kept at its own mint, what it pays and about what arrives, into which mint; or what's
 * missing for that: a mint to receive into, what the link pays (asked of its service with the link, once the user agrees), or an amount the
 * mint takes.
 */
@Composable
private fun MeltSection(preview: ClaimPreview.Melt, state: ClaimState, actions: MeltActions, onCheck: () -> Unit) {
    val host = preview.link.host
    WalletCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview.problem == null) preview.expectedMsat?.let { Text(formatAmount(it), style = MaterialTheme.typography.titleLarge) }
            Text(
                when (preview.reason) {
                    MeltReason.Plain -> stringResource(R.string.claim_melt_plain, host)
                    MeltReason.NoSigningKey ->
                        stringResource(
                            if (state.canAllowWithoutSigningKey) R.string.claim_melt_no_signing_key_allow
                            else R.string.claim_melt_no_signing_key,
                            host,
                        )
                    MeltReason.NotANote -> stringResource(R.string.claim_melt_not_a_note, host)
                    MeltReason.Refused -> stringResource(R.string.claim_melt_refused, host)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val target = preview.target
            val amount = preview.amountMsat
            when {
                preview.problem == MeltProblem.NoMint -> Text(stringResource(R.string.claim_melt_no_mint))
                target == null -> Text(stringResource(R.string.claim_melt_pick))
                amount == null -> Text(stringResource(R.string.claim_melt_ask_hint, host), style = MaterialTheme.typography.bodySmall)
                preview.problem == MeltProblem.OutOfRange ->
                    Text(
                        stringResource(
                            R.string.claim_melt_out_of_range,
                            formatAmount(amount),
                            target,
                            formatAmount(preview.acceptMinMsat),
                            formatAmount(preview.acceptMaxMsat),
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                preview.problem == MeltProblem.MintUnreachable ->
                    Text(stringResource(R.string.claim_melt_unreachable, target), color = MaterialTheme.colorScheme.error)
                else -> {
                    Text(stringResource(R.string.claim_melt_pays, host, formatAmount(amount), formatAmount(preview.expectedMsat ?: amount)))
                    Text(
                        stringResource(if (target == state.home) R.string.claim_melt_into_home else R.string.claim_melt_into, target),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    val target = preview.target
    when {
        preview.problem == MeltProblem.NoMint ->
            if (preview.link.note != null) PrimaryButton(stringResource(R.string.claim_keep_for_later), actions.keep, busy = state.busy)
        target == null ->
            preview.mints.forEach { mint ->
                WalletButton(kind = ButtonKind.Secondary, onClick = { actions.into(mint) }, enabled = !state.busy) { Text(mint) }
            }
        preview.amountMsat == null ->
            PrimaryButton(stringResource(R.string.claim_melt_ask, preview.link.host), actions.ask, busy = state.busy)
        preview.problem != null -> PrimaryButton(stringResource(R.string.claim_check), onCheck, busy = state.busy)
        else ->
            PrimaryButton(
                stringResource(R.string.claim_melt_action, formatAmount(preview.expectedMsat ?: preview.amountMsat ?: 0)),
                actions.melt,
                busy = state.busy,
            )
    }
}

/** The user's own note, handed out and not claimed yet. */
@Composable
private fun OwnCard(preview: ClaimPreview.OwnShared) {
    WalletCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            WalletNoteFace(
                size = NoteFaceSize.Large,
                preview.amountMsat,
                modifier = Modifier.fillMaxWidth(),
                status = NoteStatus.Waiting,
                place = NoteFacePlace.Claim,
            )
            Text(stringResource(R.string.claim_own_shared), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A note that can't be claimed now: why, what it is worth as far as known, and what keeping it means. */
@Composable
private fun KeepCard(preview: ClaimPreview.Keepable) {
    WalletCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val amount =
                when (preview) {
                    is ClaimPreview.Offline ->
                        preview.certifiedMsat?.let { formatAmount(it) }
                            ?: preview.declaredMsat?.let { stringResource(R.string.claim_amount_declared, formatAmount(it)) }
                    is ClaimPreview.Locked ->
                        preview.amountMsat?.let { formatAmount(it) }
                            ?: preview.link.declaredAmountMsat?.let { stringResource(R.string.claim_amount_declared, formatAmount(it)) }
                    is ClaimPreview.KeyChanged -> formatAmount(preview.amountMsat)
                }
            amount?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            Text(
                when (preview) {
                    is ClaimPreview.Offline -> stringResource(R.string.claim_offline)
                    is ClaimPreview.Locked ->
                        preview.untilMillis?.let { stringResource(R.string.claim_locked_until, recentTime(it)) }
                            ?: stringResource(R.string.claim_locked_for, lockDuration(preview.forSeconds ?: 0))
                    is ClaimPreview.KeyChanged -> stringResource(R.string.claim_key_changed, preview.host)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.claim_kept_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A wait in the largest whole unit that doesn't understate it: minutes, hours or days, rounded up. */
@Composable
private fun lockDuration(seconds: Long): String {
    val minutes = (seconds + 59) / 60
    val hours = (minutes + 59) / 60
    val days = (hours + 23) / 24
    return when {
        hours >= 24 -> pluralStringResource(R.plurals.claim_duration_days, days.toInt(), days)
        minutes >= 60 -> pluralStringResource(R.plurals.claim_duration_hours, hours.toInt(), hours)
        else -> pluralStringResource(R.plurals.claim_duration_minutes, minutes.toInt(), minutes)
    }
}

@Composable
private fun previewMessage(preview: ClaimPreview): String =
    when (preview) {
        is ClaimPreview.Invalid -> stringResource(R.string.claim_invalid, preview.reason)
        ClaimPreview.OfflineLink -> stringResource(R.string.claim_offline_link)
        ClaimPreview.AlreadySpent -> stringResource(R.string.claim_spent)
        ClaimPreview.AlreadyInWallet -> stringResource(R.string.claim_in_wallet)
        ClaimPreview.NotSpendable -> stringResource(R.string.claim_not_spendable)
        is ClaimPreview.Ready,
        is ClaimPreview.Keepable,
        is ClaimPreview.Melt,
        is ClaimPreview.PayLink,
        is ClaimPreview.OwnShared -> ""
    }

@Composable
private fun TakeBackMessage(result: ReclaimResult) {
    val (text, isError) =
        when (result) {
            is ReclaimResult.Reclaimed -> stringResource(R.string.claim_taken_back, formatAmount(result.amountMsat)) to false
            ReclaimResult.Pending -> stringResource(R.string.claim_pending) to false
            ReclaimResult.AlreadySpent -> stringResource(R.string.claim_spent) to true
            is ReclaimResult.Failed -> stringResource(R.string.claim_take_back_failed) to true
        }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ResultMessage(result: ClaimResult) {
    val (text, isError) =
        when (result) {
            is ClaimResult.Claimed -> stringResource(R.string.claim_done, formatAmount(result.amountMsat)) to false
            is ClaimResult.Failed -> stringResource(R.string.claim_failed, result.reason ?: result.kind.name) to true
            ClaimResult.Pending -> stringResource(R.string.claim_pending) to false
            is ClaimResult.Kept -> {
                val note = result.note
                when {
                    note.state == ReceivedState.Locked && note.lockedUntilMillis != null ->
                        stringResource(R.string.claim_kept_until, recentTime(note.lockedUntilMillis!!))
                    note.state == ReceivedState.Locked -> stringResource(R.string.claim_kept_locked)
                    note.state == ReceivedState.KeyChanged -> stringResource(R.string.claim_kept_key_changed)
                    note.state == ReceivedState.LightningOnly && note.archivedReason == ArchiveReason.NoSigningKey ->
                        stringResource(R.string.claim_kept_for_lightning)
                    note.state == ReceivedState.LightningOnly -> stringResource(R.string.claim_melt_refused, note.host)
                    else -> stringResource(R.string.claim_kept)
                } to false
            }
        }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}
