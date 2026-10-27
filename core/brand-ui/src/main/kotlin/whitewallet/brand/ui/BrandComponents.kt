package whitewallet.brand.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import whitewallet.brand.ReleaseStage

/**
 * The building blocks every screen is made of. Each takes one parameter object (new options are added there with a
 * default, so a brand's overrides keep compiling) and a [Modifier] that the implementation must apply to its root.
 * The defaults ([DefaultComponents]) are Material 3, themed by [ThemeTokens]; a brand overrides what it restyles:
 *
 * ```
 * object MyComponents : BrandComponents {
 *     @Composable override fun Button(params: ButtonParams, modifier: Modifier) { … }
 * }
 * ```
 *
 * Texts arrive translated and semantics are set by the app; an implementation only lays them out.
 */
interface BrandComponents {
    @Composable fun Button(params: ButtonParams, modifier: Modifier) = DefaultComponents.button(params, modifier)

    /** A large amount, such as the balance, already formatted. */
    @Composable fun Amount(params: AmountParams, modifier: Modifier) = DefaultComponents.amount(params, modifier)

    @Composable fun TopBar(params: TopBarParams, modifier: Modifier) = DefaultComponents.topBar(params, modifier)

    @Composable fun TextField(params: TextFieldParams, modifier: Modifier) = DefaultComponents.textField(params, modifier)

    /** One row of a list: a setting, an account, an entry of the history. */
    @Composable fun ListRow(params: ListRowParams, modifier: Modifier) = DefaultComponents.listRow(params, modifier)

    @Composable fun Card(params: CardParams, modifier: Modifier) = DefaultComponents.card(params, modifier)

    @Composable fun Dialog(params: DialogParams) = DefaultComponents.dialog(params)

    @Composable fun Sheet(params: SheetParams) = DefaultComponents.sheet(params)

    /** A switch, checkbox or radio button. */
    @Composable fun Toggle(params: ToggleParams, modifier: Modifier) = DefaultComponents.toggle(params, modifier)

    @Composable fun Chip(params: ChipParams, modifier: Modifier) = DefaultComponents.chip(params, modifier)

    @Composable fun Progress(params: ProgressParams, modifier: Modifier) = DefaultComponents.progress(params, modifier)

    /** The bar of the wallet's tabs. */
    @Composable fun NavigationBar(params: NavigationBarParams, modifier: Modifier) = DefaultComponents.navigationBar(params, modifier)

    /** A QR code to scan: the code itself is drawn by the app; a design may frame it, never alter it. */
    @Composable fun QrCode(params: QrCodeParams, modifier: Modifier) = DefaultComponents.qrCode(params, modifier)

    /** The leading mark of a history entry: what kind it is, and whether the wallet did it by itself. */
    @Composable fun ActivityIcon(params: ActivityIconParams, modifier: Modifier) = DefaultComponents.activityIcon(params, modifier)

    /** The app's release stage (alpha, beta) by its name, at setup and in About; never shown for a stable release. */
    @Composable fun ReleaseBadge(params: ReleaseBadgeParams, modifier: Modifier) = DefaultComponents.releaseBadge(params, modifier)
}

/** How prominent a button is: one [Primary] action per screen, [Secondary] and [Text] for the rest. */
enum class ButtonKind { Primary, Tonal, Secondary, Text }

/** What a button, card or text is about, beyond its kind. */
enum class Tone { Neutral, Positive, Warning, Danger }

class ButtonParams(
    val onClick: () -> Unit,
    val kind: ButtonKind = ButtonKind.Primary,
    val tone: Tone = Tone.Neutral,
    val enabled: Boolean = true,
    /** Working: show progress instead of the label (the button is disabled meanwhile). */
    val busy: Boolean = false,
    val content: @Composable RowScope.() -> Unit,
)

class AmountParams(val text: String)

class TopBarParams(
    val title: @Composable () -> Unit,
    val navigationIcon: @Composable () -> Unit = {},
    val actions: @Composable RowScope.() -> Unit = {},
)

class TextFieldParams(
    val value: String,
    val onValueChange: (String) -> Unit,
    val label: (@Composable () -> Unit)? = null,
    val placeholder: (@Composable () -> Unit)? = null,
    val supportingText: (@Composable () -> Unit)? = null,
    val leadingIcon: (@Composable () -> Unit)? = null,
    val trailingIcon: (@Composable () -> Unit)? = null,
    val prefix: (@Composable () -> Unit)? = null,
    val suffix: (@Composable () -> Unit)? = null,
    val isError: Boolean = false,
    val enabled: Boolean = true,
    val readOnly: Boolean = false,
    val singleLine: Boolean = false,
    val minLines: Int = 1,
    val maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    /** Null: the theme's body text. */
    val textStyle: TextStyle? = null,
    val keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    val keyboardActions: KeyboardActions = KeyboardActions.Default,
    val visualTransformation: VisualTransformation = VisualTransformation.None,
)

