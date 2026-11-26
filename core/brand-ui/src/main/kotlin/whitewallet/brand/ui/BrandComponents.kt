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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
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

    /**
     * A mint's mark beside its name in lists and pickers: its seal in small ([MintPattern]), so the mint is recognised at a glance, as on
     * its bills. Always decorative: the name beside it is what is read.
     */
    @Composable fun MintMark(params: MintMarkParams, modifier: Modifier) = DefaultComponents.mintMark(params, modifier)

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

/** A mint's mark ([BrandComponents.MintMark]), beside its name in lists and pickers of mints; decorative. */
class MintMarkParams(
    /** How the mint's bills are drawn: the mark is its seal ([MintPattern.sealShape], [MintPattern.sealMotif]) ringed by its border. */
    val pattern: MintPattern,
    /** Its bills' colours: the home mint's or the other mints' ([WalletColors.note]). */
    val colors: NoteColors,
    /** The mint is the active account's home (the default reads it from [colors] alone). */
    val home: Boolean,
    /** The mark's diameter. */
    val size: Dp,
)

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
    /**
     * The colours of its mint's bills ([WalletColors.homeMintNote] or [WalletColors.otherMintsNote]); a note out of the balance is drawn
     * greyed out ([NoteStatus.inBalance]).
     */
    val colors: NoteColors? = null,
    /**
     * Its mint's certificate for its amount verified (a certificate that doesn't verify is never kept). The default draws a certified bill
     * with a foil stripe and its engraving, and another without; the words for one that isn't ([statusText]) stay the app's.
     */
    val certified: Boolean,
    /** How its mint's bills are drawn, also where mints aren't named ([mint] null): the default's border, waves and seal. */
    val pattern: MintPattern,
)

/** Whether a note of this status counts in the balance: else a bill is drawn greyed out. */
val NoteStatus.inBalance: Boolean
    get() = this == NoteStatus.Ready || this == NoteStatus.Uncertified

class NotePileParams(
    /** The mint's host; null where mints aren't named (brand `mints.visible=false`). */
    val mint: String?,
    /**
     * Home mint, trusted and how many notes, in words ("Home mint · Trusted · 84 notes"); with none in the balance, why first, then home
     * and trusted ("2 notes unknown to the mint · 1 more · Home mint").
     */
    val labels: String,
    /**
     * How many lines [labels] get, the same on every mint's card so they stay as high: 2 while any mint has none in the balance (its why is
     * long), else 1.
     */
    val labelLines: Int,
    /**
     * What its notes count in the balance, formatted; with none in the balance, what is held up there (waiting for an answer, or unknown to
     * the mint: [outsideBalance] says so); null when nothing is, as while its only notes are being paid.
     */
    val total: String?,
    val totalMsat: Long,
    /** From its smallest note to its biggest, in words ("From 1,000 sats to 21,000 sats"); null with one note or all alike. */
    val range: String?,
    /**
     * "Not in your balance", when [total] isn't counted in it: the default draws the total muted and says this where the range goes; null
     * when the total is the balance's.
     */
    val outsideBalance: String?,
    /**
     * The notes on top of the pile (a few at most), formatted and in msat: those in the balance first, the biggest first, then those out of
     * it ([billsStatus]), the biggest first.
     */
    val bills: List<String>,
    val billsMsat: List<Long>,
    /** Whether each of [bills] is certified ([NoteFaceParams.certified]), in the same order; a bill without one is drawn plain. */
    val billsCertified: List<Boolean>,
    /** How each of [bills] stands, in the same order: a bill out of the balance ([NoteStatus.inBalance]) is drawn greyed out. */
    val billsStatus: List<NoteStatus>,
    /** The mark of the front bill's status when it is out of the balance ([NoteFaceParams.statusIcon]); null when it is in it. */
    val frontIcon: Painter?,
    /** The biggest note on any of the user's mints: bills are drawn to one scale across the mints. */
    val largestMsat: Long,
    val colors: NoteColors,
    val home: Boolean,
    val trusted: Boolean,
    /** The mark that says a tap opens the mint's notes (decorative). */
    val openIcon: Painter,
    /** Opens the mint's notes: make the whole pile clickable with it, inside the pile's own shape, so the ripple keeps its corners. */
    val onClick: () -> Unit,
    /** What a tap does, for screen readers ("Open this mint's notes"). */
    val onClickLabel: String,
    /**
     * How the mint's bills are drawn, also where mints aren't named ([mint] null); the default shows its [MintMark] beside a named mint.
     */
    val pattern: MintPattern,
)

