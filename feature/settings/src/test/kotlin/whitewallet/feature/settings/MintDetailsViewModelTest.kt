package whitewallet.feature.settings

import androidx.lifecycle.SavedStateHandle
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import whitewallet.core.model.Account
import whitewallet.core.model.Mint
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.WalletRecord
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.MintTrustStore
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/** A mint's page: its trust, changed only with its pinned key, and the account mark read with the rest. No mint answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class MintDetailsViewModelTest {
    private val store = InMemoryWalletStore()
    private val trust = MintTrustStore.InMemory()
    /** While set, no request gets its failure until it is released. */
    @Volatile private var hold: CountDownLatch? = null
    private val silent = Interceptor {
        hold?.await()
        throw IOException("no answer")
    }
    private val wallet =
        Wallet(
            store,
            InMemorySeedStore(),
            LnurlcashClient(http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor(silent).build()),
            trust = trust,
        )

    @BeforeTest fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    /** Wallet "w", account "a" at home on h1.example; other.example known with a pinned key. */
    private suspend fun page(host: String = "other.example"): MintDetailsViewModel {
        store.saveWallet(WalletRecord("w", null, 0))
        store.saveAccount(Account("a", "A", 0, walletId = "w", homeHost = "h1.example"))
        for (h in listOf("h1.example", "other.example")) store.saveMint(
            Mint("w", h, "https://$h/w", "02" + "ab".repeat(32), 1_000, 1_000_000_000, null, null)
        )
        store.setActiveAccount("a")
        return MintDetailsViewModel(wallet, SavedStateHandle(mapOf("host" to host)), AppDefaults(emptyList(), allowMintChange = true))
            .also {
                CoroutineScope(Dispatchers.Unconfined).launch { it.state.collect {} }
            }
    }

    private suspend fun trusted(host: String) = wallet.mints.first().single { it.host == host }.trusted

    @Test
    fun `setTrusted writes trust for the pinned key`() = runTest {
        val screen = page()
        screen.state.first { it.details != null && it.mint != null }
        screen.setTrusted(true)
        wallet.mints.first { list -> list.single { it.host == "other.example" }.trusted }
        screen.setTrusted(false)
        wallet.mints.first { list -> !list.single { it.host == "other.example" }.trusted }
        assertEquals(false, screen.state.first { it.mint?.trusted == false }.mint?.trusted)
    }

    @Test
    fun `the page knows which other accounts of the wallet have the mint as home, never the one in use`() = runTest {
        val other = page()
        store.saveAccount(Account("b", "Savings", 1, walletId = "w", index = 1, homeHost = "other.example"))
        store.saveAccount(Account("c", "Daily", 2, walletId = "w", index = 2, homeHost = "h1.example"))
        assertEquals(listOf("Savings"), other.state.first { it.details != null && it.homeOf.isNotEmpty() }.homeOf)
        val home =
            MintDetailsViewModel(wallet, SavedStateHandle(mapOf("host" to "h1.example")), AppDefaults(emptyList(), allowMintChange = true))
        CoroutineScope(Dispatchers.Unconfined).launch { home.state.collect {} }
        assertEquals(listOf("Daily"), home.state.first { it.details != null && it.homeOf.isNotEmpty() }.homeOf)
    }

    @Test
    fun `setTrusted is ignored while the key doesn't match`() = runTest {
        trust.setKeyChanged("w", "other.example", true)
        val screen = page()
        screen.state.first { it.details != null && it.mint != null }
        screen.setTrusted(true)
        testScheduler.advanceUntilIdle()
        assertFalse(trusted("other.example"))
    }

    private fun note(id: String, account: String, host: String, msat: Long) =
        Note(id, account, host, NoteKind.KeyPath, 0, id.hashCode().toLong() and 0xffff, msat, null, NoteState.Outstanding, 0)

    @Test
    fun `switching account on a mint's page never shows one account's home with another's money`() = runTest {
        // A: at home on h1, money there. B: at home on h2, money on h3 (so "Move all money here" is B's, for h2).
        store.saveWallet(WalletRecord("w", null, 0))
        store.saveAccount(Account("a", "A", 0, walletId = "w", index = 0, homeHost = "h1.example"))
        store.saveAccount(Account("b", "B", 1, walletId = "w", index = 1, homeHost = "h2.example"))
        for (h in listOf("h1.example", "h2.example", "h3.example")) store.saveMint(
            Mint("w", h, "https://$h/w", "02" + "ab".repeat(32), 1_000, 1_000_000_000, null, null)
        )
        store.upsertNotes(listOf(note("n1", "a", "h1.example", 5_000), note("n2", "b", "h3.example", 7_000)))
        store.setActiveAccount("a")
        val screen =
            MintDetailsViewModel(wallet, SavedStateHandle(mapOf("host" to "h1.example")), AppDefaults(emptyList(), allowMintChange = true))
        val seen = mutableListOf<MintDetailsState>()
        CoroutineScope(Dispatchers.Unconfined).launch { screen.state.collect { seen += it } }
        screen.state.first { it.details != null && !it.loading && it.accounts?.activeId == "a" }

        // The mint takes its time to answer for B: what the page shows meanwhile is still all A's.
        val latch = CountDownLatch(1).also { hold = it }
        screen.switchAccount("b")
        wallet.activeAccount.first { it?.account?.id == "b" }
        testScheduler.advanceUntilIdle()
        hold = null
        latch.countDown()
        screen.state.first { it.details != null && !it.loading && it.accounts?.activeId == "b" }

        for (s in seen.filter { it.details != null }) {
            when (s.accounts?.activeId) {
                "a" -> assertTrue(s.details!!.isHome && s.home && !s.moneyElsewhere && s.details.balanceMsat == 5_000L, "A's page: $s")
                "b" -> assertTrue(!s.details!!.isHome && !s.home && s.moneyElsewhere && s.details.balanceMsat == 0L, "B's page: $s")
                else -> error("a page without its account: $s")
            }
        }
    }

    @Test
    fun `a key the wallet saw not match is known to the page though the mint doesn't answer`() = runTest {
        trust.setKeyChanged("w", "other.example", true)
        val screen = page()
        assertTrue(screen.state.first { it.details != null }.keyChanged)
    }

    @Test
    fun `the account mark comes with the mint, never after it`() = runTest {
        val screen = page()
        val seen = mutableListOf<MintDetailsState>()
        CoroutineScope(Dispatchers.Unconfined).launch { screen.state.collect { seen += it } }
        screen.state.first { it.details != null }
        assertTrue(seen.filter { it.host != null }.all { it.accounts != null }, "the title and the account mark come together: $seen")
        assertTrue(seen.filter { it.details != null }.all { it.accounts != null && it.mint != null }, "$seen")
        assertNotNull(seen.last().accounts)
    }
}
