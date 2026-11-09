package whitewallet.feature.onboarding

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.robolectric.RuntimeEnvironment
import whitewallet.core.designsystem.R as DesignR

/** String [id] in the test's language: a test looks for what the screen shows, not a copy of its wording. */
internal fun text(@StringRes id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

internal fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    RuntimeEnvironment.getApplication().resources.getQuantityString(id, count, *args)

/** The top bar's back arrow. */
internal fun ComposeTestRule.onNodeWithContentDescriptionBack(): SemanticsNodeInteraction =
    onNodeWithContentDescription(text(DesignR.string.back))
