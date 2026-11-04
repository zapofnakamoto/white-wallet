package whitewallet.core.designsystem

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies a secret to the clipboard marked as sensitive (hidden from the system clipboard preview), and clears it after [clearAfterMillis]
 * unless something else has been copied since.
 */
object SensitiveClipboard {
    /** [clearAfterMillis]: the brand's `security.clipboard.clearAfter` (`AppDefaults.timings`). */
    fun copy(context: Context, label: String, text: String, clearAfterMillis: Long) {
        val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }

        val handler = Handler(Looper.getMainLooper())
        var replaced = false
        lateinit var listener: ClipboardManager.OnPrimaryClipChangedListener
        val clear = Runnable {
            clipboard.removePrimaryClipChangedListener(listener)
            if (!replaced) clipboard.clearPrimaryClip()
        }
        clipboard.setPrimaryClip(clip)
        // Registered after our own copy, so any later change means the user copied something else.
        listener = ClipboardManager.OnPrimaryClipChangedListener { replaced = true }
        clipboard.addPrimaryClipChangedListener(listener)
        handler.postDelayed(clear, clearAfterMillis)
    }
}
