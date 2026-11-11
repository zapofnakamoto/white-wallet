package whitewallet.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import whitewallet.brand.ui.Anchor

/**
 * When a page says that it is loading: only after [shownAfterMillis], so a page that is ready in a moment never shows it, and then for at
 * least [shownAtLeastMillis], so it never blinks.
 */
data class LoadingTimes(val shownAfterMillis: Long, val shownAtLeastMillis: Long)

/** The app provides wallet.properties' `loading.indicator.*`; previews and tests get these. */
val LocalLoadingTimes = staticCompositionLocalOf { LoadingTimes(shownAfterMillis = 400, shownAtLeastMillis = 500) }

/**
 * Remembers across pages whether a loading indicator is up or just was, so that a wait that goes on in the next place (the app opens, then
 * its first page loads, then the wallet refreshes) is one wait: the next indicator comes up at once instead of after a moment of nothing or
 * of figures about to change. The app provides one ([LocalLoadingCarry]); without one every wait starts over.
 */
class LoadingCarry(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var up = 0
    private var lastUpMillis: Long? = null

    internal fun enter() {
        up++
    }

    internal fun leave() {
        up--
        lastUpMillis = nowMillis()
    }

    /** Whether an indicator is up, or was within [withinMillis]. */
    fun justUp(withinMillis: Long): Boolean = up > 0 || lastUpMillis?.let { nowMillis() - it <= withinMillis } == true
}

val LocalLoadingCarry = staticCompositionLocalOf<LoadingCarry?> { null }

/** What a page that reads what it shows draws. */
enum class LoadingPhase {
    /** Loading for a moment: nothing yet, or what was there. */
    Blank,

    /** Loading for a while, or the indicator's shortest time isn't over: the indicator. */
    Indicator,

    /** Loaded: the page. */
    Content,
}

/**
 * The one rule for everything that loads ([LocalLoadingTimes]): nothing for a moment, then the indicator for at least its shortest time,
 * then the content. Loading that ends within the moment never shows the indicator; loading that ends just after it keeps the indicator up.
 * Loading that starts while an indicator is up elsewhere, or within its shortest time after one was ([LoadingCarry]), goes on with the
 * indicator at once.
 */
@Composable
fun loadingPhase(loading: Boolean): LoadingPhase {
    val times = LocalLoadingTimes.current
    val carry = LocalLoadingCarry.current
    val current by rememberUpdatedState(loading)
    var showing by remember { mutableStateOf(loading && carry?.justUp(times.shownAtLeastMillis) == true) }
    var shortestTimeOver by remember { mutableStateOf(false) }
    LaunchedEffect(times) {
        var continuing = showing
        while (true) {
            if (!continuing) {
                snapshotFlow { current }.first { it }
                if (carry?.justUp(times.shownAtLeastMillis) != true) {
                    val quick = withTimeoutOrNull(times.shownAfterMillis) { snapshotFlow { current }.first { !it } }
                    if (quick != null) continue
                }
                shortestTimeOver = false
                showing = true
            }
            continuing = false
            delay(times.shownAtLeastMillis)
            shortestTimeOver = true
            snapshotFlow { current }.first { !it }
            showing = false
        }
    }
    val indicator = showing && (loading || !shortestTimeOver)
    if (indicator && carry != null) {
        DisposableEffect(carry) {
            carry.enter()
            onDispose { carry.leave() }
        }
    }
    return when {
        indicator -> LoadingPhase.Indicator
        loading -> LoadingPhase.Blank
        else -> LoadingPhase.Content
    }
}

/**
 * Whether a page that stays on screen says that it works on something (a progress bar, a spinner beside a button): by the rule of
 * [loadingPhase], so not for work that ends in a moment, and then for its shortest time. What the work locks (a button) is locked at once;
 * only the sign of it waits.
 */
@Composable fun showsWorking(working: Boolean): Boolean = loadingPhase(loading = working) == LoadingPhase.Indicator

/**
 * Whether a page inside a screen's frame (under its top bar) may draw: false until [loaded], and while the loading indicator this draws in
 * the meantime is up ([loadingPhase]). Call it where the page's body starts, with the frame's padding as [modifier]: `if
 * (!pageLoaded(state.loaded, Modifier.padding(padding))) return@Scaffold`.
 */
@Composable
fun pageLoaded(loaded: Boolean, modifier: Modifier = Modifier): Boolean {
    val phase = loadingPhase(loading = !loaded)
    if (phase == LoadingPhase.Indicator) LoadingIndicator(modifier.fillMaxSize())
    return phase == LoadingPhase.Content
}

/** [pageLoaded] for a screen that can't draw its frame yet: an empty screen, then the indicator on it. */
@Composable
fun screenLoaded(loaded: Boolean): Boolean {
    val phase = loadingPhase(loading = !loaded)
    if (phase != LoadingPhase.Content) {
        Surface(Modifier.fillMaxSize()) { if (phase == LoadingPhase.Indicator) LoadingIndicator(Modifier.fillMaxSize()) }
    }
    return phase == LoadingPhase.Content
}

/** The brand's picture for a page that is loading ([Anchor.PageLoading]), centred, and "Loading" to a screen reader. */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.loading)
    Box(
        modifier.semantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center,
    ) {
        BrandDecoration(Anchor.PageLoading) { WalletCircularProgress(Modifier.size(40.dp)) }
    }
}
