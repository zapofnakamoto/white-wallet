package whitewallet.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource

/** The app theme, built from the brand colors in brand/res (night variants come from values-night). */
@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    val primary = colorResource(R.color.brand_primary)
    val onPrimary = colorResource(R.color.brand_on_primary)
    val secondary = colorResource(R.color.brand_secondary)
    val tertiary = colorResource(R.color.brand_tertiary)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = primary, onPrimary = onPrimary, secondary = secondary, tertiary = tertiary)
    } else {
        lightColorScheme(primary = primary, onPrimary = onPrimary, secondary = secondary, tertiary = tertiary)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
