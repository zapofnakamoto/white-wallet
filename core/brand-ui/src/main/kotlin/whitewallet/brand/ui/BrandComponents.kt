package whitewallet.brand.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

    /**
     * What stands in for a secret QR code until the user taps it: as large as the code, with [QrCodeCoverParams.label]
     * as its text. The app's [modifier] carries the tap and what screen readers say; draw it as given.
     */
    @Composable fun QrCodeCover(params: QrCodeCoverParams, modifier: Modifier) = DefaultComponents.qrCodeCover(params, modifier)

    /** The leading mark of a history entry: what kind it is, and whether the wallet did it by itself. */
    @Composable fun ActivityIcon(params: ActivityIconParams, modifier: Modifier) = DefaultComponents.activityIcon(params, modifier)

    /**
     * The leading mark of a list row that says what the row is (a settings page) or how it stands (a note that's
     * still being paid). Always decorative: the row says the same in words.
     */
    @Composable fun ListIcon(params: ListIconParams, modifier: Modifier) = DefaultComponents.listIcon(params, modifier)

    /** An account's mark: the first letter of its name on a colour of its own, so accounts tell apart at a glance. */
    @Composable fun AccountMark(params: AccountMarkParams, modifier: Modifier) = DefaultComponents.accountMark(params, modifier)

    /** The app's release stage (alpha, beta) by its name, at setup and in About; never shown for a stable release. */
    @Composable fun ReleaseBadge(params: ReleaseBadgeParams, modifier: Modifier) = DefaultComponents.releaseBadge(params, modifier)

    /**
     * A note, the one object a brand designs once: a row in the notes list ([NoteFaceSize.Row]), and large on the
     * note's details, the claim preview and a handed-out note's page, around its QR code ([NoteFaceSize.Large]). The app
     * keeps what can be done with it (taps, selection, its actions and their confirmations) and the bearer warning;
     * the face only shows the note. Draw the texts it's given with `Text`: they are what screen readers read.
     */
    @Composable fun NoteFace(params: NoteFaceParams, modifier: Modifier) = DefaultComponents.noteFace(params, modifier)

    /** The heading of one mint's notes in the notes list: its name, labels and total, or only a separator. */
    @Composable fun NoteGroupHeader(params: NoteGroupParams, modifier: Modifier) = DefaultComponents.noteGroupHeader(params, modifier)
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

class QrCodeCoverParams(val label: String, val size: Dp)

/** What a history entry is. [Split], [Combined] and [Renewed] are note maintenance: the balance stays the same. */
enum class ActivityType { Received, Claimed, Sent, SentNote, MovedAccounts, MovedMints, Split, Combined, Renewed }

/** Which way the entry moved the account's money: [Within] keeps it in the account (a move between mints, maintenance). */
enum class MoneyDirection { In, Out, Within }

class ReleaseBadgeParams(
    /** [ReleaseStage.Alpha] or [ReleaseStage.Beta]. */
    val stage: ReleaseStage,
    /** The stage's name in the user's language ("Beta"). */
    val label: String,
)

/** [Row]: a row's leading mark; [Inline]: beside a line of text, at its size. */
enum class ListIconSize { Row, Inline }

class ListIconParams(
    /** The icon (a brand may replace the drawable, or draw its own). */
    val icon: Painter,
    /** [Tone.Danger] and [Tone.Warning] for rows that need the user; [Tone.Neutral] otherwise. */
    val tone: Tone = Tone.Neutral,
    val size: ListIconSize = ListIconSize.Row,
)

class AccountMarkParams(
    /** The first character of the account's name (one grapheme, upper case where the script has case); null when the name is blank. */
    val letter: String?,
    /** The account's place among the wallet's accounts, oldest first: give neighbours different colours. */
    val index: Int,
    /** The account in use (in the account switcher); the row says so to screen readers. */
    val active: Boolean,
    /** The mark's diameter; the letter scales with it, not with the font size, so it always fits. */
    val size: Dp,
    /** For a [letter] of null. */
    val fallback: Painter,
    /** The mark of the [active] account. */
    val activeBadge: Painter,
)

/** [Row]: one line of the notes list; [Large]: the note on a page of its own. */
enum class NoteFaceSize { Row, Large }

/**
 * How a note stands. In the wallet: [Ready] to use, [Paying] (being spent), [Unclear] (a payment whose outcome isn't
 * known yet), [Uncertified] (its amount isn't certified). Handed out: [Waiting] for its recipient, [Redeemed] by them,
 * [TakenBack]. New statuses may be added in later releases: a brand's `when` needs an `else` branch.
 */
enum class NoteStatus { Ready, Paying, Unclear, Uncertified, Waiting, Redeemed, TakenBack }

