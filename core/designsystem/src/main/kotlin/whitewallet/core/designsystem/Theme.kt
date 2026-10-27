package whitewallet.core.designsystem

import android.content.res.Resources
import android.provider.Settings
import android.util.TypedValue
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.FontRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import whitewallet.brand.ui.BrandDesign
import whitewallet.brand.ui.DefaultBrandDesign
import whitewallet.brand.ReleaseStage
import whitewallet.brand.ui.LocalBrandDesign
import whitewallet.brand.ui.LocalReduceMotion
import whitewallet.brand.ui.ThemeTokens
import whitewallet.brand.ui.WalletColors
import whitewallet.brand.ui.WalletSpacing

/** The app's name, for decorations (a wordmark) and anything else that shows it. */
val LocalAppName = staticCompositionLocalOf { "" }

/** How finished the app is (brand `releaseStage`): alpha and beta are shown at setup and in About. */
val LocalReleaseStage = staticCompositionLocalOf { ReleaseStage.Stable }

/**
 * The app theme: the brand's tokens from its resources (brand_defaults.xml lists them; night variants come from
 * values-night), handed to the brand's [design], which may change them before they apply.
 */
@Composable
fun WalletTheme(design: BrandDesign = DefaultBrandDesign, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val tokens = remember(configuration, dark) { BrandTokens.read(resources, dark) }
    val reduceMotion = Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    CompositionLocalProvider(LocalBrandDesign provides design, LocalReduceMotion provides reduceMotion) {
        design.Theme(tokens, content)
    }
}

/** Reads the design tokens from the brand's resources. */
internal object BrandTokens {
    fun read(res: Resources, dark: Boolean): ThemeTokens {
        val scheme = colorScheme(res, dark)
        return ThemeTokens(
            dark = dark,
            colorScheme = scheme,
            typography = typography(res),
            shapes = shapes(res),
            walletColors = WalletColors.from(scheme).run {
                WalletColors(
                    incoming = res.role(R.color.brand_incoming) ?: incoming,
                    outgoing = res.role(R.color.brand_outgoing) ?: outgoing,
                    success = res.role(R.color.brand_success) ?: success,
                    warning = res.role(R.color.brand_warning) ?: warning,
                    warningContainer = res.role(R.color.brand_warning_container) ?: warningContainer,
                    onWarningContainer = res.role(R.color.brand_on_warning_container) ?: onWarningContainer,
                    amount = res.role(R.color.brand_amount) ?: amount,
                )
            },
            spacing = WalletSpacing(screen = res.dp(R.dimen.brand_spacing_screen), gap = res.dp(R.dimen.brand_spacing_gap)),
        )
    }

    /** Material's scheme with every role the brand set. */
    fun colorScheme(res: Resources, dark: Boolean): ColorScheme {
        val primary = Color(res.getColor(R.color.brand_primary, null))
        val onPrimary = Color(res.getColor(R.color.brand_on_primary, null))
        val secondary = Color(res.getColor(R.color.brand_secondary, null))
        val tertiary = Color(res.getColor(R.color.brand_tertiary, null))
        val base = if (dark) {
            darkColorScheme(primary = primary, onPrimary = onPrimary, secondary = secondary, tertiary = tertiary)
        } else {
            lightColorScheme(primary = primary, onPrimary = onPrimary, secondary = secondary, tertiary = tertiary)
        }
        return colorRoles.fold(base) { scheme, (id, apply) -> res.role(id)?.let { scheme.apply(it) } ?: scheme }
    }

