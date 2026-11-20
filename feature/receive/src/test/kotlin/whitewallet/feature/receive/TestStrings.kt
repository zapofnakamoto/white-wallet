package whitewallet.feature.receive

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import org.robolectric.RuntimeEnvironment

/** String [id] in the test's language: a test looks for what the screen shows, not a copy of its wording. */
internal fun text(@StringRes id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

/** Plural [id] for [count] in the test's language, with [count] as its only argument. */
internal fun plural(@PluralsRes id: Int, count: Int): String =
    RuntimeEnvironment.getApplication().resources.getQuantityString(id, count, count)