class NoteGroupParams(
    /** The mint's host; null where mints aren't named. */
    val mint: String?,
    /**
     * What the group's notes count in the balance, formatted; with none in the balance, what is held up there ([outsideBalance] says so),
     * as on the pile; null when nothing is.
     */
    val total: String?,
    val totalMsat: Long,
    val home: Boolean,
    val trusted: Boolean,
    /**
     * [home], [trusted] and how many notes, in words ("Home mint · Trusted · 84 notes"), or with none in the balance why, as on the pile;
     * null when there's nothing to say.
     */
    val labels: String?,
    /** "Not in your balance", when [total] isn't counted in it: the default draws the total muted and says this below the labels. */
    val outsideBalance: String?,
    /** How the mint's bills are drawn; the default shows its [MintMark] beside a named mint. */
    val pattern: MintPattern,
    /** Its bills' colours, for its mark. */
    val colors: NoteColors,
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
        // A text button in the text colour of a coloured card it is on (see card()), else in the theme's primary.
        val textColor = LocalCardText.current ?: MaterialTheme.colorScheme.primary
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
                    colors = ButtonDefaults.textButtonColors(contentColor = if (danger) error else textColor),
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
        // On a coloured card, its text colour stands in for the colours made for the page, which can be the card's own: onSurfaceVariant
        // (an outline button's label too) and outline here, and a text button's primary (see button()).
        val scheme = MaterialTheme.colorScheme
        val text = colors.contentColor.takeIf { params.tone != Tone.Neutral }
        val onCard = text?.let { scheme.copy(onSurfaceVariant = it, outline = it) } ?: scheme
        Card(modifier, colors = colors) {
            CompositionLocalProvider(LocalCardText provides text) { MaterialTheme(colorScheme = onCard) { params.content(this) } }
        }
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
            // The button in the snackbar's own action colour, as Material's: the theme's primary is made for the page, not the snackbar.
            action =
                params.actionLabel?.let { label ->
                    {
                        TextButton(
                            onClick = params.onAction,
                            colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                        ) {
                            Text(label)
                        }
                    }
                },
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
            // The selected tab's name in the bar's text colour, as its others: Material's accent (secondary) needn't stand out from the
            // bar.
            val colors = NavigationBarItemDefaults.colors(selectedTextColor = MaterialTheme.colorScheme.onSurface)
            params.items.forEach { item -> NavigationBarItem(item.selected, item.onClick, item.icon, label = item.label, colors = colors) }
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
                                    params.pattern,
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
                            params.pattern,
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
                                    .fillMaxWidth(BILL_TEXT_WIDTH)
                                    .padding(start = 24.dp, top = 20.dp, end = 8.dp),
                            )
                            params.mint?.let {
                                Text(
                                    it,
                                    Modifier.align(Alignment.BottomStart)
                                        .fillMaxWidth(BILL_TEXT_WIDTH)
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
     * A card: the mint's name and total, its labels below at the card's width, then its bills fanned out, the biggest in front with its
     * amount, each drawn to one scale across the mints ([NotePileParams.largestMsat]), and the range below. Bills out of the balance are
     * greyed out, behind the others; with none in the balance the total is muted and "Not in your balance" takes the range's line. Always
     * the same height, so swiping between mints keeps the page still.
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
                // Its mark, as beside its name in lists; "Trusted" is in its labels.
                if (params.mint != null)
                    LocalBrandDesign.current.components.MintMark(
                        MintMarkParams(params.pattern, params.colors, params.home, 24.dp),
                        Modifier,
                    )
                // Without a name, the labels head the card, in as many lines.
                val lines = if (params.mint == null) params.labelLines else 1
                Text(
                    params.mint ?: params.labels,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = lines,
                    minLines = lines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                params.total?.let { total ->
                    Text(
                        total,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (params.outsideBalance != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    )
                }
                Icon(params.openIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Below, the card's whole width: why none counts is a long line.
            if (params.mint != null)
                Text(
                    params.labels,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = params.labelLines,
                    minLines = params.labelLines,
                    overflow = TextOverflow.Ellipsis,
                )
            BillFan(params, Modifier.fillMaxWidth().padding(top = 8.dp).height(160.dp))
            // Always a line, empty or not: every mint's card is as high as the others.
            Text(
                params.outsideBalance ?: params.range.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                minLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * A row with the mint's name, labels and total; without a [NoteGroupParams.mint], the total (or without one the labels) and labels
     * only. A total out of the balance is muted, and said so below the labels.
     */
    @Composable
    fun noteGroupHeader(params: NoteGroupParams, modifier: Modifier) {
        val mint = params.mint
        val totalColor = if (params.outsideBalance != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
        val total: (@Composable () -> Unit)? =
            params.total?.let { total -> { Text(total, style = MaterialTheme.typography.titleMedium, color = totalColor) } }
        // Without a name, the total heads the row; without a total either, the labels do.
        val headline = mint ?: params.total ?: params.labels.orEmpty()
        val supporting = listOfNotNull(params.labels.takeIf { headline != it }, params.outsideBalance)
        LocalBrandDesign.current.components.ListRow(
            ListRowParams(
                headline =
                    if (mint == null && total != null) total
                    else {
                        { Text(headline, style = MaterialTheme.typography.titleMedium) }
                    },
                supporting = supporting.takeIf { it.isNotEmpty() }?.let { lines -> { Column { lines.forEach { Text(it) } } } },
                // Its mark, as beside its name in lists; "Trusted" is in its labels.
                leading =
                    mint?.let {
                        {
                            LocalBrandDesign.current.components.MintMark(
                                MintMarkParams(params.pattern, params.colors, params.home, 32.dp),
                                Modifier,
                            )
                        }
                    },
                trailing = total.takeIf { mint != null },
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

    /**
     * The mint's seal small: a disc in its bills' colour, ringed by its border style, the seal's outline and motif inside. Decorative: the
     * mint's name sits beside it.
     */
    @Composable
    fun mintMark(params: MintMarkParams, modifier: Modifier) {
        val colors = params.colors
        val pattern = params.pattern
        Box(
            modifier
                .size(params.size)
                .clearAndSetSemantics {}
                .drawWithCache {
                    val r = size.minDimension / 2
                    val center = size.center
                    val line = 1.dp.toPx()
                    val ring = r - 2.dp.toPx()
                    val bounds = Rect(center, ring)
                    val step = maxOf(ring * 0.3f, 3.dp.toPx())
                    val border: DrawScope.(Color) -> Unit =
                        when (pattern.border) {
                            BillBorder.Zigzag -> {
                                val teeth = (2 * PI.toFloat() * ring / step).toInt().coerceAtLeast(8)
                                val zigzag = star(center, ring, ring - step * 0.4f, teeth, 0f)
                                ({ color -> drawPath(zigzag, color, style = Stroke(line, join = StrokeJoin.Round)) })
                            }
                            BillBorder.Double,
                            BillBorder.Dashes,
                            BillBorder.Dots -> borderLine(pattern.border, bounds, ring, step, line)
                        }
                    // Inside the ring, whatever its style and the outline's points. The mark always waves the same: its size leaves
                    // no room to tell depths apart.
                    val depth = if (pattern.border == BillBorder.Zigzag) step * 0.4f else borderDepth(pattern.border, line)
                    val inner = ring - depth - 1.5f * line
                    val seal = Seal(pattern, center, minOf(0.6f * r, inner / reach(pattern.sealShape)), depth = 0.3f)
                    val stroke = Stroke(line)
                    onDrawBehind {
                        drawCircle(colors.paper)
                        drawCircle(colors.ink.copy(alpha = 0.12f), style = Stroke(line))
                        border(colors.ink.copy(alpha = 0.55f))
                        drawPath(seal.outline, colors.ink.copy(alpha = 0.08f))
                        drawPath(seal.outline, colors.ink.copy(alpha = 0.75f), style = stroke)
                        seal.motif.forEach { drawPath(it, colors.ink.copy(alpha = 0.7f), style = stroke) }
                    }
                }
        )
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
    if (!status.inBalance || place == NoteFacePlace.Shared) greyedBill() else colors ?: MaterialTheme.walletColors.homeMintNote

@Composable private fun greyedBill() = NoteColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Up to seven bills, the biggest in front in the mint's colours and the others behind it in a lighter tint, each a little to the right and
 * slightly turned; those out of the balance greyed out, the front one with its status's mark. Without a bill, one blank bill, greyed out.
 */
@Composable
private fun BillFan(params: NotePileParams, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val largest = minOf(maxWidth * 0.75f, 240.dp)
        val smallest = largest * 0.6f
        val shown = params.billsMsat.take(7)
        if (shown.isEmpty()) {
            Bill(
                greyedBill(),
                params.pattern,
                Modifier.align(Alignment.CenterStart).size(smallest, smallest * 0.52f),
                corner = 8.dp,
                frame = 6.dp,
            )
            return@BoxWithConstraints
        }
        fun width(msat: Long) = smallest + (largest - smallest) * sqrt(msat.toFloat() / params.largestMsat.coerceAtLeast(1))
        val front = width(shown.first())
        val step = if (shown.size > 1) minOf(56.dp, (maxWidth - front) / (shown.size - 1)) else 0.dp
        val tint = NoteColors(lerp(params.colors.paper, MaterialTheme.colorScheme.surface, 0.7f), MaterialTheme.colorScheme.onSurface)
        val greyed = greyedBill()
        val greyedTint = NoteColors(lerp(greyed.paper, MaterialTheme.colorScheme.surface, 0.5f), greyed.ink)
        for (r in shown.indices.reversed()) {
            val w = width(shown[r])
            val h = w * 0.52f
            val inBalance = params.billsStatus.getOrElse(r) { NoteStatus.Ready }.inBalance
            val colors =
                when {
                    !inBalance -> if (r == 0) greyed else greyedTint
                    r == 0 -> params.colors
                    else -> tint
                }
            Bill(
                colors,
                params.pattern,
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
                if (r == 0) {
                    BillAmount(
                        params.bills.first(),
                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        colors,
                        Modifier.align(Alignment.TopStart)
                            .fillMaxWidth(BILL_TEXT_WIDTH)
                            .padding(start = 14.dp, top = 12.dp, end = 6.dp)
                            .clearAndSetSemantics {},
                    )
                    // Decorative too: the labels say why it is out of the balance.
                    params.frontIcon?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = colors.ink,
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 12.dp).size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** How much of a bill's width its texts may take: the rest is its seal's and its foil stripe's. */
private const val BILL_TEXT_WIDTH = 0.76f

/**
 * The default bill: the mint's colour, and the mint's design ([pattern]) laid out on a bill 204 units wide and 100 high: its border just
 * inside the edge ([frame] in), and on a bill wide enough to show them, three waves under the amount and the seal low on the right. A
 * [certified] note's bill carries a foil stripe from 160 to 184 with engraved lines at the mint's angle. [content] holds its texts.
 * Decorative: the texts it holds are what is read.
 */
@Composable
private fun Bill(
    colors: NoteColors,
    pattern: MintPattern,
    modifier: Modifier,
    corner: Dp,
    frame: Dp,
    certified: Boolean = false,
    elevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .shadow(elevation, shape)
            .clip(shape)
            .background(colors.paper)
            .border(1.dp, Color.Black.copy(alpha = 0.12f), shape)
            // Built once per size: a list scrolls many bills.
            .drawWithCache {
                val marks = billMarks(colors, pattern, certified, frame, corner)
                onDrawBehind { marks() }
            },
        content = content,
    )
}

/** The paths of [Bill], built once per size; a small bill builds no waves or seal. */
private fun CacheDrawScope.billMarks(
    colors: NoteColors,
    pattern: MintPattern,
    certified: Boolean,
    frame: Dp,
    corner: Dp,
): DrawScope.() -> Unit {
    val ux = size.width / 204
    val uy = size.height / 100
    val u = minOf(ux, uy)
    val ink = colors.ink
    val detailed = size.width >= 100.dp.toPx()
    val hairline = maxOf(0.45f * u, 0.5.dp.toPx())
    val inset = frame.toPx()
    val borderStep = maxOf(4 * u, 3.dp.toPx())
    val borderWidth = if (detailed) 1.dp.toPx() else 0.75.dp.toPx()
    val border =
        borderLine(
            pattern.border,
            Rect(inset, inset, size.width - inset, size.height - inset),
            (corner - frame / 2).coerceAtLeast(2.dp).toPx(),
            step = borderStep,
            width = borderWidth,
        )
    val left = 160 * ux
    val right = 184 * ux
    val stripe =
        if (!certified) null
        else {
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
            val spacing = (right - left) / lines
            val lean = tan(pattern.engravingAngle * PI.toFloat() / 180) * size.height
            // Past the stripe on both sides, as far as the lines lean: once clipped, they cover it from corner to corner.
            val extra = ceil(abs(lean) / 2 / spacing).toInt() + 1
            val engraving =
                Path().apply {
                    for (k in -extra until lines + extra) {
                        val x = left + (k + 0.5f) * spacing
                        moveTo(x - lean / 2, 0f)
                        lineTo(x + lean / 2, size.height)
                    }
                }
            val draw: DrawScope.() -> Unit = {
                drawRect(foil, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
                clipRect(left, 0f, right, size.height) { drawPath(engraving, ink.copy(alpha = 0.3f), style = Stroke(hairline)) }
                drawLine(ink.copy(alpha = 0.3f), Offset(left, 0f), Offset(left, size.height), hairline)
                drawLine(ink.copy(alpha = 0.3f), Offset(right, 0f), Offset(right, size.height), hairline)
            }
            draw
        }
    // The waves and the seal only where they are drawn: a small bill builds none of their paths.
    val details: (DrawScope.() -> Unit)? =
        if (!detailed) null
        else {
            val waves =
                Path().apply {
                    val phase = pattern.wavePhase * 2 * PI.toFloat()
                    for (k in 0 until 3) {
                        for (x in 0..160 step 2) {
                            val t = 2 * PI.toFloat() * pattern.waveFrequency * x / 160 + phase + k * 0.7f
                            val y = (58 + k * 7) * uy + 2.5f * u * sin(t)
                            if (x == 0) moveTo(0f, y) else lineTo(x * ux, y)
                        }
                    }
                }
            val center = Offset(172 * ux, 74 * uy)
            val inward =
                if (pattern.border == BillBorder.Zigzag) borderStep * 0.45f + borderWidth else borderDepth(pattern.border, borderWidth)
            // Clear of the border on a short bill: the outline's farthest point stays inside it.
            val reachable = minOf(size.height - center.y, size.width - center.x) - inset - inward
            val seal = Seal(pattern, center, minOf(15 * u, (reachable - hairline) / reach(pattern.sealShape)), pattern.depth)
            val sealLine = Stroke(maxOf(0.7f * u, hairline))
            val draw: DrawScope.() -> Unit = {
                drawPath(waves, ink.copy(alpha = 0.2f), style = Stroke(hairline))
                drawPath(seal.outline, colors.paper)
                drawPath(seal.outline, ink.copy(alpha = 0.6f), style = sealLine)
                seal.motif.forEach { drawPath(it, ink.copy(alpha = 0.55f), style = sealLine) }
            }
            draw
        }
    return {
        stripe?.invoke(this)
        border(ink.copy(alpha = 0.4f))
        details?.invoke(this)
    }
}

/**
 * A mint's [border] style along [rect] (corners of [radius]); [step] is the distance between dots or zigzag points, [width] the line's.
 * Returns what draws it in a colour.
 */
private fun CacheDrawScope.borderLine(border: BillBorder, rect: Rect, radius: Float, step: Float, width: Float): DrawScope.(Color) -> Unit {
    val round = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
    return when (border) {
        BillBorder.Double -> {
            val gap = maxOf(width * 2.5f, 2.dp.toPx())
            val inner = Path().apply { addRoundRect(RoundRect(rect.deflate(gap), CornerRadius((radius - gap).coerceAtLeast(1f)))) }
            ({ color ->
                drawPath(round, color, style = Stroke(width))
                drawPath(inner, color.copy(alpha = color.alpha * 0.75f), style = Stroke(width * 0.75f))
            })
        }
        BillBorder.Dots -> {
            val dots = Stroke(width * 1.8f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, step * 1.6f)))
            ({ color -> drawPath(round, color, style = dots) })
        }
        BillBorder.Zigzag -> {
            val zigzag = zigzag(rect, step, amplitude = step * 0.45f)
            ({ color -> drawPath(zigzag, color, style = Stroke(width, join = StrokeJoin.Round)) })
        }
        BillBorder.Dashes -> {
            val dashes = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(step, step * 0.75f)))
            ({ color -> drawPath(round, color, style = dashes) })
        }
    }
}

/**
 * For a brand drawing its own notes: the mint's seal, its outline and motif, centred on [center] with its outline's farthest point [radius]
 * away (the line reaches half [width] further), as the default bills draw it, in lines [width] wide; all in px. Build it in a
 * `drawWithCache`; what it gives back draws it with lines in `ink` over a `fill` (null: none).
 */
fun CacheDrawScope.mintSeal(
    pattern: MintPattern,
    center: Offset,
    radius: Float,
    width: Float,
): DrawScope.(ink: Color, fill: Color?) -> Unit {
    val seal = Seal(pattern, center, radius / reach(pattern.sealShape), pattern.depth)
    val stroke = Stroke(width)
    return { ink, fill ->
        fill?.let { drawPath(seal.outline, it) }
        drawPath(seal.outline, ink, style = stroke)
        seal.motif.forEach { drawPath(it, ink.copy(alpha = ink.alpha * 0.9f), style = stroke) }
    }
}

/** A zigzag around [rect]: points every [step], [amplitude] in and out, each side on its own so the corners stay sharp. */
private fun zigzag(rect: Rect, step: Float, amplitude: Float) =
    Path().apply {
        fun side(from: Offset, to: Offset, inward: Offset) {
            val length = (to - from).getDistance()
            // Even: each side ends on its corner.
            val n = maxOf(2, (length / step).toInt()).let { it + it % 2 }
            for (i in 0..n) {
                val along = from + (to - from) * (i.toFloat() / n)
                val p = along + inward * (if (i % 2 == 0) 0f else amplitude)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        side(rect.topLeft, rect.topRight, Offset(0f, 1f))
        side(rect.topRight, rect.bottomRight, Offset(-1f, 0f))
        side(rect.bottomRight, rect.bottomLeft, Offset(0f, -1f))
        side(rect.bottomLeft, rect.topLeft, Offset(1f, 0f))
    }

/** How far in from its line a [border] of lines [width] wide reaches: past the inner line of a double one, else half its own width. */
private fun CacheDrawScope.borderDepth(border: BillBorder, width: Float): Float =
    if (border == BillBorder.Double) maxOf(width * 2.5f, 2.dp.toPx()) + width else width

/** How far a seal's outline reaches, as a share of its radius ([Seal]). */
private fun reach(shape: SealShape): Float =
    when (shape) {
        SealShape.Star -> 1.2f
        SealShape.Diamond -> 1.15f
        SealShape.Square -> 1.1f
        SealShape.Teeth,
        SealShape.Hexagon -> 1f
    }

/**
 * A mint's seal ([MintPattern.sealShape] around its [MintPattern.sealMotif]) centred on [center], [radius] its outline's size, its rosette
 * waving [depth] of its radius.
 */
private class Seal(pattern: MintPattern, center: Offset, radius: Float, depth: Float) {
    val outline: Path
    val motif: List<Path>

    init {
        val petals = pattern.petals
        // Smooth at any size, without thousands of points on a small mark.
        val segments = (radius * 2).toInt().coerceIn(48, 180)
        outline =
            when (pattern.sealShape) {
                SealShape.Hexagon -> polygon(center, radius, 6, PI.toFloat() / 6)
                SealShape.Diamond -> polygon(center, radius * 1.15f, 4, -PI.toFloat() / 2)
                SealShape.Square -> polygon(center, radius * 1.1f, 4, PI.toFloat() / 4)
                SealShape.Star -> star(center, radius * 1.2f, radius * 0.62f, 5, -PI.toFloat() / 2)
                SealShape.Teeth -> star(center, radius, radius * 0.84f, 14 + petals, 0f)
            }
        // Inside a star's arms there is less room.
        val r = radius * if (pattern.sealShape == SealShape.Star) 0.72f else 1f
        motif =
            when (pattern.sealMotif) {
                SealMotif.Spokes ->
                    listOf(
                        Path().apply {
                            for (i in 0 until petals) {
                                val t = i * 2 * PI.toFloat() / petals
                                moveTo(center.x + 0.16f * r * cos(t), center.y + 0.16f * r * sin(t))
                                lineTo(center.x + 0.6f * r * cos(t), center.y + 0.6f * r * sin(t))
                            }
                        },
                        rose(center, 0.62f * r, 0f, petals, 0f, segments),
                    )
                SealMotif.Rings -> listOf(0.2f, 0.4f, 0.6f).map { rose(center, it * r, 0f, petals, 0f, segments) }
                SealMotif.DoubleRosette ->
                    listOf(
                        rose(center, 0.56f * r, 0.3f * 0.56f * r, petals, 0f, segments),
                        rose(center, 0.3f * r, 0.4f * 0.3f * r, petals, PI.toFloat() / petals, segments),
                    )
                SealMotif.Rosette -> listOf(rose(center, 0.48f * r, depth * 0.48f * r, petals, 0f, segments))
            }
    }
}

/** A regular polygon of [corners] corners on a circle of [radius], the first at angle [rotation]. */
private fun polygon(center: Offset, radius: Float, corners: Int, rotation: Float) =
    Path().apply {
        for (i in 0 until corners) {
            val t = rotation + i * 2 * PI.toFloat() / corners
            if (i == 0) moveTo(center.x + radius * cos(t), center.y + radius * sin(t))
            else lineTo(center.x + radius * cos(t), center.y + radius * sin(t))
        }
        close()
    }

/** A toothed edge or a star: [points] points between radius [outer] and [inner], the first at angle [rotation]. */
private fun star(center: Offset, outer: Float, inner: Float, points: Int, rotation: Float) =
    Path().apply {
        for (i in 0 until 2 * points) {
            val r = if (i % 2 == 0) outer else inner
            val t = rotation + i / (2f * points) * 2 * PI.toFloat()
            val p = Offset(center.x + r * cos(t), center.y + r * sin(t))
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }

/** A rosette: a circle of radius [radius] waving [amplitude] in and out [petals] times ([amplitude] 0: a ring), in [segments] points. */
private fun rose(center: Offset, radius: Float, amplitude: Float, petals: Int, phase: Float, segments: Int) =
    Path().apply {
        for (i in 0..segments) {
            val t = i * 2 * PI.toFloat() / segments
            val r = radius + amplitude * cos(petals * t + phase)
            val p = Offset(center.x + r * cos(t), center.y + r * sin(t))
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }

/** The text colour of the coloured card drawn around, or null on the page (see [DefaultComponents.card]). */
private val LocalCardText = compositionLocalOf<Color?> { null }
