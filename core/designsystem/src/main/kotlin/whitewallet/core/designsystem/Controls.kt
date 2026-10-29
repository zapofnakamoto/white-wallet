package whitewallet.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import android.icu.text.BreakIterator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import whitewallet.brand.ReleaseStage
import whitewallet.brand.ui.AccountMarkParams
import whitewallet.brand.ui.ActivityIconParams
import whitewallet.brand.ui.ActivityType
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.ButtonParams
import whitewallet.brand.ui.CardParams
import whitewallet.brand.ui.ChipKind
import whitewallet.brand.ui.ChipParams
import whitewallet.brand.ui.DialogParams
import whitewallet.brand.ui.ListIconParams
import whitewallet.brand.ui.SnackbarParams
import whitewallet.brand.ui.ListIconSize
import whitewallet.brand.ui.ListRowParams
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.brand.ui.MoneyDirection
import whitewallet.brand.ui.NavigationBarParams
import whitewallet.brand.ui.NavigationItem
import whitewallet.brand.ui.NoteFaceParams
import whitewallet.brand.ui.NoteFacePlace
import whitewallet.brand.ui.NoteFaceSize
import whitewallet.brand.ui.NoteGroupParams
import whitewallet.brand.ui.NoteStatus
import whitewallet.brand.ui.ProgressKind
import whitewallet.brand.ui.ProgressParams
import whitewallet.brand.ui.ReleaseBadgeParams
import whitewallet.brand.ui.SheetParams
import whitewallet.brand.ui.TextFieldParams
import whitewallet.brand.ui.ToggleKind
import whitewallet.brand.ui.ToggleParams
import whitewallet.brand.ui.Tone

// The controls feature screens are built from. Each draws through the brand's components (whitewallet.brand.ui,
// docs/WHITE_LABEL.md "Design"), so a brand restyles every screen at once; features never use Material's directly
// (dev/check-design.sh).

@Composable
fun WalletButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    tone: Tone = Tone.Neutral,
    enabled: Boolean = true,
    busy: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    LocalBrandDesign.current.components.Button(ButtonParams(onClick, kind, tone, enabled, busy, content), modifier)
}

@Composable
fun WalletTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle? = null,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    LocalBrandDesign.current.components.TextField(
        TextFieldParams(
            value, onValueChange, label, placeholder, supportingText, leadingIcon, trailingIcon, prefix, suffix, isError, enabled, readOnly,
            singleLine, minLines, maxLines, textStyle, keyboardOptions, keyboardActions, visualTransformation,
        ),
        modifier,
    )
}

@Composable
fun WalletListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    LocalBrandDesign.current.components.ListRow(
        ListRowParams(headlineContent, overlineContent, supportingContent, leadingContent, trailingContent),
        modifier,
    )
}

/** A history entry's mark, drawn by the brand ([whitewallet.brand.ui.BrandComponents.ActivityIcon]) with the type's icon. */
@Composable
fun WalletActivityIcon(type: ActivityType, direction: MoneyDirection, automatic: Boolean, settled: Boolean, modifier: Modifier = Modifier) {
    val icon = when (type) {
        ActivityType.Received -> R.drawable.ic_activity_received
        ActivityType.Claimed -> R.drawable.ic_activity_claimed
        ActivityType.Sent -> R.drawable.ic_activity_sent
        ActivityType.SentNote -> R.drawable.ic_activity_sent_note
        ActivityType.MovedAccounts -> R.drawable.ic_activity_moved
        ActivityType.MovedMints -> R.drawable.ic_activity_moved_mint
        ActivityType.Split -> R.drawable.ic_activity_split
        ActivityType.Combined -> R.drawable.ic_activity_combined
        ActivityType.Renewed -> R.drawable.ic_activity_rotated
    }
    LocalBrandDesign.current.components.ActivityIcon(
        ActivityIconParams(type, direction, automatic, settled, painterResource(icon), painterResource(R.drawable.ic_activity_automatic)),
        modifier,
    )
}

/**
 * An account's mark ([whitewallet.brand.ui.BrandComponents.AccountMark]): the first letter of [name] on the colour
 * of its [index] among the accounts (oldest first). Decorative: the name is always shown beside it.
 */
@Composable
fun WalletAccountMark(name: String, index: Int, modifier: Modifier = Modifier, active: Boolean = false, size: Dp = 40.dp) {
    val locale = LocalConfiguration.current.locales[0]
    val letter = remember(name, locale) { firstLetter(name, locale) }
    LocalBrandDesign.current.components.AccountMark(
        AccountMarkParams(letter, index, active, size, painterResource(R.drawable.ic_account), painterResource(R.drawable.ic_check_circle)),
        modifier,
    )
}

/** The first grapheme of [name] (a letter with its accents, a whole emoji), upper case where its script has case. */
internal fun firstLetter(name: String, locale: Locale): String? {
    val trimmed = name.trim().ifEmpty { return null }
    val breaks = BreakIterator.getCharacterInstance(locale).apply { setText(trimmed) }
    return trimmed.substring(0, breaks.next()).uppercase(locale)
}

/**
 * A note as the brand draws it ([whitewallet.brand.ui.BrandComponents.NoteFace]). [modifier] carries what the app does
 * with it (taps, selection); [NoteFaceSize.Large] is read out by its amount, like [AmountText].
 */
