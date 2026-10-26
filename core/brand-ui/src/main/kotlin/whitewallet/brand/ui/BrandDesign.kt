package whitewallet.brand.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import whitewallet.brand.WalletEvent
import whitewallet.brand.WalletStatus

/**
 * A brand's design in code (docs/WHITE_LABEL.md, "Design"), on top of what its resources already set. Every member
 * has a default that keeps the app as it is, so a brand overrides only what it changes, and new members in later
 * releases don't break it. Register one in the brand's code module: `@Provides fun design(): BrandDesign = …`.
 *
 * The wallet keeps its flows, texts and accessibility: a design decides how things look, never what happens.
 */
interface BrandDesign {
    /**
     * The theme around the whole app. [tokens] are built from the brand's resources; change any of them and end
     * with [WalletMaterialTheme], adding CompositionLocals of the brand's own if its components need them.
     */
    @Composable
    fun Theme(tokens: ThemeTokens, content: @Composable () -> Unit) {
        WalletMaterialTheme(tokens, content)
    }

    /** The building blocks of every screen: buttons, fields, rows, cards, dialogs and more. */
    val components: BrandComponents get() = DefaultComponents

    /**
     * Brand content at a named place in the app ([Anchor]), such as a logo above the onboarding form or a mascot
     * above the balance. [default] is what the app shows there without a brand design (often nothing): call it to
     * keep it, or draw something else. Decorations are decorative: the app keeps the texts and headings next to them.
     */
    @Composable
    fun Decoration(anchor: Anchor, context: DecorationContext, default: @Composable () -> Unit) {
        default()
    }
}

/** What a [BrandDesign.Decoration] knows about where it is shown. */
@Immutable
class DecorationContext(
    /** What the screen is about right now. */
    val status: WalletStatus,
    /** The user asked for less motion (animations off in the system settings): change states without moving. */
    val reduceMotion: Boolean,
    /** The app's name, for a wordmark. */
    val appName: String,
)

/** The design without any brand code. */
object DefaultBrandDesign : BrandDesign

val LocalBrandDesign = staticCompositionLocalOf<BrandDesign> { DefaultBrandDesign }

/** What the current screen is about; screens set it, decorations read it. */
val LocalWalletStatus = compositionLocalOf<WalletStatus> { WalletStatus.None }

/** Every [WalletEvent] while the app runs, for brand composables that react to them. */
val LocalWalletEvents = staticCompositionLocalOf<Flow<WalletEvent>> { emptyFlow() }

/** The user asked for less motion (animator duration scale 0). */
val LocalReduceMotion = staticCompositionLocalOf { false }
