package whitewallet.feature.onboarding

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.Connection
import whitewallet.core.wallet.CreateAccountResult
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.NotePolicies
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * Picking a mint to add: nothing leaves the phone toward a mint until the user acts on it. The one picked from the list is asked at once,
 * its problem shows right there, and going on uses that answer. The mints are answered here, by what each test says they do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddMintViewModelTest {
    private val first = "first.example"
    private val other = "other.example"

    /** What a mint does when asked for one of its documents. */
    private enum class Mint {
        Fine,
        NoKey,
        KeyNotAnswered,
        Down,
    }

    private val mints = ConcurrentHashMap(mapOf(first to Mint.Fine, other to Mint.Fine))
    private val asked = ConcurrentHashMap<String, AtomicInteger>()

    /** Holds every answer back until it is opened. */
    @Volatile private var gate: CountDownLatch? = null

    private val answers = Interceptor { chain ->
        val url = chain.request().url
        asked.computeIfAbsent(url.host) { AtomicInteger() }.incrementAndGet()
        gate?.await()
        val mint = mints.getValue(url.host)
        val discovery = url.encodedPath.startsWith("/.well-known/lnurlw/")
        if (mint == Mint.Down || mint == Mint.KeyNotAnswered && discovery) throw IOException("no answer")
        val key = if (mint == Mint.NoKey) "" else ""","mintPubkey":"$KEY""""
        val json =
            if (discovery)
                """{"tag":"withdrawRequest","callback":"https://${url.host}/w","minWithdrawable":1000,"maxWithdrawable":1000000,""" +
                    """"defaultDescription":"a mint"$key}"""
            else
                """{"tag":"payRequest","callback":"https://${url.host}/p/cb","minSendable":1000,"maxSendable":1000000,""" +
                    """"metadata":"[[\"text/plain\",\"A mint\"]]","commentAllowed":64,""" +
                    """"withdrawLink":"https://${url.host}/.well-known/lnurlw/_"}"""
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private val connection = MutableStateFlow(Connection.Online)
    private val wallet =
        Wallet(
            InMemoryWalletStore(),
            InMemorySeedStore(),
            LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(answers).build()),
            policies = NotePolicies.InMemory(ManualStrategy.ID),
            connection = connection,
        )

    @BeforeTest fun setMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    private fun askedOf(host: String): Int = asked[host]?.get() ?: 0

    /** Until [host] was asked [times] documents: a mint that is fine changes nothing on the page to wait for. */
    private suspend fun awaitAsked(host: String, times: Int) = withTimeout(10_000) { while (askedOf(host) < times) delay(10) }

    private suspend fun AddMintViewModel.await(what: (AddMintState) -> Boolean): AddMintState = withTimeout(10_000) { state.first(what) }

    /** The picker as it opens: both mints offered, the first one preselected. */
    private suspend fun picker(): AddMintViewModel {
        assertTrue(wallet.createWallet("Main") is CreateAccountResult.Created)
        val screen = AddMintViewModel(wallet, AppDefaults(listOf(first, other), allowMintChange = true), AppPreferences.InMemory())
        screen.init(setup = false)
        screen.await { it.loaded }
        return screen
    }

    @Test
    fun `no mint is asked before the user acts on it, not even the one preselected`() =
        runBlocking<Unit> {
            val screen = picker()
            assertEquals(first, screen.state.value.host)
            assertEquals(AddMintStep.Pick, screen.state.value.step)
            assertEquals(0, askedOf(first) + askedOf(other))
        }

    @Test
    fun `while the mint is asked, Next keeps its look`() {
        // The button is locked by busy; disabled as well, it would turn grey while it works.
        assertTrue(AddMintState(host = first, busy = true).canNext)
    }

    @Test
    fun `a mint picked from the list is asked at once, and its missing key shows without going on`() =
        runBlocking<Unit> {
            mints[other] = Mint.NoKey
            val screen = picker()
            screen.onMintSelected(MintOption.Suggested(other))
            val state = screen.await { it.error != null }
            assertEquals(AddMintError.WithoutKey(other, asked = true), state.error)
            assertEquals(MintProblem.NoSigningKey, state.problems[other])
            assertEquals(AddMintStep.Pick, state.step)
            assertFalse(state.busy)
            assertTrue(state.canNext)
            assertEquals(0, askedOf(first))
        }

    @Test
    fun `a picked mint that can't be asked for its key isn't answering`() =
        runBlocking<Unit> {
            mints[other] = Mint.KeyNotAnswered
            val screen = picker()
            screen.onMintSelected(MintOption.Suggested(other))
            val state = screen.await { it.error != null }
            assertEquals(AddMintError.WithoutKey(other, asked = false), state.error)
            assertEquals(MintProblem.NotAnswering, state.problems[other])
        }

    @Test
    fun `going on uses the answer the pick got`() =
        runBlocking<Unit> {
            val screen = picker()
            screen.onMintSelected(MintOption.Suggested(other))
            awaitAsked(other, 2)
            // Both documents are in; the page shows nothing about a mint that is fine.
            assertNull(screen.await { !it.busy }.error)
            screen.next()
            val info = screen.await { it.step == AddMintStep.Info }
            assertEquals(other, info.preview?.host)
            assertEquals(2, askedOf(other))
        }

    @Test
    fun `going on while the picked mint is still asked waits for that answer`() =
        runBlocking<Unit> {
            val screen = picker()
            val open = CountDownLatch(1)
            gate = open
            screen.onMintSelected(MintOption.Suggested(other))
            screen.next()
            assertTrue(screen.state.value.busy)
            assertEquals(AddMintStep.Pick, screen.state.value.step)
            open.countDown()
            screen.await { it.step == AddMintStep.Info }
            assertEquals(2, askedOf(other))
        }

    @Test
    fun `a picked mint that didn't answer is asked again on going on, and its row forgets the problem once it answers`() =
        runBlocking<Unit> {
            mints[other] = Mint.Down
            val screen = picker()
            screen.onMintSelected(MintOption.Suggested(other))
            val down = screen.await { it.error != null }
            assertEquals(AddMintError.Unreachable, down.error)
            assertEquals(MintProblem.NotAnswering, down.problems[other])

            mints[other] = Mint.Fine
            screen.next()
            val info = screen.await { it.step == AddMintStep.Info }
            assertNull(info.problems[other])
        }

    @Test
    fun `picking another mint takes the message away, and the list remembers what was learned`() =
        runBlocking<Unit> {
            mints[other] = Mint.NoKey
            val screen = picker()
            screen.onMintSelected(MintOption.Suggested(other))
            screen.await { it.error != null }
            screen.onMintSelected(MintOption.Suggested(first))
            assertNull(screen.state.value.error)
            awaitAsked(first, 2)
            val state = screen.state.value
            assertEquals(MintProblem.NoSigningKey, state.problems[other])
            assertNull(state.problems[first])
        }

    @Test
    fun `a typed address is asked only on going on`() =
        runBlocking<Unit> {
            mints["typed.example"] = Mint.Fine
            val screen = picker()
            screen.onMintSelected(MintOption.Custom)
            screen.onCustomHostChange("typed.example")
            delay(200)
            assertEquals(0, askedOf("typed.example"))
            screen.next()
            screen.await { it.step == AddMintStep.Info }
        }

    @Test
    fun `offline a pick asks nothing, and back online the picked mint is asked`() =
        runBlocking<Unit> {
            mints[other] = Mint.NoKey
            connection.value = Connection.Offline
            val screen = picker()
            screen.await { it.offline }
            screen.onMintSelected(MintOption.Suggested(other))
            delay(200)
            assertEquals(0, askedOf(other))

            connection.value = Connection.Online
            assertEquals(AddMintError.WithoutKey(other, asked = true), screen.await { it.error != null }.error)
        }

    private companion object {
        /** A valid compressed key: the curve's generator. */
        const val KEY = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    }
}
