package whitewallet.feature.settings

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
import whitewallet.core.model.Mint
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/** Changing the home mint with no other mint, adding one from the empty page, and coming back to it picked. No mint is asked. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoneyMoveViewModelTest {
    private val store = InMemoryWalletStore()
    private val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient())

    private fun mint(host: String) = Mint("a", host, "https://$host/w", null, 1_000, 1_000_000_000, null, null)

    // The view model runs on the test's dispatcher: a test waits for a state as it comes, with no clock of its own.
    @BeforeTest fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    private suspend fun MoneyMoveViewModel.await(settled: (MoneyMoveState) -> Boolean): MoneyMoveState = state.first(settled)

    @Test
    fun `a mint added while there was none to pick comes back picked`() = runTest {
        store.addAccount("a", "Main", null, "home.example")
        store.saveMint(mint("home.example"))
        val screen = MoneyMoveViewModel(wallet, AppDefaults(emptyList(), allowMintChange = true))
        CoroutineScope(Dispatchers.Unconfined).launch { screen.state.collect {} }
        screen.init(MoneyMoveMode.ChangeHome, null)

        val empty = screen.await { it.loaded }
        assertTrue(empty.nothingToPick)
        assertTrue(empty.canAddMint, "the brand lets users add a mint")

        store.saveMint(mint("new.example"))
        val picked = screen.await { it.targets.isNotEmpty() }
        assertEquals(listOf("new.example"), picked.targets)
        assertEquals("new.example", picked.target)
    }

    @Test
    fun `while collecting at home, adding a mint gives nowhere to move money`() = runTest {
        store.addAccount("a", "Main", null, "home.example")
        store.saveMint(mint("home.example"))
        wallet.setCollectAtHome(true)
        val screen = MoneyMoveViewModel(wallet, AppDefaults(emptyList(), allowMintChange = true))
        CoroutineScope(Dispatchers.Unconfined).launch { screen.state.collect {} }
        screen.init(MoneyMoveMode.Move, "home.example")
        assertEquals(false, screen.await { it.loaded }.canAddMint)
    }
}