class ListRowParams(
    val headline: @Composable () -> Unit,
    val overline: (@Composable () -> Unit)? = null,
    val supporting: (@Composable () -> Unit)? = null,
    val leading: (@Composable () -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null,
)

class CardParams(
    val tone: Tone = Tone.Neutral,
    val content: @Composable ColumnScope.() -> Unit,
)

class DialogParams(
    val onDismissRequest: () -> Unit,
    val confirmButton: @Composable () -> Unit,
    val dismissButton: (@Composable () -> Unit)? = null,
    val icon: (@Composable () -> Unit)? = null,
    val title: (@Composable () -> Unit)? = null,
    val text: (@Composable () -> Unit)? = null,
)

class SheetParams(val onDismissRequest: () -> Unit, val content: @Composable ColumnScope.() -> Unit)

enum class ToggleKind { Switch, Checkbox, Radio }

class ToggleParams(
    val kind: ToggleKind,
    val checked: Boolean,
    /** Null: the toggle only shows its state (its row handles the click). A radio button is only ever set to true. */
    val onCheckedChange: ((Boolean) -> Unit)?,
    val enabled: Boolean = true,
)

/** [Filter]: on or off; [Assist]: a shortcut to an action; [Suggestion]: a value to fill in. */
enum class ChipKind { Filter, Assist, Suggestion }

class ChipParams(
    val kind: ChipKind,
    val onClick: () -> Unit,
    val label: @Composable () -> Unit,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val leadingIcon: (@Composable () -> Unit)? = null,
    val trailingIcon: (@Composable () -> Unit)? = null,
)

enum class ProgressKind { Linear, Circular }

class ProgressParams(
    val kind: ProgressKind,
    /** 0..1, or null while it's unknown how far along it is. */
    val progress: (() -> Float)? = null,
)

class NavigationItem(val selected: Boolean, val onClick: () -> Unit, val icon: @Composable () -> Unit, val label: @Composable () -> Unit)

class NavigationBarParams(val items: List<NavigationItem>)

class QrCodeParams(val bitmap: ImageBitmap, val size: Dp)

/** What a history entry is. [Split], [Combined] and [Renewed] are note maintenance: the balance stays the same. */
enum class ActivityType { Received, Claimed, Sent, SentNote, MovedAccounts, MovedServices, Split, Combined, Renewed }

/** Which way the entry moved the account's money: [Within] keeps it in the account (a move between mints, maintenance). */
enum class MoneyDirection { In, Out, Within }

class ReleaseBadgeParams(
    /** [ReleaseStage.Alpha] or [ReleaseStage.Beta]. */
    val stage: ReleaseStage,
    /** The stage's name in the user's language ("Beta"). */
    val label: String,
)

class ActivityIconParams(
    val type: ActivityType,
    val direction: MoneyDirection,
    /** Done by the wallet itself (the note strategy, collecting at the home mint), not by the user. */
    val automatic: Boolean,
    /** False while it is still being confirmed, and when it failed or was taken back. */
    val settled: Boolean,
    /** The type's icon (a brand may replace the drawable, or draw its own). */
    val icon: Painter,
    /** The mark for [automatic] entries. */
    val automaticBadge: Painter,
)

/** Material 3, as the app looks without a brand design. Brand components can call these to wrap or fall back. */
object DefaultComponents : BrandComponents {
    @Composable
    fun button(params: ButtonParams, modifier: Modifier) {
        val enabled = params.enabled && !params.busy
        val content: @Composable RowScope.() -> Unit = {
            if (params.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else params.content(this)
        }
        val danger = params.tone == Tone.Danger
        val error = MaterialTheme.colorScheme.error
        when (params.kind) {
            ButtonKind.Primary -> Button(
                params.onClick, modifier, enabled,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                content = content,
            )
            ButtonKind.Tonal -> FilledTonalButton(params.onClick, modifier, enabled, content = content)
            ButtonKind.Secondary -> OutlinedButton(
                params.onClick, modifier, enabled,
                colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = error) else ButtonDefaults.outlinedButtonColors(),
                content = content,
            )
            ButtonKind.Text -> TextButton(
                params.onClick, modifier, enabled,
                colors = if (danger) ButtonDefaults.textButtonColors(contentColor = error) else ButtonDefaults.textButtonColors(),
                content = content,
            )
        }
    }

    @Composable
    fun amount(params: AmountParams, modifier: Modifier) {
        Text(params.text, modifier, color = MaterialTheme.walletColors.amount, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun topBar(params: TopBarParams, modifier: Modifier) {
        TopAppBar(title = params.title, modifier = modifier, navigationIcon = params.navigationIcon, actions = params.actions)
    }

    @Composable
    fun textField(params: TextFieldParams, modifier: Modifier) {
        OutlinedTextField(
            value = params.value,
            onValueChange = params.onValueChange,
            modifier = modifier,
            enabled = params.enabled,
            readOnly = params.readOnly,
            textStyle = params.textStyle ?: LocalTextStyle.current,
            label = params.label,
            placeholder = params.placeholder,
            leadingIcon = params.leadingIcon,
            trailingIcon = params.trailingIcon,
            prefix = params.prefix,
            suffix = params.suffix,
            supportingText = params.supportingText,
            isError = params.isError,
            visualTransformation = params.visualTransformation,
            keyboardOptions = params.keyboardOptions,
            keyboardActions = params.keyboardActions,
            singleLine = params.singleLine,
            maxLines = params.maxLines,
            minLines = params.minLines,
        )
    }

    @Composable
    fun listRow(params: ListRowParams, modifier: Modifier) {
        ListItem(
            headlineContent = params.headline,
            modifier = modifier,
            overlineContent = params.overline,
            supportingContent = params.supporting,
            leadingContent = params.leading,
            trailingContent = params.trailing,
        )
    }

    @Composable
    fun card(params: CardParams, modifier: Modifier) {
        val colors = when (params.tone) {
            Tone.Neutral -> CardDefaults.cardColors()
            Tone.Positive -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            Tone.Warning -> MaterialTheme.walletColors.let { CardDefaults.cardColors(containerColor = it.warningContainer, contentColor = it.onWarningContainer) }
            Tone.Danger -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        }
        Card(modifier, colors = colors, content = params.content)
    }

    @Composable
    fun dialog(params: DialogParams) {
        AlertDialog(
            onDismissRequest = params.onDismissRequest,
            confirmButton = params.confirmButton,
            dismissButton = params.dismissButton,
            icon = params.icon,
            title = params.title,
            text = params.text,
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun sheet(params: SheetParams) {
        ModalBottomSheet(onDismissRequest = params.onDismissRequest, content = params.content)
    }

    @Composable
    fun toggle(params: ToggleParams, modifier: Modifier) {
        when (params.kind) {
            ToggleKind.Switch -> Switch(params.checked, params.onCheckedChange, modifier, enabled = params.enabled)
            ToggleKind.Checkbox -> Checkbox(params.checked, params.onCheckedChange, modifier, enabled = params.enabled)
            ToggleKind.Radio -> RadioButton(params.checked, params.onCheckedChange?.let { { it(true) } }, modifier, enabled = params.enabled)
        }
    }

    @Composable
    fun chip(params: ChipParams, modifier: Modifier) {
        when (params.kind) {
            ChipKind.Filter -> FilterChip(
                params.selected, params.onClick, params.label, modifier, params.enabled,
                leadingIcon = params.leadingIcon, trailingIcon = params.trailingIcon,
            )
            ChipKind.Assist -> AssistChip(
                params.onClick, params.label, modifier, params.enabled,
                leadingIcon = params.leadingIcon, trailingIcon = params.trailingIcon,
            )
            ChipKind.Suggestion -> SuggestionChip(params.onClick, params.label, modifier, params.enabled, icon = params.leadingIcon)
        }
    }

    @Composable
    fun progress(params: ProgressParams, modifier: Modifier) {
        val progress = params.progress
        when (params.kind) {
            ProgressKind.Linear -> if (progress != null) LinearProgressIndicator(progress, modifier) else LinearProgressIndicator(modifier)
            ProgressKind.Circular -> if (progress != null) CircularProgressIndicator(progress, modifier) else CircularProgressIndicator(modifier)
        }
    }

    @Composable
    fun navigationBar(params: NavigationBarParams, modifier: Modifier) {
        NavigationBar(modifier) {
            params.items.forEach { item -> NavigationBarItem(item.selected, item.onClick, item.icon, label = item.label) }
        }
    }

    @Composable
    fun qrCode(params: QrCodeParams, modifier: Modifier) {
        Image(params.bitmap, contentDescription = null, modifier = modifier.size(params.size), filterQuality = FilterQuality.None)
    }

    /** The stage's name, small, on the warning container colour. */
    @Composable
    fun releaseBadge(params: ReleaseBadgeParams, modifier: Modifier) {
        Text(
            params.label,
            modifier.background(MaterialTheme.walletColors.warningContainer, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
            color = MaterialTheme.walletColors.onWarningContainer,
            style = MaterialTheme.typography.labelMedium,
        )
    }

    /** The type's icon on a circle tinted by where the money went; a small badge when the wallet did it by itself. */
    @Composable
    fun activityIcon(params: ActivityIconParams, modifier: Modifier) {
        val maintenance = params.type == ActivityType.Split || params.type == ActivityType.Combined || params.type == ActivityType.Renewed
        val tint = when {
            maintenance || !params.settled -> MaterialTheme.colorScheme.onSurfaceVariant
            params.direction == MoneyDirection.In -> MaterialTheme.walletColors.incoming
            params.direction == MoneyDirection.Out -> MaterialTheme.walletColors.outgoing
            else -> MaterialTheme.colorScheme.onSurface
        }
        Box(modifier.size(40.dp)) {
            Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(params.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            if (params.automatic) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(18.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(params.automaticBadge, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
