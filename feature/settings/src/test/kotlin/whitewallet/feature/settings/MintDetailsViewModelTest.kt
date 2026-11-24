package whitewallet.feature.settings

import androidx.lifecycle.SavedStateHandle
import java.io.IOException
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
    private val silent = Interceptor { throw IOException("no answer") }
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
    fun `setTrusted is ignored while the key doesn't match`() = runTest {
        trust.setKeyChanged("w", "other.example", true)
        val screen = page()
        screen.state.first { it.details != null && it.mint != null }
        screen.setTrusted(true)
        testScheduler.advanceUntilIdle()
        assertFalse(trusted("other.example"))
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
