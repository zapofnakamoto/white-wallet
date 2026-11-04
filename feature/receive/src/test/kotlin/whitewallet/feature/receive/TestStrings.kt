package whitewallet.feature.receive

import androidx.annotation.StringRes
import org.robolectric.RuntimeEnvironment

/** String [id] in the test's language: a test looks for what the screen shows, not a copy of its wording. */
internal fun text(@StringRes id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)
