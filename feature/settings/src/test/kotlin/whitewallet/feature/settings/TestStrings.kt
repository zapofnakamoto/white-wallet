package whitewallet.feature.settings

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import org.robolectric.RuntimeEnvironment

/** String [id] in the test's language: a test looks for what the screen shows, not a copy of its wording. */
internal fun text(@StringRes id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

/** A plural for [count], with [args] as its arguments, or [count] without any. */
internal fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    RuntimeEnvironment.getApplication().resources.getQuantityString(id, count, *(if (args.isEmpty()) arrayOf(count) else args))
