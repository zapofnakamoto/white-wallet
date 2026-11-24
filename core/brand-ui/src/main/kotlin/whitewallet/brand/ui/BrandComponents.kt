package whitewallet.brand.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import whitewallet.brand.ReleaseStage

/**
 * The building blocks every screen is made of. Each takes one parameter object (new options are added there with a default, so a brand's
 * overrides keep compiling) and a [Modifier] that the implementation must apply to its root. The defaults ([DefaultComponents]) are
 * Material 3, themed by [ThemeTokens]; a brand overrides what it restyles:
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

    /**
     * A short message at the bottom of the screen, gone after a few seconds: [SnackbarParams.message], and an action button if there is
     * one. The app decides when it shows and for how long.
     */
    @Composable fun Snackbar(params: SnackbarParams, modifier: Modifier) = DefaultComponents.snackbar(params, modifier)

    /** A switch, checkbox or radio button. */
    @Composable fun Toggle(params: ToggleParams, modifier: Modifier) = DefaultComponents.toggle(params, modifier)

    @Composable fun Chip(params: ChipParams, modifier: Modifier) = DefaultComponents.chip(params, modifier)

    @Composable fun Progress(params: ProgressParams, modifier: Modifier) = DefaultComponents.progress(params, modifier)

    /** The bar of the wallet's tabs. */
    @Composable fun NavigationBar(params: NavigationBarParams, modifier: Modifier) = DefaultComponents.navigationBar(params, modifier)

    /** A QR code to scan: the code itself is drawn by the app; a design may frame it, never alter it. */
    @Composable fun QrCode(params: QrCodeParams, modifier: Modifier) = DefaultComponents.qrCode(params, modifier)

    /**
     * What stands in for a secret QR code until the user taps it: as large as the code, with [QrCodeCoverParams.label] as its text. The
     * app's [modifier] carries the tap and what screen readers say; draw it as given.
     */
    @Composable fun QrCodeCover(params: QrCodeCoverParams, modifier: Modifier) = DefaultComponents.qrCodeCover(params, modifier)

    /** The leading mark of a history entry: what kind it is, and whether the wallet did it by itself. */
    @Composable fun ActivityIcon(params: ActivityIconParams, modifier: Modifier) = DefaultComponents.activityIcon(params, modifier)

    /**
     * The leading mark of a list row that says what the row is (a settings page) or how it stands (a note that's still being paid). Always
     * decorative: the row says the same in words.
     */
    @Composable fun ListIcon(params: ListIconParams, modifier: Modifier) = DefaultComponents.listIcon(params, modifier)

    /** An account's mark: the first letter of its name on a colour of its own, so accounts tell apart at a glance. */
    @Composable fun AccountMark(params: AccountMarkParams, modifier: Modifier) = DefaultComponents.accountMark(params, modifier)

    /** The app's release stage (alpha, beta) by its name, at setup and in About; never shown for a stable release. */
    @Composable fun ReleaseBadge(params: ReleaseBadgeParams, modifier: Modifier) = DefaultComponents.releaseBadge(params, modifier)

    /**
     * A note, the one object a brand designs once: a row in the notes list ([NoteFaceSize.Row]), and large on the note's details, the claim
     * preview and a handed-out note's page, around its QR code ([NoteFaceSize.Large]). The app keeps what can be done with it (taps,
     * selection, its actions and their confirmations) and the bearer warning; the face only shows the note. Draw the texts it's given with
     * `Text`: they are what screen readers read.
     */
    @Composable fun NoteFace(params: NoteFaceParams, modifier: Modifier) = DefaultComponents.noteFace(params, modifier)

    /** The heading of a mint's page: its name, labels and total; without a name (`mints.visible=false`) the total and labels. */
    @Composable fun NoteGroupHeader(params: NoteGroupParams, modifier: Modifier) = DefaultComponents.noteGroupHeader(params, modifier)

    /**
     * One mint's notes at a glance on the notes view: a pile of its bills, the biggest in front, with the mint's name, labels, total and
     * the range of its notes. The pile takes the tap itself ([NotePileParams.onClick], inside its own shape); the app handles the swiping
     * between mints.
     */
    @Composable fun NotePile(params: NotePileParams, modifier: Modifier) = DefaultComponents.notePile(params, modifier)
}

/** How prominent a button is: one [Primary] action per screen, [Secondary] and [Text] for the rest. */
enum class ButtonKind {
    Primary,
    Tonal,
    Secondary,
    Text,
}

/** What a button, card or text is about, beyond its kind. */
enum class Tone {
    Neutral,
    Positive,
    Warning,
    Danger,
}