    private val colorRoles: List<Pair<Int, ColorScheme.(Color) -> ColorScheme>> = listOf(
        R.color.brand_primary_container to { copy(primaryContainer = it) },
        R.color.brand_on_primary_container to { copy(onPrimaryContainer = it) },
        R.color.brand_inverse_primary to { copy(inversePrimary = it) },
        R.color.brand_on_secondary to { copy(onSecondary = it) },
        R.color.brand_secondary_container to { copy(secondaryContainer = it) },
        R.color.brand_on_secondary_container to { copy(onSecondaryContainer = it) },
        R.color.brand_on_tertiary to { copy(onTertiary = it) },
        R.color.brand_tertiary_container to { copy(tertiaryContainer = it) },
        R.color.brand_on_tertiary_container to { copy(onTertiaryContainer = it) },
        R.color.brand_background to { copy(background = it) },
        R.color.brand_on_background to { copy(onBackground = it) },
        R.color.brand_surface to { copy(surface = it) },
        R.color.brand_on_surface to { copy(onSurface = it) },
        R.color.brand_surface_variant to { copy(surfaceVariant = it) },
        R.color.brand_on_surface_variant to { copy(onSurfaceVariant = it) },
        R.color.brand_surface_tint to { copy(surfaceTint = it) },
        R.color.brand_inverse_surface to { copy(inverseSurface = it) },
        R.color.brand_inverse_on_surface to { copy(inverseOnSurface = it) },
        R.color.brand_error to { copy(error = it) },
        R.color.brand_on_error to { copy(onError = it) },
        R.color.brand_error_container to { copy(errorContainer = it) },
        R.color.brand_on_error_container to { copy(onErrorContainer = it) },
        R.color.brand_outline to { copy(outline = it) },
        R.color.brand_outline_variant to { copy(outlineVariant = it) },
        R.color.brand_scrim to { copy(scrim = it) },
        R.color.brand_surface_bright to { copy(surfaceBright = it) },
        R.color.brand_surface_dim to { copy(surfaceDim = it) },
        R.color.brand_surface_container to { copy(surfaceContainer = it) },
        R.color.brand_surface_container_high to { copy(surfaceContainerHigh = it) },
        R.color.brand_surface_container_highest to { copy(surfaceContainerHighest = it) },
        R.color.brand_surface_container_low to { copy(surfaceContainerLow = it) },
        R.color.brand_surface_container_lowest to { copy(surfaceContainerLowest = it) },
    )

