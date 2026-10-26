package whitewallet.core.designsystem

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import whitewallet.brand.ui.ButtonKind
import whitewallet.brand.ui.ButtonParams
import whitewallet.brand.ui.CardParams
import whitewallet.brand.ui.ChipKind
import whitewallet.brand.ui.ChipParams
import whitewallet.brand.ui.DialogParams
import whitewallet.brand.ui.ListRowParams
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.brand.ui.NavigationBarParams
import whitewallet.brand.ui.NavigationItem
import whitewallet.brand.ui.ProgressKind
import whitewallet.brand.ui.ProgressParams
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