class ButtonParams(
    val onClick: () -> Unit,
    val kind: ButtonKind = ButtonKind.Primary,
    val tone: Tone = Tone.Neutral,
    val enabled: Boolean = true,
    /**
     * At work for a while: show progress beside the label, which stays, as does the button's look. The button is locked by then ([onClick]
     * does nothing), and work over in a moment is never said, so a button never blinks.
     */
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
    /** The field takes the focus when it appears, its text selected: typing replaces it (a rename). */
    val focusSelected: Boolean = false,
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

/**
 * [openFully]: the sheet opens at its full height (as far as its content and the screen go) rather than at half the screen first, because
 * what comes up at its bottom, such as an action's outcome, has to be seen without dragging.
 */
class SheetParams(val onDismissRequest: () -> Unit, val content: @Composable ColumnScope.() -> Unit, val openFully: Boolean = false)

/** [actionLabel] and [onAction] come together: the message has a button, or none. */
class SnackbarParams(val message: String, val actionLabel: String? = null, val onAction: () -> Unit = {})

enum class ToggleKind {
    Switch,
    Checkbox,
    Radio,
}

class ToggleParams(
    val kind: ToggleKind,
    val checked: Boolean,
    /** Null: the toggle only shows its state (its row handles the click). A radio button is only ever set to true. */
    val onCheckedChange: ((Boolean) -> Unit)?,
    val enabled: Boolean = true,
)

/** [Filter]: on or off; [Assist]: a shortcut to an action; [Suggestion]: a value to fill in. */
enum class ChipKind {
    Filter,
    Assist,
    Suggestion,
}

class ChipParams(
    val kind: ChipKind,
    val onClick: () -> Unit,
    val label: @Composable () -> Unit,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val leadingIcon: (@Composable () -> Unit)? = null,
    val trailingIcon: (@Composable () -> Unit)? = null,
)

enum class ProgressKind {
    Linear,
    Circular,
}

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
enum class ActivityType {
    Received,
    Claimed,
    Sent,
    SentNote,
    MovedAccounts,
    MovedMints,
    Split,
    Combined,
    Renewed,
}

/** Which way the entry moved the account's money: [Within] keeps it in the account (a move between mints, maintenance). */
enum class MoneyDirection {
    In,
    Out,
    Within,
}

class ReleaseBadgeParams(
    /** [ReleaseStage.Alpha] or [ReleaseStage.Beta]. */
    val stage: ReleaseStage,
    /** The stage's name in the user's language ("Beta"). */
    val label: String,
)

/** [Row]: a row's leading mark; [Inline]: beside a line of text, at its size. */
enum class ListIconSize {
    Row,
    Inline,
}

class ListIconParams(
    /** The icon (a brand may replace the drawable, or draw its own). */
    val icon: Painter,
    /** [Tone.Danger] and [Tone.Warning] for rows that need the user; [Tone.Neutral] otherwise. */
    val tone: Tone = Tone.Neutral,
    val size: ListIconSize = ListIconSize.Row,
    /** The mark of the wallet in use, to draw on the icon as [AccountMarkParams.activeBadge] is on an account's; null on any other row. */
    val activeBadge: Painter? = null,
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
enum class NoteFaceSize {
    Row,
    Large,
}

/**
 * Where a note is drawn: in the user's own [Wallet] (the notes list, a note's details), on the claim preview before the user takes
 * someone's note ([Claim]), handed out ([Shared]: its page, and its row in the notes view, under the handed-out heading), or on the page
 * printed from there ([Paper]: white, the brand's light theme, the QR code never covered).
 */
enum class NoteFacePlace {
    Wallet,
    Claim,
    Shared,
    Paper,
}

/**
 * How a note stands. In the wallet: [Ready] to use, [Paying] (being spent), [Unclear] (a payment whose outcome isn't known yet),
 * [Uncertified] (its amount isn't certified), [Unknown] (its mint says it doesn't know it: set aside, out of the balance, until the mint
 * knows it again). Handed out: [Waiting] for its recipient, [Spent] by them, [TakenBack]. New statuses may be added in later releases: a
 * brand's `when` needs an `else` branch.
 */
enum class NoteStatus {
    Ready,
    Paying,
    Unclear,
    Uncertified,
    Waiting,
    Spent,
    TakenBack,
    Unknown,
}

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
    /** The mint it is held on; null where mints aren't named (brand `mints.visible=false`). */
    val mint: String? = null,
    /** The user trusts its mint (by its signing key). */
    val trusted: Boolean = false,
    /** While the user selects notes: whether this one is selected; null otherwise. The app handles the tap. */
    val selected: Boolean? = null,
    /** False when it can't be selected or acted on right now (it is being paid, or the wallet is busy). */
    val enabled: Boolean = true,
    /**
     * The note's QR code while it waits for its recipient, on a handed-out note's page and on paper, drawn by the app: place and frame it,
     * never alter or cover it. On the page it holds the app's cover until the user taps it ([qrCovered]). On [NoteFaceSize.Large] the app
     * writes the status, age and mint below the face; use them for its look, not to repeat them.
     */
    val qrCode: (@Composable () -> Unit)? = null,
    val place: NoteFacePlace = NoteFacePlace.Wallet,
    /** [qrCode] holds the cover over the code (`QrCodeCover`) until the user taps it; nothing to scan yet. False without a [qrCode]. */
    val qrCovered: Boolean = false,
    /** The colours of its mint's bills ([WalletColors.notes]); a note out of the balance is drawn greyed out ([NoteStatus.inBalance]). */
    val colors: NoteColors? = null,
    /**
     * Its mint's certificate for its amount verified (a certificate that doesn't verify is never kept). The default draws a certified bill
     * like a banknote (a foil stripe, engraved lines, a medal) and another plain; the words for one that isn't ([statusText]) stay the
     * app's.
     */
    val certified: Boolean,
)

/** Whether a note of this status counts in the balance: else a bill is drawn greyed out. */
val NoteStatus.inBalance: Boolean
    get() = this == NoteStatus.Ready || this == NoteStatus.Uncertified

class NotePileParams(
    /** The mint's host; null where mints aren't named (brand `mints.visible=false`). */
    val mint: String?,
    /** Home mint, trusted and how many notes, in words ("Home mint · Trusted · 84 notes"). */
    val labels: String,
    /** What its notes count in the balance, formatted. */
    val total: String,
    val totalMsat: Long,
    /** From its smallest note to its biggest, in words ("From 1,000 sats to 21,000 sats"); null with one note or all alike. */
    val range: String?,
    /** The notes on top of the pile, the biggest first (a few at most), formatted and in msat; empty when none counts in the balance. */
    val bills: List<String>,
    val billsMsat: List<Long>,
    /** Whether each of [bills] is certified ([NoteFaceParams.certified]), in the same order; a bill without one is drawn plain. */
    val billsCertified: List<Boolean>,
    /** The biggest note on any of the user's mints: bills are drawn to one scale across the mints. */
    val largestMsat: Long,
    val colors: NoteColors,
    val home: Boolean,
    val trusted: Boolean,
    /** The mark of a trusted mint. */
    val trustedIcon: Painter,
    /** The mark that says a tap opens the mint's notes (decorative). */
    val openIcon: Painter,
    /** Opens the mint's notes: make the whole pile clickable with it, inside the pile's own shape, so the ripple keeps its corners. */
    val onClick: () -> Unit,
    /** What a tap does, for screen readers ("Open this mint's notes"). */
    val onClickLabel: String,
)

class NoteGroupParams(
    /** The mint's host; null where mints aren't named. */
    val mint: String?,
    /** What the group's notes count in the balance, formatted. */
    val total: String,
    val totalMsat: Long,
    val home: Boolean,
    val trusted: Boolean,
    /**
     * [home], [trusted] and how many notes, in words ("Home mint · Trusted · 84 notes"), as on the pile; null when there's nothing to say.
     */
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
        val enabled = params.enabled
        val content: @Composable RowScope.() -> Unit = {
            if (params.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            params.content(this)
        }
        val danger = params.tone == Tone.Danger
        val error = MaterialTheme.colorScheme.error
        when (params.kind) {
            ButtonKind.Primary ->
                Button(
                    params.onClick,
                    modifier,
                    enabled,
                    colors =
                        if (danger) ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError)
                        else ButtonDefaults.buttonColors(),
                    content = content,
                )
            ButtonKind.Tonal -> FilledTonalButton(params.onClick, modifier, enabled, content = content)
            ButtonKind.Secondary ->
                OutlinedButton(
                    params.onClick,
                    modifier,
                    enabled,
                    colors =
                        if (danger) ButtonDefaults.outlinedButtonColors(contentColor = error) else ButtonDefaults.outlinedButtonColors(),
                    content = content,
                )
            ButtonKind.Text ->
                TextButton(
                    params.onClick,
                    modifier,
                    enabled,
                    colors = if (danger) ButtonDefaults.textButtonColors(contentColor = error) else ButtonDefaults.textButtonColors(),
                    content = content,
                )
        }
    }

    @Composable
    fun amount(params: AmountParams, modifier: Modifier) {
        Text(
            params.text,
            modifier,
            color = MaterialTheme.walletColors.amount,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun topBar(params: TopBarParams, modifier: Modifier) {
        // The title in the bar's own style, also where it is only measured.
        val title = @Composable { ProvideTextStyle(MaterialTheme.typography.titleLarge) { params.title() } }
        SubcomposeLayout { constraints ->
            val below =
                constraints.hasBoundedWidth &&
                    run {
                        val any = Constraints.Infinity
                        fun probe(slot: TopBarSlot, content: @Composable () -> Unit) =
                            subcompose(slot) { Box(Modifier.clearAndSetSemantics {}) { content() } }
                        val beside =
                            probe(TopBarSlot.Navigation, params.navigationIcon).maxOfOrNull { it.maxIntrinsicWidth(any) }.orZero() +
                                probe(TopBarSlot.Actions) { Row { params.actions(this) } }
                                    .maxOfOrNull { it.maxIntrinsicWidth(any) }
                                    .orZero()
                        val word = probe(TopBarSlot.Title, title).maxOfOrNull { it.minIntrinsicWidth(any) }.orZero()
                        // Material's insets: 4 dp at each end, 16 dp before the title without a back button, 24 dp on its sides.
                        constraints.maxWidth - beside - 32.dp.roundToPx() < word
                    }
            val bar =
                subcompose(if (below) TopBarSlot.Below else TopBarSlot.Beside) {
                        if (below) {
                            Column(modifier.background(TopAppBarDefaults.topAppBarColors().containerColor)) {
                                TopAppBar(title = {}, navigationIcon = params.navigationIcon, actions = params.actions)
                                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { title() }
                            }
                        } else
                            TopAppBar(
                                title = params.title,
                                modifier = modifier,
                                navigationIcon = params.navigationIcon,
                                actions = params.actions,
                            )
                    }
                    .single()
                    .measure(constraints)
            layout(bar.width, bar.height) { bar.place(0, 0) }
        }
    }

    @Composable
    fun textField(params: TextFieldParams, modifier: Modifier) {
        if (params.focusSelected) return focusSelectedTextField(params, modifier)
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

    /** [textField] with [TextFieldParams.focusSelected]: it keeps the selection itself, which a plain text value can't carry. */
    @Composable
    private fun focusSelectedTextField(params: TextFieldParams, modifier: Modifier) {
        var field by remember { mutableStateOf(TextFieldValue(params.value, TextRange(0, params.value.length))) }
        if (field.text != params.value) field = field.copy(text = params.value)
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != params.value) params.onValueChange(it.text)
            },
            modifier = modifier.focusRequester(focus),
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

    /**
     * A Material list item, whose right-hand side ([ListRowParams.trailing]) goes below the text when beside it the text's longest word
     * wouldn't fit (a large font, a long action): the text is then never split mid-word.
     */
    @Composable
    fun listRow(params: ListRowParams, modifier: Modifier) {
        val trailing = params.trailing
        if (trailing == null) {
            listItem(params, trailing = null, modifier)
            return
        }
        // The caller's modifier (click, selection, actions) goes on the item itself, whose text it describes.
        SubcomposeLayout { constraints ->
            val below =
                constraints.hasBoundedWidth &&
                    run {
                        val any = Constraints.Infinity
                        // Only measured, never drawn: kept out of what a screen reader (or a test) finds.
                        fun probe(slot: ListRowSlot, content: @Composable () -> Unit) =
                            subcompose(slot) { Box(Modifier.clearAndSetSemantics {}) { content() } }
                        val beside = probe(ListRowSlot.Trailing, trailing).maxOfOrNull { it.maxIntrinsicWidth(any) } ?: 0
                        val leading = params.leading?.let { l -> probe(ListRowSlot.Leading, l).maxOfOrNull { it.maxIntrinsicWidth(any) } }
                        val word =
                            probe(ListRowSlot.Text) {
                                    Column {
                                        params.overline?.invoke()
                                        params.headline()
                                        params.supporting?.invoke()
                                    }
                                }
                                .maxOfOrNull { it.minIntrinsicWidth(any) } ?: 0
                        // Material's paddings: 16 dp at each end, and between the leading part, the text and the right-hand side.
                        val gap = 16.dp.roundToPx()
                        val room = constraints.maxWidth - 2 * gap - (leading?.let { it + gap } ?: 0) - beside - gap
                        room < word
                    }
            val row =
                subcompose(if (below) ListRowSlot.Below else ListRowSlot.Beside) {
                        if (below) {
                            val supporting =
                                @Composable {
                                    Column {
                                        params.supporting?.invoke()
                                        trailing()
                                    }
                                }
                            listItem(ListRowParams(params.headline, params.overline, supporting, params.leading), trailing = null, modifier)
                        } else listItem(params, trailing, modifier)
                    }
                    .single()
                    .measure(constraints)
            layout(row.width, row.height) { row.place(0, 0) }
        }
    }

    @Composable
    private fun listItem(params: ListRowParams, trailing: (@Composable () -> Unit)?, modifier: Modifier) {
        ListItem(
            headlineContent = params.headline,
            modifier = modifier,
            overlineContent = params.overline,
            supportingContent = params.supporting,
            leadingContent = params.leading,
            trailingContent = trailing,
        )
    }

    @Composable
    fun card(params: CardParams, modifier: Modifier) {
        val colors =
            when (params.tone) {
                Tone.Neutral -> CardDefaults.cardColors()
                Tone.Positive -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                Tone.Warning ->
                    MaterialTheme.walletColors.let {
                        CardDefaults.cardColors(containerColor = it.warningContainer, contentColor = it.onWarningContainer)
                    }
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

    @Composable
    fun snackbar(params: SnackbarParams, modifier: Modifier) {
        Snackbar(
            modifier = modifier.padding(12.dp),
            action = params.actionLabel?.let { label -> { TextButton(onClick = params.onAction) { Text(label) } } },
        ) {
            Text(params.message)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun sheet(params: SheetParams) {
        ModalBottomSheet(
            onDismissRequest = params.onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = params.openFully),
            content = params.content,
        )
    }

    @Composable
    fun toggle(params: ToggleParams, modifier: Modifier) {
        when (params.kind) {
            ToggleKind.Switch -> Switch(params.checked, params.onCheckedChange, modifier, enabled = params.enabled)
            ToggleKind.Checkbox -> Checkbox(params.checked, params.onCheckedChange, modifier, enabled = params.enabled)
            ToggleKind.Radio ->
                RadioButton(params.checked, params.onCheckedChange?.let { { it(true) } }, modifier, enabled = params.enabled)
        }
    }

    @Composable
    fun chip(params: ChipParams, modifier: Modifier) {
        when (params.kind) {
            ChipKind.Filter ->
                FilterChip(
                    params.selected,
                    params.onClick,
                    params.label,
                    modifier,
                    params.enabled,
                    leadingIcon = params.leadingIcon,
                    trailingIcon = params.trailingIcon,
                )
            ChipKind.Assist ->
                AssistChip(
                    params.onClick,
                    params.label,
                    modifier,
                    params.enabled,
                    leadingIcon = params.leadingIcon,
                    trailingIcon = params.trailingIcon,
                )
            ChipKind.Suggestion -> SuggestionChip(params.onClick, params.label, modifier, params.enabled, icon = params.leadingIcon)
        }
    }

    @Composable
    fun progress(params: ProgressParams, modifier: Modifier) {
        val progress = params.progress
        when (params.kind) {
            ProgressKind.Linear -> if (progress != null) LinearProgressIndicator(progress, modifier) else LinearProgressIndicator(modifier)
            ProgressKind.Circular ->
                if (progress != null) CircularProgressIndicator(progress, modifier) else CircularProgressIndicator(modifier)
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
        // Clipped before the app's modifier, so the tap's ripple keeps the rounded corners.
        Box(
            Modifier.size(params.size)
                .clip(MaterialTheme.shapes.medium)
                .then(modifier)
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
            modifier
                .background(MaterialTheme.walletColors.warningContainer, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 2.dp),
            color = MaterialTheme.walletColors.onWarningContainer,
            style = MaterialTheme.typography.labelMedium,
        )
    }

    /**
     * [NoteFaceSize.Row]: a list row with the amount, the status beside its mark, the age, and a checkbox while selecting.
     * [NoteFaceSize.Large]: the amount as on the wallet tab, and the QR code below it. Drawn through the brand's own [ListRow], [Toggle],
     * [ListIcon] and [Amount], so restyling those restyles notes too.
     */
    @Composable
    fun noteFace(params: NoteFaceParams, modifier: Modifier) {
        val components = LocalBrandDesign.current.components
        when (params.size) {
            NoteFaceSize.Row ->
                components.ListRow(
                    ListRowParams(
                        headline = { Text(params.amount, style = MaterialTheme.typography.bodyLarge) },
                        supporting =
                            // A handed-out note has no mint header above it, so its row names the mint, below what is unusual about it.
                            params.mint
                                ?.takeIf { params.place == NoteFacePlace.Shared }
                                .let { mint ->
                                    if (params.statusText == null && mint == null) null
                                    else {
                                        {
                                            Column {
                                                params.statusText?.let { text ->
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    ) {
                                                        params.statusIcon?.let {
                                                            components.ListIcon(ListIconParams(it, size = ListIconSize.Inline), Modifier)
                                                        }
                                                        Text(
                                                            text,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                                mint?.let { Text(it) }
                                            }
                                        }
                                    }
                                },
                        leading = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                params.selected?.let { selected ->
                                    components.Toggle(
                                        ToggleParams(ToggleKind.Checkbox, selected, onCheckedChange = null, enabled = params.enabled),
                                        Modifier,
                                    )
                                }
                                Bill(
                                    params.billColors(),
                                    Modifier.size(width = 48.dp, height = 26.dp),
                                    corner = 4.dp,
                                    frame = 3.dp,
                                    certified = params.certified,
                                )
                            }
                        },
                        trailing = params.age?.let { age -> { Text(age, style = MaterialTheme.typography.bodySmall) } },
                    ),
                    modifier,
                )
            // Without a QR code, the note is a bill of its own; with one, the amount as on the wallet tab above the code.
            NoteFaceSize.Large ->
                if (params.qrCode == null)
                    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val colors = params.billColors()
                        Bill(
                            colors,
                            Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(2.04f),
                            corner = 14.dp,
                            frame = 10.dp,
                            certified = params.certified,
                            elevation = 4.dp,
                        ) {
                            BillAmount(
                                params.amount,
                                MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                                colors,
                                Modifier.align(Alignment.TopStart)
                                    .fillMaxWidth(billTextWidth(params.certified))
                                    .padding(start = 24.dp, top = 20.dp, end = 8.dp),
                            )
                            params.mint?.let {
                                Text(
                                    it,
                                    Modifier.align(Alignment.BottomStart)
                                        .fillMaxWidth(billTextWidth(params.certified))
                                        .padding(start = 24.dp, bottom = 20.dp, end = 8.dp),
                                    color = colors.ink,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                else
                    Column(
                        modifier,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.walletSpacing.gap),
                    ) {
                        components.Amount(AmountParams(params.amount), Modifier.fillMaxWidth())
                        params.qrCode.invoke()
                    }
        }
    }

    /**
     * A card: the mint's name, labels and total, then its bills fanned out, the biggest in front with its amount, each drawn to one scale
     * across the mints ([NotePileParams.largestMsat]), and the range below. Always the same height, so swiping between mints keeps the page
     * still.
     */
    @Composable
    fun notePile(params: NotePileParams, modifier: Modifier) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier
                .clip(shape)
                .clickable(onClickLabel = params.onClickLabel, onClick = params.onClick)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (params.trusted && params.mint != null)
                            Icon(
                                params.trustedIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        Text(
                            params.mint ?: params.labels,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (params.mint != null)
                        Text(
                            params.labels,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                }
                Text(params.total, style = MaterialTheme.typography.titleMedium)
                Icon(params.openIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BillFan(params, Modifier.fillMaxWidth().padding(top = 8.dp).height(160.dp))
            // Always a line, empty or not: every mint's card is as high as the others.
            Text(
                params.range.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                minLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /** A row with the mint's name, labels and total; without a [NoteGroupParams.mint], the total and labels only. */
    @Composable
    fun noteGroupHeader(params: NoteGroupParams, modifier: Modifier) {
        val mint = params.mint
        LocalBrandDesign.current.components.ListRow(
            ListRowParams(
                headline = { Text(mint ?: params.total, style = MaterialTheme.typography.titleMedium) },
                supporting = params.labels?.let { labels -> { Text(labels) } },
                leading =
                    if (params.trusted && mint != null)
                        ({ Icon(params.trustedIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) })
                    else null,
                trailing = mint?.let { { Text(params.total, style = MaterialTheme.typography.titleMedium) } },
            ),
            modifier,
        )
    }

    /** The icon in the secondary text colour, or the tone's colour; a check badge on the wallet in use. */
    @Composable
    fun listIcon(params: ListIconParams, modifier: Modifier) {
        val tint =
            when (params.tone) {
                Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                Tone.Positive -> MaterialTheme.colorScheme.primary
                Tone.Warning -> MaterialTheme.walletColors.warning
                Tone.Danger -> MaterialTheme.colorScheme.error
            }
        val size =
            when (params.size) {
                ListIconSize.Row -> 24.dp
                ListIconSize.Inline -> 16.dp
            }
        val badge = params.activeBadge ?: return Icon(params.icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
        Box(modifier.size(size)) {
            Icon(params.icon, contentDescription = null, modifier = Modifier.size(size), tint = tint)
            ActiveBadge(badge, size * 0.6f)
        }
    }

    /** The check of the account or wallet in use, at the bottom end of its mark: half outside, clear of what the mark shows. */
    @Composable
    private fun BoxScope.ActiveBadge(badge: Painter, size: Dp) {
        Box(
            Modifier.align(Alignment.BottomEnd)
                .offset(size * 0.3f, size * 0.3f)
                .size(size)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.85f))
        }
    }

    /** The letter on a primary, tertiary or secondary container by [AccountMarkParams.index]; a check badge when active. */
    @Composable
    fun accountMark(params: AccountMarkParams, modifier: Modifier) {
        // Tertiary second: secondary is often a shade of primary, and the first two accounts should differ most.
        val (container, content) =
            when (params.index.mod(3)) {
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
            if (params.active) ActiveBadge(params.activeBadge, params.size * 0.45f)
        }
    }

    /** The type's icon on a circle tinted by where the money went; a small badge when the wallet did it by itself. */
    @Composable
    fun activityIcon(params: ActivityIconParams, modifier: Modifier) {
        val maintenance = params.type == ActivityType.Split || params.type == ActivityType.Combined || params.type == ActivityType.Renewed
        val tint =
            when {
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

/** The parts of a [DefaultComponents.listRow] composed apart: measured to decide where the right-hand side goes, then the row itself. */
private enum class ListRowSlot {
    Trailing,
    Leading,
    Text,
    Beside,
    Below,
}

/** The parts of a [DefaultComponents.topBar] composed apart: measured to decide where the title goes, then the bar itself. */
private enum class TopBarSlot {
    Navigation,
    Actions,
    Title,
    Beside,
    Below,
}

private fun Int?.orZero() = this ?: 0

/** An amount on a bill: one line, smaller where a large font or a long amount wouldn't fit. */
@Composable
private fun BillAmount(text: String, style: TextStyle, colors: NoteColors, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier,
        style = style.copy(color = colors.ink),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
    )
}

/** A bill's colours: its mint's, greyed out when the note is out of the balance. */
@Composable
private fun NoteFaceParams.billColors(): NoteColors =
    if (!status.inBalance || place == NoteFacePlace.Shared) greyedBill() else colors ?: MaterialTheme.walletColors.note(0)

@Composable private fun greyedBill() = NoteColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Up to seven bills, the biggest in front in the mint's colours and the others behind it in a lighter tint, each a little to the right and
 * slightly turned. Without a note in the balance, one blank bill, greyed out.
 */
@Composable
private fun BillFan(params: NotePileParams, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val largest = minOf(maxWidth * 0.75f, 240.dp)
        val smallest = largest * 0.6f
        val shown = params.billsMsat.take(7)
        if (shown.isEmpty()) {
            Bill(greyedBill(), Modifier.align(Alignment.CenterStart).size(smallest, smallest * 0.52f), corner = 8.dp, frame = 6.dp)
            return@BoxWithConstraints
        }
        fun width(msat: Long) = smallest + (largest - smallest) * sqrt(msat.toFloat() / params.largestMsat.coerceAtLeast(1))
        val front = width(shown.first())
        val step = if (shown.size > 1) minOf(56.dp, (maxWidth - front) / (shown.size - 1)) else 0.dp
        val tint = NoteColors(lerp(params.colors.paper, MaterialTheme.colorScheme.surface, 0.7f), MaterialTheme.colorScheme.onSurface)
        for (r in shown.indices.reversed()) {
            val w = width(shown[r])
            val h = w * 0.52f
            Bill(
                if (r == 0) params.colors else tint,
                Modifier.offset(x = front - w + step * r, y = (maxHeight - h) / 2)
                    .size(w, h)
                    .rotate(
                        when {
                            r == 0 -> 0f
                            r % 2 == 1 -> 2.5f
                            else -> -2f
                        }
                    ),
                corner = 8.dp,
                frame = 6.dp,
                certified = params.billsCertified.getOrElse(r) { false },
                elevation = 2.dp,
            ) {
                // Decorative: the card is read out by its mint, labels, total and range, not by its biggest note.
                if (r == 0)
                    BillAmount(
                        params.bills.first(),
                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        params.colors,
                        Modifier.align(Alignment.TopStart)
                            .fillMaxWidth(billTextWidth(params.billsCertified.firstOrNull() ?: false))
                            .padding(start = 14.dp, top = 12.dp, end = 6.dp)
                            .clearAndSetSemantics {},
                    )
            }
        }
    }
}

/** How much of a bill's width its texts may take: a certified bill keeps the rest for its foil stripe. */
private fun billTextWidth(certified: Boolean) = if (certified) 0.76f else 1f

/**
 * The default bill: the mint's colour with a dashed frame [frame] inside its edge; [content] holds its texts. A [certified] note's bill
 * carries the marks of a banknote: a foil stripe with fine engraved lines and a medal near its right edge, and soft waves under the amount;
 * a small bill only the stripe. Decorative: the texts it holds are what is read.
 */
@Composable
private fun Bill(
    colors: NoteColors,
    modifier: Modifier,
    corner: Dp,
    frame: Dp,
    certified: Boolean = false,
    elevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(corner)
    val line = colors.ink.copy(alpha = 0.35f)
    Box(
        modifier
            .shadow(elevation, shape)
            .clip(shape)
            .background(colors.paper)
            .border(1.dp, Color.Black.copy(alpha = 0.12f), shape)
            // Built once per size: a list scrolls many bills.
            .drawWithCache {
                val marks = if (certified) certificateMarks(colors, detailed = size.width >= 100.dp.toPx()) else null
                val inset = frame.toPx()
                val dashes = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                val radius = CornerRadius((corner - frame / 2).coerceAtLeast(2.dp).toPx())
                onDrawBehind {
                    marks?.invoke(this)
                    drawRoundRect(
                        line,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = radius,
                        style = dashes,
                    )
                }
            },
        content = content,
    )
}

/**
 * A certified bill's marks, laid out on a bill 204 units wide and 100 high: the foil stripe from 160 to 184 with engraved lines (three on a
 * small bill, six else), and when [detailed] the medal low on it and three soft waves under the amount. Built once, drawn by what it
 * returns.
 */
private fun CacheDrawScope.certificateMarks(colors: NoteColors, detailed: Boolean): DrawScope.() -> Unit {
    val ux = size.width / 204
    val uy = size.height / 100
    val u = minOf(ux, uy)
    val ink = colors.ink
    val hairline = maxOf(0.45f * u, 0.5.dp.toPx())
    val left = 160 * ux
    val right = 184 * ux
    val foil =
        Brush.linearGradient(
            0f to ink.copy(alpha = 0.08f),
            0.45f to ink.copy(alpha = 0.34f),
            0.55f to ink.copy(alpha = 0.16f),
            1f to ink.copy(alpha = 0.06f),
            start = Offset(left, 0f),
            end = Offset(left + 0.35f * (right - left), size.height),
        )
    val lines = if (detailed) 6 else 3
    val engraving =
        Path().apply {
            for (k in 0 until lines) {
                val x0 = left + (k + 0.5f) * (right - left) / lines
                for (y in 0..100 step 2) {
                    val x = x0 + 1.5f * ux * sin((y + k * 4) / 18f * 2 * PI.toFloat())
                    if (y == 0) moveTo(x, 0f) else lineTo(x, y * uy)
                }
            }
        }
    // The medal and waves only where they are drawn: a small bill builds none of their paths.
    val details: (DrawScope.() -> Unit)? =
        if (!detailed) null
        else {
            val waves =
                Path().apply {
                    for (k in 0 until 3) {
                        for (x in 0..160 step 2) {
                            val y = (60 + k * 6) * uy + 2.5f * u * sin((x + k * 7) / 40f * 2 * PI.toFloat())
                            if (x == 0) moveTo(0f, y) else lineTo(x * ux, y)
                        }
                    }
                }
            val center = Offset(172 * ux, 76 * uy)
            val teeth = star(center, 12.5f * u, 10.8f * u, 28)
            val rosette = rose(center, 5f * u, 1.6f * u, 10, 0f)
            val inner = rose(center, 3.2f * u, 1f * u, 10, PI.toFloat() / 10)
            val medal: DrawScope.() -> Unit = {
                drawPath(waves, ink.copy(alpha = 0.18f), style = Stroke(hairline))
                drawPath(teeth, colors.paper)
                drawPath(teeth, ink.copy(alpha = 0.24f))
                drawCircle(colors.paper, 8.4f * u, center)
                drawCircle(ink.copy(alpha = 0.6f), 8.4f * u, center, style = Stroke(maxOf(0.7f * u, hairline)))
                drawPath(rosette, ink.copy(alpha = 0.55f), style = Stroke(hairline))
                drawPath(inner, ink.copy(alpha = 0.35f), style = Stroke(hairline * 0.9f))
            }
            medal
        }
    return {
        drawRect(foil, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
        drawLine(ink.copy(alpha = 0.3f), Offset(left, 0f), Offset(left, size.height), hairline)
        drawLine(ink.copy(alpha = 0.3f), Offset(right, 0f), Offset(right, size.height), hairline)
        drawPath(engraving, ink.copy(alpha = 0.3f), style = Stroke(hairline))
        details?.invoke(this)
    }
}

/** A medal's toothed edge: [points] teeth between radius [outer] and [inner]. */
private fun star(center: Offset, outer: Float, inner: Float, points: Int) =
    Path().apply {
        for (i in 0 until 2 * points) {
            val r = if (i % 2 == 0) outer else inner
            val t = i / (2f * points) * 2 * PI.toFloat()
            val p = Offset(center.x + r * cos(t), center.y + r * sin(t))
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }

/** A rosette: a circle of radius [radius] waving [amplitude] in and out [petals] times. */
private fun rose(center: Offset, radius: Float, amplitude: Float, petals: Int, phase: Float) =
    Path().apply {
        for (i in 0..360) {
            val t = i / 360f * 2 * PI.toFloat()
            val r = radius + amplitude * cos(petals * t + phase)
            val p = Offset(center.x + r * cos(t), center.y + r * sin(t))
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
