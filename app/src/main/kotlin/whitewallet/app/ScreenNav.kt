package whitewallet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable

/**
 * A screen's way to the other screens, open only while that screen is the one in front. A screen that is left stays drawn, and can be
 * tapped, until the next one has faded in, and its own late answer (a mint removed, a payment done) may arrive then too: going back from
 * there would take the screen under it along, down to an app with no screen at all.
 */
internal class ScreenNav(private val nav: NavController, private val entry: NavBackStackEntry) {
    private val inFront: Boolean
        get() = nav.currentBackStackEntry?.id == entry.id

    /** Back to the screen under this one; never off the last screen, which only the system's back leaves. */
    fun back() {
        if (inFront && nav.previousBackStackEntry != null) nav.popBackStack()
    }

    /** Back to the screen at [route], if it is under this one. */
    fun backTo(route: String) {
        if (inFront) nav.popBackStack(route, inclusive = false)
    }

    fun open(route: String, options: NavOptionsBuilder.() -> Unit = {}) {
        if (inFront) nav.navigate(route, options)
    }
}

/** A screen of the app at [route], which reaches the other screens through its [ScreenNav]. */
internal fun NavGraphBuilder.screen(
    nav: NavController,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable ScreenNav.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry -> remember(entry) { ScreenNav(nav, entry) }.content(entry) }
}
