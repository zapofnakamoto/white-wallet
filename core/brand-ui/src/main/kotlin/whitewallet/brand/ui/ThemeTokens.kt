package whitewallet.brand.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's theme as built from the brand's resources (docs/WHITE_LABEL.md, "Design"): Material's colour scheme,
 * typography and shapes, plus the wallet's own colour roles and spacing. [dark] is the scheme in use, after the
 * brand's `app.appearance` and the system setting.
 */
@Immutable
data class ThemeTokens(
    val dark: Boolean,
    val colorScheme: ColorScheme,
    val typography: Typography,
    val shapes: Shapes,
    val walletColors: WalletColors,
    val spacing: WalletSpacing,
)

/** Colour roles Material doesn't have, for what a wallet shows. Each falls back to a Material role when unset. */
@Immutable
data class WalletColors(
    /** Money coming in, such as a received amount in the history. Default: primary. */
    val incoming: Color,
    /** Money going out. Default: onSurface. */
    val outgoing: Color,
    /** Something went well: an address is available, a payment arrived. Default: primary. */
    val success: Color,
    /** Needs the user's attention, without being an error. Default: error. */
    val warning: Color,
    /** Notices that need the user (back up, a mint changed its key). Default: errorContainer / onErrorContainer. */
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Large amounts, such as the balance. Default: onSurface. */
    val amount: Color,
) {
    companion object {
        /** Every role at its fallback in [scheme]. */
        fun from(scheme: ColorScheme) = WalletColors(
            incoming = scheme.primary,
            outgoing = scheme.onSurface,
            success = scheme.primary,
            warning = scheme.error,
            warningContainer = scheme.errorContainer,
            onWarningContainer = scheme.onErrorContainer,
            amount = scheme.onSurface,
        )
    }
}

/** The screens' rhythm: the padding at a screen's edges and the gap between its blocks. */
@Immutable
data class WalletSpacing(val screen: Dp = 24.dp, val gap: Dp = 16.dp)

/** Null outside [WalletMaterialTheme] (a preview, a test): the roles then follow Material's colours. */
val LocalWalletColors = staticCompositionLocalOf<WalletColors?> { null }
val LocalWalletSpacing = staticCompositionLocalOf { WalletSpacing() }

/** The wallet's colour roles, next to Material's: `MaterialTheme.walletColors.incoming`. */
val MaterialTheme.walletColors: WalletColors
    @Composable @ReadOnlyComposable get() = LocalWalletColors.current ?: WalletColors.from(colorScheme)

/** The wallet's spacing: `MaterialTheme.walletSpacing.screen`. */
val MaterialTheme.walletSpacing: WalletSpacing
    @Composable @ReadOnlyComposable get() = LocalWalletSpacing.current

/** Applies [tokens]: Material's theme and the wallet's colours and spacing. A brand's [BrandDesign.Theme] ends here. */
@Composable
fun WalletMaterialTheme(tokens: ThemeTokens, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWalletColors provides tokens.walletColors, LocalWalletSpacing provides tokens.spacing) {
        MaterialTheme(colorScheme = tokens.colorScheme, typography = tokens.typography, shapes = tokens.shapes, content = content)
    }
}