@Composable
fun WalletNoteFace(
    size: NoteFaceSize,
    amountMsat: Long,
    modifier: Modifier = Modifier,
    status: NoteStatus = NoteStatus.Ready,
    statusText: String? = null,
    @DrawableRes statusIcon: Int? = null,
    age: String? = null,
    mint: String? = null,
    trusted: Boolean = false,
    selected: Boolean? = null,
    enabled: Boolean = true,
    qrCode: (@Composable () -> Unit)? = null,
    place: NoteFacePlace = NoteFacePlace.Wallet,
    qrCovered: Boolean = false,
) {
    val spoken = if (size == NoteFaceSize.Large) spokenAmount(amountMsat) else null
    LocalBrandDesign.current.components.NoteFace(
        NoteFaceParams(
            size, formatAmount(amountMsat), amountMsat, status, statusText, statusIcon?.let { painterResource(it) }, age, mint, trusted,
            selected, enabled, qrCode, place, qrCovered,
        ),
        if (spoken != null) modifier.semantics { contentDescription = spoken } else modifier,
    )
}

/**
 * The heading of one mint's notes ([whitewallet.brand.ui.BrandComponents.NoteGroupHeader]); a heading for screen
 * readers when it names the [mint], else only a separator.
 */
@Composable
fun WalletNoteGroupHeader(mint: String?, totalMsat: Long, home: Boolean, trusted: Boolean, labels: String?, modifier: Modifier = Modifier) {
    LocalBrandDesign.current.components.NoteGroupHeader(
        NoteGroupParams(mint, formatAmount(totalMsat), totalMsat, home, trusted, labels, painterResource(R.drawable.ic_check_circle)),
        if (mint != null) modifier.semantics(mergeDescendants = true) { heading() } else modifier,
    )
}

/** A list row's leading mark ([whitewallet.brand.ui.BrandComponents.ListIcon]); decorative, the row says it in words. */
@Composable
fun WalletListIcon(@DrawableRes icon: Int, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, size: ListIconSize = ListIconSize.Row) {
    LocalBrandDesign.current.components.ListIcon(ListIconParams(painterResource(icon), tone, size), modifier)
}

/** The release stage's badge ([whitewallet.brand.ui.BrandComponents.ReleaseBadge]); nothing for a stable release. */
@Composable
fun WalletReleaseBadge(modifier: Modifier = Modifier) {
    val stage = LocalReleaseStage.current
    val label = when (stage) {
        ReleaseStage.Alpha -> stringResource(R.string.release_stage_alpha)
        ReleaseStage.Beta -> stringResource(R.string.release_stage_beta)
        ReleaseStage.Stable -> return
    }
    LocalBrandDesign.current.components.ReleaseBadge(ReleaseBadgeParams(stage, label), modifier)
}

/** What the release stage means for the user, as a sentence; null for a stable release. */
@Composable
fun releaseNotice(): String? = when (LocalReleaseStage.current) {
    ReleaseStage.Alpha -> stringResource(R.string.release_notice_alpha)
    ReleaseStage.Beta -> stringResource(R.string.release_notice_beta)
    ReleaseStage.Stable -> null
}

@Composable
fun WalletCard(modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, content: @Composable ColumnScope.() -> Unit) {
    LocalBrandDesign.current.components.Card(CardParams(tone, content), modifier)
}

@Composable
fun WalletDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    LocalBrandDesign.current.components.Dialog(DialogParams(onDismissRequest, confirmButton, dismissButton, icon, title, text))
}

@Composable
fun WalletSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    LocalBrandDesign.current.components.Sheet(SheetParams(onDismissRequest, content))
}

/**
 * Where [WalletSnackbarHost]'s messages appear: Material's [SnackbarHost] with each message drawn by the brand. A
 * message's action label is its button; [SnackbarHostState.showSnackbar] says whether it was tapped.
 */
@Composable
fun WalletSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        LocalBrandDesign.current.components.Snackbar(SnackbarParams(data.visuals.message, data.visuals.actionLabel, data::performAction), Modifier)
    }
}

@Composable
fun WalletSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    LocalBrandDesign.current.components.Toggle(ToggleParams(ToggleKind.Switch, checked, onCheckedChange, enabled), modifier)
}

@Composable
fun WalletCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    LocalBrandDesign.current.components.Toggle(ToggleParams(ToggleKind.Checkbox, checked, onCheckedChange, enabled), modifier)
}

@Composable
fun WalletRadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    LocalBrandDesign.current.components.Toggle(ToggleParams(ToggleKind.Radio, selected, onClick?.let { { _: Boolean -> it() } }, enabled), modifier)
}

@Composable
fun WalletFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    LocalBrandDesign.current.components.Chip(ChipParams(ChipKind.Filter, onClick, label, selected, enabled, leadingIcon, trailingIcon), modifier)
}

@Composable
fun WalletAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    LocalBrandDesign.current.components.Chip(ChipParams(ChipKind.Assist, onClick, label, false, enabled, leadingIcon, trailingIcon), modifier)
}

@Composable
fun WalletSuggestionChip(onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    LocalBrandDesign.current.components.Chip(ChipParams(ChipKind.Suggestion, onClick, label, enabled = enabled), modifier)
}

/** Progress along a line: indeterminate without [progress]. */
@Composable
fun WalletLinearProgress(modifier: Modifier = Modifier, progress: (() -> Float)? = null) {
    LocalBrandDesign.current.components.Progress(ProgressParams(ProgressKind.Linear, progress), modifier)
}

/** A spinner: indeterminate without [progress]. */
@Composable
fun WalletCircularProgress(modifier: Modifier = Modifier, progress: (() -> Float)? = null) {
    LocalBrandDesign.current.components.Progress(ProgressParams(ProgressKind.Circular, progress), modifier)
}

@Composable
fun WalletNavigationBar(items: List<NavigationItem>, modifier: Modifier = Modifier) {
    LocalBrandDesign.current.components.NavigationBar(NavigationBarParams(items), modifier)
}
