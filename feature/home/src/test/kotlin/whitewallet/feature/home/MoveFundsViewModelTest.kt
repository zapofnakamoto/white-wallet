package whitewallet.feature.home

import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import whitewallet.core.model.Account
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.MoveCheck
import whitewallet.core.wallet.MoveFailure
import whitewallet.core.wallet.MoveResult
import whitewallet.core.wallet.MoveStayed
import whitewallet.core.wallet.StayReason
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * What the Move funds screen is told about the phone's wallets and the mints a move would add, and what Move does before anything moves.
 * Runs without any mint: the test answers in the place of [host], and [silent] never answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveFundsViewModelTest {
    private val host = "mint.example"
    private val silent = "silent.example"
    private val store = InMemoryWalletStore()

    /** While set, no request gets its answer (or its failure) until it is released. */
    @Volatile private var hold: CountDownLatch? = null

    /** [host] says what it is, as a mint does when asked before a move; nothing else is answered, by anyone. */
    private val mints = Interceptor { chain ->
        val url = chain.request().url
        hold?.await()
        if (url.host != host || !url.encodedPath.startsWith("/.well-known/")) throw IOException("no answer")
        val json =
            if ("lnurlp" in url.encodedPath)
                """{"tag":"payRequest","callback":"https://$host/p/cb","minSendable":2000,"maxSendable":1000000000,""" +
                    """"metadata":"[[\"text/plain\", \"A mint\"]]","withdrawLink":"https://$host/w","commentAllowed":64}"""
            else
                """{"tag":"withdrawRequest","callback":"https://$host/w","minWithdrawable":1000,"maxWithdrawable":999999000,""" +
                    """"defaultDescription":"a note","mintPubkey":"02${"11".repeat(32)}"}"""
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }
    private val wallet =
        Wallet(
            store,
            InMemorySeedStore(),
            LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(mints).build()),
        )

    private fun note(id: String, at: String, msat: Long) =
        Note(id, "a", at, NoteKind.KeyPath, 0, 0, msat, null, NoteState.Outstanding, 0, scheme = KeyScheme.Bip32)

    @BeforeTest fun setMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    /** Two wallets of one account each, "b" the older; "a" is in use and holds one note of 3 sats at [host]. */
    private suspend fun screen(): MoveFundsViewModel {
        store.addAccount("b", "Savings", null, host)
        store.addAccount("a", "Main", null, host)
        store.upsertNotes(listOf(note("n1", host, 3_000)))
        return MoveFundsViewModel(wallet, AppDefaults(emptyList(), allowMintChange = true)).also { screen ->
            CoroutineScope(Dispatchers.Unconfined).launch { screen.state.collect {} }
        }
    }

    /** The screen's state once [settled] holds. */
    private suspend fun MoveFundsViewModel.await(settled: (MoveFundsState) -> Boolean): MoveFundsState {
        repeat(100) {
            state.value.takeIf(settled)?.let {
                return it
            }
            delay(50)
        }
        error("never got there: ${state.value}")
    }

    @Test
    fun `every wallet has its number, oldest first, and its name if the user gave it one`() =
        runBlocking<Unit> {
            val screen = screen()
            val state = screen.await { it.from != null }
            assertEquals(mapOf("b" to 1, "a" to 2), state.walletNumbers)
            assertEquals(emptyMap(), state.walletNames)
            assertEquals("b", state.targetId, "the only other account is the target")

            store.saveWallet(store.wallets.first().first { it.id == "b" }.copy(name = "Travel"))
            assertEquals(mapOf("b" to "Travel"), screen.await { it.walletNames.isNotEmpty() }.walletNames)
        }

    @Test
    fun `the mints a move would add follow the target and the amount`() =
        runBlocking<Unit> {
            val screen = screen()
            assertEquals(listOf(host), screen.await { it.from != null && it.addsMints.isNotEmpty() }.addsMints)

            // More than the mint holds: nothing would move, so nothing would be added.
            screen.onEverything(false)
            screen.onAmount("5")
            screen.await { it.amountMsat == 5_000L && it.addsMints.isEmpty() }
            screen.onAmount("2")
            assertEquals(listOf(host), screen.await { it.amountMsat == 2_000L && it.addsMints.isNotEmpty() }.addsMints)

            // An account of the wallet in use: its wallet has the mint.
            store.saveAccount(Account("a2", "Spending", 2, walletId = "a", index = 1, homeHost = host))
            screen.onTarget("a2")
            screen.await { it.targetId == "a2" && it.addsMints.isEmpty() }
        }

    @Test
    fun `a move that can't be whole waits for the user, and nothing is sent until they say so`() =
        runBlocking<Unit> {
            val screen = screen()
            store.upsertNotes(listOf(note("n2", silent, 9_000)))
            screen.await { it.from?.balanceMsat == 12_000L }

            screen.move()
            val asked = screen.await { it.partial != null }
            assertEquals(MoveCheck(3_000, listOf(MoveStayed(silent, 9_000, StayReason.Unreachable))), asked.partial)
            assertFalse(asked.busy)
            assertNull(asked.result)

            screen.cancelPartial()
            val cancelled = screen.await { it.partial == null }
            assertNull(cancelled.result, "nothing was tried")
            assertTrue(store.journal.isEmpty())

            screen.move()
            screen.await { it.partial != null }
            screen.confirmPartial()
            val done = screen.await { it.result != null }
            assertNull(done.partial)
            assertFalse(done.busy)
        }

    @Test
    fun `a move of which nothing can be made is said as it is, without trying, and stays shown while it is tried again`() =
        runBlocking<Unit> {
            val screen = screen()
            store.upsertNotes(listOf(note("n1", silent, 3_000)))
            screen.await { it.from != null }

            screen.move()
            val stayed = listOf(MoveStayed(silent, 3_000, StayReason.Unreachable))
            val failed = screen.await { it.result != null }
            assertEquals(MoveResult.Failed(MoveFailure.Refused, stayed = stayed), failed.result)
            assertNull(failed.partial)
            assertTrue(store.journal.isEmpty())

            // Try again: the result is there while the mints are asked, until the next one is.
            val asking = CountDownLatch(1).also { hold = it }
            screen.move()
            assertEquals(failed.result, screen.await { it.busy }.result)
            asking.countDown()
            assertEquals(failed.result, screen.await { !it.busy }.result)
        }
}
