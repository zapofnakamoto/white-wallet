package whitewallet.core.designsystem

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * The configurations every screen preview is drawn in (docs/DEVELOPMENT.md, "Screenshots"): light, the first and the one every screenshot
 * run draws; dark; a large font on a small phone, where text runs out of room first; German, whose words are longer.
 */
@Preview(name = "light")
@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "large-font", fontScale = 2f, device = "spec:width=360dp,height=640dp")
@Preview(name = "de", locale = "de")
annotation class WalletPreviews

/** A preview that `just check` draws in every [WalletPreviews] configuration, not only light: one that tends to break. */
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class CheckAll

/** A preview of a moment that moves (a success screen): also drawn while its picture moves, not only once it is over. */
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Moment
