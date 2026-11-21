package whitewallet.feature.home

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.Connection
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.Wallet
import whitewallet.core.wallet.WalletSettings
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * Home and the phone's connection: offline is said at once, and Home updates by itself once the phone is back, when its network is found to
 * reach the internet. Runs without any mint: a refresh shows as the requests it sends to [host], which never answers. The view model, the
 * wallet and its requests all run on the test's dispatcher, so the wait is the test's to let pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val host = "mint.example"
    private val store = InMemoryWalletStore()
    private val connection = MutableStateFlow(Connection.Offline)
    private val dispatcher = StandardTestDispatcher()
    private val wait = WalletSettings().backOnlineVerifiedWaitMillis

    /** Requests a refresh sent to [host]. */
    private val asked = AtomicInteger()
    private val mints = Interceptor { chain ->
        if (chain.request().url.host == host) asked.incrementAndGet()
        throw IOException("no answer")
    }

    /** What the wallet runs by itself; cancelled after each test. */
    private val background = CoroutineScope(dispatcher + Job())
    private val wallet =
        Wallet(
            store,
            InMemorySeedStore(),
            LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(mints).build(), io = dispatcher),
            connection = connection,
            scope = background,
        )

    /** Where the screen's state is collected, as the screen would; cancelled after each test. */
    private val screen = CoroutineScope(Dispatchers.Unconfined + Job())

    /** Holds the view model, so that clearing it ends what it runs before the next test. */
    private val viewModels = ViewModelStore()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun cleanUp() {
        screen.cancel()
        viewModels.clear()
        background.cancel()
        // Cleared, a view model's scope finishes cancelling on the main dispatcher: reset only once that ran.
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    /**
     * Home for an account whose home mint is [host], with a Lightning address there (which every refresh checks, whatever the network), its
     * first refresh (at the start, offline) done.
     */
    private suspend fun TestScope.home(): HomeViewModel {
        store.addAccount("a", "Main", null, host)
        store.saveAddress("a", RegisteredAddress("alice", host, addedAtMillis = 0, share = 0))
        val factory = viewModelFactory {
            initializer {
                HomeViewModel(wallet, AppPreferences.InMemory(), AppDefaults(emptyList(), allowMintChange = true)) { currentTime }
            }
        }
        val home = ViewModelProvider.create(viewModels, factory)[HomeViewModel::class]
        screen.launch { home.state.collect {} }
        runCurrent()
        val state = home.state.value
        assertTrue(state.loaded && state.offline && !state.refreshing, "$state")
        return home
    }

    @Test
    fun `offline is said at once and back online Home updates by itself once the network is found to work`() =
        runTest(dispatcher) {
            val home = home()
            val before = asked.get()

            // A network, not yet found to reach the internet: still offline, and nothing is asked over it yet.
            connection.value = Connection.Unverified
            advanceTimeBy(wait / 2)
            assertTrue(home.state.value.offline, "the line went before anything could be asked")
            assertEquals(before, asked.get(), "asked before the network was found to work")

            // Found to work: the line goes and Home updates, together.
            connection.value = Connection.Online
            runCurrent()
            assertFalse(home.state.value.offline)
            assertTrue(asked.get() > before, "no refresh: still $before requests")

            connection.value = Connection.Offline
            runCurrent()
            assertTrue(home.state.value.offline)
        }

    @Test
    fun `a network never found to reach the internet gets its refresh after the wait`() =
        runTest(dispatcher) {
            val home = home()
            val before = asked.get()
            connection.value = Connection.Unverified
            advanceTimeBy(wait - 1)
            assertTrue(home.state.value.offline)
            assertEquals(before, asked.get(), "asked before the wait was over")
            advanceTimeBy(1)
            runCurrent()
            assertFalse(home.state.value.offline)
            assertTrue(asked.get() > before, "no refresh: still $before requests")
        }

    @Test
    fun `offline the refresh that failed isn't offered again, the offline line says it all`() =
        runTest(dispatcher) {
            val state = home().state.value
            assertTrue(state.offline)
            assertFalse(state.refreshFailed)
        }
}
