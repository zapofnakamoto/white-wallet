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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
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
 * reach the internet. Runs without any mint: a refresh shows as the requests it sends to [host], which never answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val host = "mint.example"
    private val store = InMemoryWalletStore()
    private val connection = MutableStateFlow(Connection.Offline)

    /** Requests a refresh sent to [host]. */
    private val asked = AtomicInteger()
    private val mints = Interceptor { chain ->
        if (chain.request().url.host == host) asked.incrementAndGet()
        throw IOException("no answer")
    }
    private val wallet =
        Wallet(
            store,
            InMemorySeedStore(),
            LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(mints).build()),
            connection = connection,
            settings = WalletSettings(backOnlineVerifiedWaitMillis = WAIT_MILLIS),
        )

    /** Where the screen's state is collected, as the screen would; cancelled after each test. */
    private val screen = CoroutineScope(Dispatchers.Unconfined + Job())

    /** Holds the view model, so that clearing it ends what it runs before the next test. */
    private val viewModels = ViewModelStore()

    @BeforeTest fun setMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        screen.cancel()
        viewModels.clear()
        Dispatchers.resetMain()
    }

    /**
     * Home for an account whose home mint is [host], with a Lightning address there (which every refresh checks, whatever the network), its
     * first refresh (at the start, offline) done.
     */
    private suspend fun home(): HomeViewModel {
        store.addAccount("a", "Main", null, host)
        store.saveAddress("a", RegisteredAddress("alice", host, addedAtMillis = 0, share = 0))
        val factory = viewModelFactory {
            initializer {
                HomeViewModel(wallet, AppPreferences.InMemory(), AppDefaults(emptyList(), allowMintChange = true)) {
                    System.currentTimeMillis()
                }
            }
        }
        val home = ViewModelProvider.create(viewModels, factory)[HomeViewModel::class]
        screen.launch { home.state.collect {} }
        home.await { it.loaded && it.offline && !it.refreshing }
        return home
    }

    private suspend fun HomeViewModel.await(settled: (HomeState) -> Boolean): HomeState {
        repeat(100) {
            state.value.takeIf(settled)?.let {
                return it
            }
            delay(20)
        }
        error("never got there: ${state.value}")
    }

    /** Waits until a refresh sent more requests than [before], or fails. */
    private suspend fun refreshedSince(before: Int) {
        repeat(100) {
            if (asked.get() > before) return
            delay(20)
        }
        error("no refresh: still $before requests")
    }

    @Test
    fun `offline is said at once and back online Home updates by itself once the network is found to work`() =
        runBlocking<Unit> {
            val home = home()
            val before = asked.get()

            // A network, not yet found to reach the internet: still offline, and nothing is asked over it yet.
            connection.value = Connection.Unverified
            delay(WAIT_MILLIS / 4)
            assertTrue(home.state.value.offline, "the line went before anything could be asked")
            assertEquals(before, asked.get(), "asked before the network was found to work")

            // Found to work: the line goes and Home updates, together.
            connection.value = Connection.Online
            assertFalse(home.await { !it.offline }.offline)
            refreshedSince(before)

            connection.value = Connection.Offline
            assertTrue(home.await { it.offline }.offline)
        }

    @Test
    fun `a network never found to reach the internet gets its refresh after the wait`() =
        runBlocking<Unit> {
            home()
            val before = asked.get()
            connection.value = Connection.Unverified
            refreshedSince(before)
        }

    @Test
    fun `offline the refresh that failed isn't offered again, the offline line says it all`() =
        runBlocking<Unit> {
            val state = home().state.value
            assertTrue(state.offline)
            assertFalse(state.refreshFailed)
        }

    private companion object {
        /** Long enough that a refresh before it shows; short enough for the test. */
        const val WAIT_MILLIS = 800L
    }
}
