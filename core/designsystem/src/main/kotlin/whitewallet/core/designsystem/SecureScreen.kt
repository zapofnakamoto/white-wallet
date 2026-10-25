package whitewallet.core.designsystem

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Blocks screenshots and screen recording, and blanks the Recents thumbnail, while [content] is shown.
 * Use around anything that displays secrets such as the recovery phrase.
 */
@Composable
fun SecureScreen(content: @Composable () -> Unit) {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    content()
}
