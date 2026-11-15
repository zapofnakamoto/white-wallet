package whitewallet.feature.settings

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import whitewallet.core.model.Account
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.UnusedAccount
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/** The Wallets page follows the wallet's account rule as notes come. No mint is asked. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletsViewModelTest {
    private val store = InMemoryWalletStore()
    private val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient())

    @BeforeTest fun setMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    private suspend fun WalletsViewModel.await(settled: (WalletsState) -> Boolean): WalletsState {
        repeat(100) {
            state.value.takeIf(settled)?.let {
                return it
            }
            delay(50)
        }
        error("never got there: ${state.value}")
    }

    @Test
    fun `the account to use first goes once it holds a note`() =
        runBlocking<Unit> {
            store.addAccount("a", "Main", null)
            val screen = WalletsViewModel(wallet)
            CoroutineScope(Dispatchers.Unconfined).launch { screen.state.collect {} }
            val main = Account("a", "Main", 0, homeHost = "mint.example")
            assertEquals(UnusedAccount(main, hidesLater = false), screen.await { it.loaded && it.useFirst != null }.useFirst)

            store.upsertNotes(listOf(Note("c".repeat(64), "a", "mint.example", NoteKind.KeyPath, 0, 0, 1_000, null, NoteState.Spent, 0)))
            assertEquals(null, screen.await { it.useFirst == null }.useFirst)
        }
}
