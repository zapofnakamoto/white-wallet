package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.Account
import whitewallet.core.model.WalletRecord
import whitewallet.lnurlcash.client.LnurlcashClient

/** Which accounts the switcher offers, which one is in use, and whether its mark shows at all (Home's rule, on every page). */
class AccountChoiceTest {
    private val store = InMemoryWalletStore()

    private fun wallet(maxAccounts: Int = AppDefaults.UNLIMITED, maxWallets: Int = AppDefaults.UNLIMITED) =
        Wallet(store, InMemorySeedStore(), LnurlcashClient(), maxAccounts = maxAccounts, maxWallets = maxWallets)

    @Test
    fun `not shown with one account when the brand allows one account and one wallet`() = runBlocking {
        store.addAccount("a", "Main", null)
        val choice = wallet(maxAccounts = 1, maxWallets = 1).accountChoice.first()
        assertFalse(choice.shown)
        assertEquals("a", choice.active?.account?.id)
    }

    @Test
    fun `shown with one account when the brand allows several accounts`() = runBlocking {
        store.addAccount("a", "Main", null)
        assertTrue(wallet(maxAccounts = 3, maxWallets = 1).accountChoice.first().shown)
    }

    @Test
    fun `shown with one account when the brand allows several wallets`() = runBlocking {
        store.addAccount("a", "Main", null)
        assertTrue(wallet(maxAccounts = 1, maxWallets = 2).accountChoice.first().shown)
    }

    @Test
    fun `shown with two accounts whatever the brand allows`() = runBlocking {
        store.addAccount("a", "Main", null)
        store.addAccount("b", "Savings", null)
        val choice = wallet(maxAccounts = 1, maxWallets = 1).accountChoice.first()
        assertTrue(choice.shown)
        assertEquals("b", choice.activeId)
    }

    @Test
    fun `wallet names hold only the wallets the user named`() = runBlocking {
        store.addAccount("a", "Main", null)
        store.saveWallet(WalletRecord("b", "Family", 1))
        store.saveAccount(Account("b", "Kids", 1, walletId = "b"))
        assertEquals(mapOf("b" to "Family"), wallet().accountChoice.first().walletNames)
    }

    @Test
    fun `the active wallet's number counts wallets in the accounts' order`() = runBlocking {
        store.addAccount("a", "Main", null)
        store.addAccount("b", "Other", null)
        val choice = wallet().accountChoice.first()
        assertEquals(2, choice.activeWalletNumber)
        store.setActiveAccount("a")
        assertEquals(1, wallet().accountChoice.first().activeWalletNumber)
    }

    @Test
    fun `nothing is active before there is an account`() = runBlocking {
        val choice = wallet().accountChoice.first()
        assertEquals(null, choice.active)
        assertEquals(0, choice.activeWalletNumber)
    }
}
