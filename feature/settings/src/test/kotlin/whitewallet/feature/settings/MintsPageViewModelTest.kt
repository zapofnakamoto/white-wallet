package whitewallet.feature.settings

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import whitewallet.core.wallet.NoHome
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/** The Mints page's state: drawn once everything is read, never one account's home with another's money. No mint is asked. */
@OptIn(ExperimentalCoroutinesApi::class)
class MintsPageViewModelTest {
    private val store = InMemoryWalletStore()
    private val trust = MintTrustStore.InMemory()
    private val key = "02" + "ab".repeat(32)

    private fun wallet(maxAccounts: Int = AppDefaults.UNLIMITED, maxWallets: Int = AppDefaults.UNLIMITED) =
        Wallet(store, InMemorySeedStore(), LnurlcashClient(), trust = trust, maxAccounts = maxAccounts, maxWallets = maxWallets)

    private fun mint(host: String) = Mint("w", host, "https://$host/w", key, 1_000, 1_000_000_000, null, null)

    private fun note(id: String, account: String, host: String, msat: Long) =
        Note(id, account, host, NoteKind.KeyPath, 0, id.hashCode().toLong() and 0xffff, msat, null, NoteState.Outstanding, 0)

    @BeforeTest fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    /** One wallet "w" with accounts "a" (home h1.example, ₿5 there) and "b" (home h2.example, ₿7 there); "a" in use. */
    private suspend fun twoAccounts() {
        store.saveWallet(WalletRecord("w", null, 0))
        store.saveAccount(Account("a", "A", 0, walletId = "w", index = 0, homeHost = "h1.example"))
        store.saveAccount(Account("b", "B", 1, walletId = "w", index = 1, homeHost = "h2.example"))
        store.saveMint(mint("h1.example"))
        store.saveMint(mint("h2.example"))
        store.upsertNotes(listOf(note("n1", "a", "h1.example", 5_000), note("n2", "b", "h2.example", 7_000)))
        store.setActiveAccount("a")
    }

    private fun MintsPageViewModel.watch(): MutableList<MintsPageState> {
        val seen = mutableListOf<MintsPageState>()
        CoroutineScope(Dispatchers.Unconfined).launch { state.collect { seen += it } }
        return seen
    }

    @Test
    fun `nothing is drawn until every source answered, then home and the others come together`() = runTest {
        twoAccounts()
        val screen = MintsPageViewModel(wallet(), AppDefaults(emptyList(), allowMintChange = true))
        val seen = screen.watch()
        val loaded = screen.state.first { it.loaded }
        assertFalse(seen.first().loaded)
        assertEquals("h1.example", loaded.home?.host)
        assertEquals(listOf("h2.example"), loaded.others.map { it.host })
        assertTrue(seen.filter { it.loaded }.all { it.home != null })
    }

    @Test
    fun `switching account never shows one account's home with another's money`() = runTest {
        twoAccounts()
        val screen = MintsPageViewModel(wallet(), AppDefaults(emptyList(), allowMintChange = true))
        val seen = screen.watch()
        screen.state.first { it.loaded }
        screen.switchAccount("b")
        screen.state.first { it.loaded && it.home?.host == "h2.example" }
        val loaded = seen.filter { it.loaded }
        for (s in loaded) {
            val byHost = (listOfNotNull(s.home) + s.others).associateBy { it.host }
            when (s.home?.host) {
                "h1.example" -> {
                    assertEquals(5_000, byHost.getValue("h1.example").balanceMsat, "account a's view: $s")
                    assertEquals(0, byHost.getValue("h2.example").balanceMsat, "account a's view: $s")
                }
                "h2.example" -> {
                    assertEquals(7_000, byHost.getValue("h2.example").balanceMsat, "account b's view: $s")
                    assertEquals(0, byHost.getValue("h1.example").balanceMsat, "account b's view: $s")
                }
                else -> error("a loaded state without a home: $s")
            }
            assertEquals(s.home.host == "h2.example", s.accounts?.activeId == "b", "the account mark follows the page: $s")
        }
    }

    @Test
    fun `a mint whose key changed is marked`() = runTest {
        twoAccounts()
        trust.setKeyChanged("w", "h2.example", true)
        val loaded =
            MintsPageViewModel(wallet(), AppDefaults(emptyList(), allowMintChange = true)).also { it.watch() }.state.first { it.loaded }
        assertTrue(loaded.others.single().keyChanged)
        assertFalse(loaded.home!!.keyChanged)
    }

    @Test
    fun `no home gives the way to pick one and still the other mints`() = runTest {
        twoAccounts()
        store.saveAccount(Account("a", "A", 0, walletId = "w", index = 0, homeHost = null))
        val loaded =
            MintsPageViewModel(wallet(), AppDefaults(emptyList(), allowMintChange = true)).also { it.watch() }.state.first { it.loaded }
        assertNull(loaded.home)
        assertEquals(NoHome.PickHome, loaded.noHome)
        assertEquals(listOf("h1.example", "h2.example"), loaded.others.map { it.host })
    }

    @Test
    fun `the home can be changed only where the brand lets it`() = runTest {
        twoAccounts()
        val allowed = MintsPageViewModel(wallet(), AppDefaults(emptyList(), allowMintChange = true)).also { it.watch() }
        assertTrue(allowed.state.first { it.loaded }.canChangeHome)
        val fixed = MintsPageViewModel(wallet(), AppDefaults(emptyList(), allowMintChange = false)).also { it.watch() }
        assertFalse(fixed.state.first { it.loaded }.canChangeHome)
    }

    @Test
    fun `the account mark follows the brand's rule`() = runTest {
        store.saveWallet(WalletRecord("w", null, 0))
        store.saveAccount(Account("a", "A", 0, walletId = "w", homeHost = "h1.example"))
        store.saveMint(mint("h1.example"))
        store.setActiveAccount("a")
        val one =
            MintsPageViewModel(wallet(maxAccounts = 1, maxWallets = 1), AppDefaults(emptyList(), allowMintChange = true)).also {
                it.watch()
            }
        assertEquals(false, one.state.first { it.loaded }.accounts?.shown)
        val many = MintsPageViewModel(wallet(maxAccounts = 5), AppDefaults(emptyList(), allowMintChange = true)).also { it.watch() }
        assertEquals(true, many.state.first { it.loaded }.accounts?.shown)
    }

    @Test
    fun `a mint where the account still has notes out of its balance is used, one with only spent notes is not`() = runTest {
        twoAccounts()
        val states =
            listOf(NoteState.Shared, NoteState.SharedUnconfirmed, NoteState.Unknown, NoteState.Paying, NoteState.Ambiguous, NoteState.Spent)
        for (st in states) {
            val host = "${st.name.lowercase()}.example"
            store.saveMint(mint(host))
            store.upsertNotes(listOf(note("x-${st.name}", "a", host, 3_000).copy(state = st)))
        }
        val screen = MintsPageViewModel(wallet(), AppDefaults(emptyList(), allowMintChange = true))
        screen.watch()
        val used = screen.state.first { it.loaded }.others.associate { it.host to it.used }
        for (st in states) assertEquals(st != NoteState.Spent, used["${st.name.lowercase()}.example"], "a note $st")
    }
}
