package whitewallet.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import whitewallet.brand.ui.BrandDesign
import whitewallet.core.designsystem.LocalAmountUnit
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.LocalReleaseStage
import whitewallet.core.designsystem.WalletTheme
import whitewallet.core.model.AmountUnit
import whitewallet.core.wallet.releaseStageFromConfig

/**
 * [content] as the brand's app draws a screen: in the brand's theme and [design], with what the app takes from brand.properties
 * (MainActivity provides the same).
 */
@Composable
fun BrandFrame(design: BrandDesign, content: @Composable () -> Unit) {
    WalletTheme(design) {
        CompositionLocalProvider(
            LocalAppName provides BuildConfig.APP_NAME,
            LocalReleaseStage provides releaseStageFromConfig(BuildConfig.RELEASE_STAGE, BuildConfig.VERSION_NAME),
            LocalAmountUnit provides AmountUnit.fromConfig(BuildConfig.DEFAULT_AMOUNT_UNIT),
        ) {
            Surface(Modifier.fillMaxSize()) { content() }
        }
    }
}