    /** Material's typography with the brand's fonts per group and any text style it set. */
    fun typography(res: Resources): Typography {
        val body = res.font(R.font.brand_font_body)
        val display = res.font(R.font.brand_font_display)
        val families = mapOf(
            "display" to display,
            "headline" to (res.font(R.font.brand_font_headline) ?: display),
            "title" to (res.font(R.font.brand_font_title) ?: body),
            "body" to body,
            "label" to (res.font(R.font.brand_font_label) ?: body),
        )
        val base = Typography()
        fun TextStyle.brand(group: String, size: Int, lineHeight: Int, weight: Int): TextStyle {
            val s = res.getDimension(size)
            val l = res.getDimension(lineHeight)
            val w = res.getInteger(weight)
            val density = res.displayMetrics.scaledDensity()
            return copy(
                fontFamily = families[group] ?: fontFamily,
                fontSize = if (s > 0) (s / density).sp else fontSize,
                lineHeight = if (l > 0) (l / density).sp else this.lineHeight,
                fontWeight = if (w > 0) FontWeight(w) else fontWeight,
            )
        }
        return base.copy(
            displayLarge = base.displayLarge.brand("display", R.dimen.brand_text_display_large_size, R.dimen.brand_text_display_large_line_height, R.integer.brand_text_display_large_weight),
            displayMedium = base.displayMedium.brand("display", R.dimen.brand_text_display_medium_size, R.dimen.brand_text_display_medium_line_height, R.integer.brand_text_display_medium_weight),
            displaySmall = base.displaySmall.brand("display", R.dimen.brand_text_display_small_size, R.dimen.brand_text_display_small_line_height, R.integer.brand_text_display_small_weight),
            headlineLarge = base.headlineLarge.brand("headline", R.dimen.brand_text_headline_large_size, R.dimen.brand_text_headline_large_line_height, R.integer.brand_text_headline_large_weight),
            headlineMedium = base.headlineMedium.brand("headline", R.dimen.brand_text_headline_medium_size, R.dimen.brand_text_headline_medium_line_height, R.integer.brand_text_headline_medium_weight),
            headlineSmall = base.headlineSmall.brand("headline", R.dimen.brand_text_headline_small_size, R.dimen.brand_text_headline_small_line_height, R.integer.brand_text_headline_small_weight),
            titleLarge = base.titleLarge.brand("title", R.dimen.brand_text_title_large_size, R.dimen.brand_text_title_large_line_height, R.integer.brand_text_title_large_weight),
            titleMedium = base.titleMedium.brand("title", R.dimen.brand_text_title_medium_size, R.dimen.brand_text_title_medium_line_height, R.integer.brand_text_title_medium_weight),
            titleSmall = base.titleSmall.brand("title", R.dimen.brand_text_title_small_size, R.dimen.brand_text_title_small_line_height, R.integer.brand_text_title_small_weight),
            bodyLarge = base.bodyLarge.brand("body", R.dimen.brand_text_body_large_size, R.dimen.brand_text_body_large_line_height, R.integer.brand_text_body_large_weight),
            bodyMedium = base.bodyMedium.brand("body", R.dimen.brand_text_body_medium_size, R.dimen.brand_text_body_medium_line_height, R.integer.brand_text_body_medium_weight),
            bodySmall = base.bodySmall.brand("body", R.dimen.brand_text_body_small_size, R.dimen.brand_text_body_small_line_height, R.integer.brand_text_body_small_weight),
            labelLarge = base.labelLarge.brand("label", R.dimen.brand_text_label_large_size, R.dimen.brand_text_label_large_line_height, R.integer.brand_text_label_large_weight),
            labelMedium = base.labelMedium.brand("label", R.dimen.brand_text_label_medium_size, R.dimen.brand_text_label_medium_line_height, R.integer.brand_text_label_medium_weight),
            labelSmall = base.labelSmall.brand("label", R.dimen.brand_text_label_small_size, R.dimen.brand_text_label_small_line_height, R.integer.brand_text_label_small_weight),
        )
    }

    /** Material's shapes with the corner radii the brand set. */
    fun shapes(res: Resources): Shapes {
        val base = Shapes()
        fun corner(id: Int) = res.getDimension(id).takeIf { it >= 0 }?.let { RoundedCornerShape((it / res.displayMetrics.density).dp) }
        return Shapes(
            extraSmall = corner(R.dimen.brand_corner_extra_small) ?: base.extraSmall,
            small = corner(R.dimen.brand_corner_small) ?: base.small,
            medium = corner(R.dimen.brand_corner_medium) ?: base.medium,
            large = corner(R.dimen.brand_corner_large) ?: base.large,
            extraLarge = corner(R.dimen.brand_corner_extra_large) ?: base.extraLarge,
        )
    }

    /** A colour role, or null while it's `@color/brand_unset`. */
    fun Resources.role(@ColorRes id: Int): Color? = getColor(id, null).takeIf { it != getColor(R.color.brand_unset, null) }?.let(::Color)

    /** A font group as a family, or null while it's unset (`@null`, which resolves to no file). */
    fun Resources.font(@FontRes id: Int): FontFamily? {
        val value = TypedValue()
        getValue(id, value, true)
        // A font file or font-family XML is a path. Loaded as a typeface, a family keeps its weights (Typeface.create
        // picks the weight).
        return if (value.type == TypedValue.TYPE_STRING) FontFamily(getFont(id)) else null
    }

    private fun Resources.dp(@DimenRes id: Int) = (getDimension(id) / displayMetrics.density).dp

    @Suppress("DEPRECATION")
    private fun android.util.DisplayMetrics.scaledDensity() = scaledDensity
}
