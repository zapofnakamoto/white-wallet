package whitewallet.feature.onboarding

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import whitewallet.core.wallet.CreateAccountResult
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.NotePolicies
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient

/** What the wallet's ready screen learns from the wallet: which one the setup was for, and whether it is the phone's only one. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletReadyViewModelTest {
    private val wallet =
        Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))

    @BeforeTest fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    private suspend fun ready(): WalletReadyState = WalletReadyViewModel(wallet).state.first { it.loaded }

    @Test
    fun `nothing is known before the wallet was read`() = runTest {
        assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
        val screen = WalletReadyViewModel(wallet)
        assertFalse(screen.state.value.loaded)
        // Read to its end: nothing of it is left running when the test's dispatcher goes.
        screen.state.first { it.loaded }
    }

    @Test
    fun `the first wallet is the only one, new and not backed up`() = runTest {
        assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
        val state = ready()
        assertFalse(state.several)
        assertEquals(1, state.number)
        assertNull(state.name)
        assertEquals(0, state.foundMsat)
        assertFalse(state.backedUp)
    }

    @Test
    fun `an added wallet is the one in use, by its number or the name the user gave it`() = runTest {
        assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
        assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
        val state = ready()
        assertTrue(state.several)
        assertEquals(2, state.number)
        assertNull(state.name)

        wallet.renameWallet("Family")
        wallet.markBackupVerified()
        val named = ready()
        assertEquals("Family", named.name)
        assertTrue(named.backedUp)
    }
}