class NoteFaceParams(
    val size: NoteFaceSize,
    /** The amount, formatted in the user's unit. */
    val amount: String,
    val amountMsat: Long,
    val status: NoteStatus = NoteStatus.Ready,
    /** What is unusual about the note, in words; null when it is simply [NoteStatus.Ready]. */
    val statusText: String? = null,
    /** The mark beside [statusText] (a brand may replace the drawable, or draw its own). */
    val statusIcon: Painter? = null,
    /** How long ago it last changed, in words; null where it isn't shown. */
    val age: String? = null,
    /** The mint it is held on; null where mints aren't named (brand `showMints=false`). */
    val mint: String? = null,
    /** The user trusts its mint (by its signing key). */
    val trusted: Boolean = false,
    /** While the user selects notes: whether this one is selected; null otherwise. The app handles the tap. */
    val selected: Boolean? = null,
    /** False when it can't be selected or acted on right now (it is being paid, or the wallet is busy). */
    val enabled: Boolean = true,
    /**
     * The note's QR code, on a handed-out note's page only, drawn by the app: place and frame it, never alter or cover
     * it. On [NoteFaceSize.Large] the app writes the status, age and mint below the face; use them for its look, not to
     * repeat them.
     */
    val qrCode: (@Composable () -> Unit)? = null,
)

class NoteGroupParams(
    /** The mint's host; null where mints aren't named: then the header only separates two mints' notes. */
    val mint: String?,
    /** What the group's notes count in the balance, formatted. */
    val total: String,
    val totalMsat: Long,
    val home: Boolean,
    val trusted: Boolean,
    /** [home] and [trusted] in words ("Home mint · Trusted"); null when neither. */
    val labels: String?,
    /** The mark of a trusted mint. */
    val trustedIcon: Painter,
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

    /** A box in the surface variant colour, as large as the code, with the label in its middle. */
    @Composable
    fun qrCodeCover(params: QrCodeCoverParams, modifier: Modifier) {
        Box(
            modifier.size(params.size).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                params.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
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

    /**
     * [NoteFaceSize.Row]: a list row with the amount, the status beside its mark, the age, and a checkbox while
     * selecting. [NoteFaceSize.Large]: the amount as on the wallet tab, and the QR code below it. Drawn through the
     * brand's own [ListRow], [Toggle], [ListIcon] and [Amount], so restyling those restyles notes too.
     */
    @Composable
    fun noteFace(params: NoteFaceParams, modifier: Modifier) {
        val components = LocalBrandDesign.current.components
        when (params.size) {
            NoteFaceSize.Row -> components.ListRow(
                ListRowParams(
                    headline = { Text(params.amount, style = MaterialTheme.typography.bodyLarge) },
                    supporting = params.statusText?.let { text ->
                        {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                params.statusIcon?.let { components.ListIcon(ListIconParams(it, size = ListIconSize.Inline), Modifier) }
                                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    leading = params.selected?.let { selected ->
                        { components.Toggle(ToggleParams(ToggleKind.Checkbox, selected, onCheckedChange = null, enabled = params.enabled), Modifier) }
                    },
                    trailing = params.age?.let { age -> { Text(age, style = MaterialTheme.typography.bodySmall) } },
                ),
                modifier,
            )
            NoteFaceSize.Large -> Column(
                modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.walletSpacing.gap),
            ) {
                components.Amount(AmountParams(params.amount), Modifier.fillMaxWidth())
                params.qrCode?.invoke()
            }
        }
    }

    /** A line above the group; with a [NoteGroupParams.mint], a row with its name, labels and total below it. */
    @Composable
    fun noteGroupHeader(params: NoteGroupParams, modifier: Modifier) {
        Column(modifier) {
            HorizontalDivider()
            val mint = params.mint ?: return@Column
            LocalBrandDesign.current.components.ListRow(
                ListRowParams(
                    headline = { Text(mint, style = MaterialTheme.typography.titleSmall) },
                    supporting = params.labels?.let { labels -> { Text(labels) } },
                    leading = if (params.trusted) ({ Icon(params.trustedIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    trailing = { Text(params.total, style = MaterialTheme.typography.titleSmall) },
                ),
                Modifier,
            )
        }
    }

    /** The icon in the secondary text colour, or the tone's colour. */
    @Composable
    fun listIcon(params: ListIconParams, modifier: Modifier) {
        val tint = when (params.tone) {
            Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
            Tone.Positive -> MaterialTheme.colorScheme.primary
            Tone.Warning -> MaterialTheme.walletColors.warning
            Tone.Danger -> MaterialTheme.colorScheme.error
        }
        val size = when (params.size) {
            ListIconSize.Row -> 24.dp
            ListIconSize.Inline -> 16.dp
        }
        Icon(params.icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
    }

    /** The letter on a primary, tertiary or secondary container by [AccountMarkParams.index]; a check badge when active. */
    @Composable
    fun accountMark(params: AccountMarkParams, modifier: Modifier) {
        // Tertiary second: secondary is often a shade of primary, and the first two accounts should differ most.
        val (container, content) = when (params.index.mod(3)) {
            0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            1 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        }
        Box(modifier.size(params.size)) {
            Box(Modifier.size(params.size).background(container, CircleShape), contentAlignment = Alignment.Center) {
                val letter = params.letter
                if (letter != null) {
                    val fontSize = with(LocalDensity.current) { (params.size * 0.45f).toSp() }
                    Text(letter, color = content, fontSize = fontSize, lineHeight = fontSize, fontWeight = FontWeight.Medium, maxLines = 1)
                } else {
                    Icon(params.fallback, contentDescription = null, tint = content, modifier = Modifier.size(params.size * 0.6f))
                }
            }
            if (params.active) {
                // Half outside the circle, clear of the letter.
                val badge = params.size * 0.45f
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(badge * 0.3f, badge * 0.3f).size(badge).background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(params.activeBadge, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(badge * 0.85f))
                }
            }
        }
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
