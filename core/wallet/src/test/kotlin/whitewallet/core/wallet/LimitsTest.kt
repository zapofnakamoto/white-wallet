package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.client.LnurlcashClient

/** The brand's limits (brand.properties `maxAccounts`, `maxAddresses`). Checked before anything is sent. */
class LimitsTest {
    private val phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    private fun wallet(store: InMemoryWalletStore, maxAccounts: Int) =
        Wallet(store, InMemorySeedStore(mapOf("a" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "))),
            LnurlcashClient(), maxAccounts = maxAccounts)

    @Test
    fun `no account beyond the limit is created or restored`() = runBlocking {
        val store = InMemoryWalletStore()
        store.addAccount("a", "Main", Profile("alice", "mint.example"))
        val wallet = wallet(store, maxAccounts = 1)
        assertFalse(wallet.canAddAccount.first())
        assertEquals(OnboardResult.AccountLimitReached(1), wallet.onboard("bob", "mint.example", "Savings"))
        assertEquals(RestoreResult.AccountLimitReached(1), wallet.restore(phrase, "mint.example", "Old"))
        assertEquals(1, store.accounts.first().size)
    }

    @Test
    fun `below the limit, accounts can be added`() = runBlocking {
        val store = InMemoryWalletStore()
        store.addAccount("a", "Main", Profile("alice", "mint.example"))
        assertTrue(wallet(store, maxAccounts = 2).canAddAccount.first())
        assertTrue(wallet(store, maxAccounts = AppDefaults.UNLIMITED).canAddAccount.first())
    }

    @Test
    fun `changing the address of the only account is not a new account`() = runBlocking {
        val store = InMemoryWalletStore()
        store.addAccount("a", "Main", Profile("alice", "mint.example"))
        val result = wallet(store, maxAccounts = 1).onboard("bob", "not a host", null)
        assertIs<OnboardResult.ServiceInvalid>(result, "gets past the account limit")
    }

    @Test
    fun `brand limits read 0 as no limit, and developer mode overrides addresses`() {
        assertEquals(AppDefaults.UNLIMITED, AppDefaults.limit(0))
        assertEquals(2, AppDefaults.limit(2))
        val defaults = AppDefaults("mint.example", allowServiceChange = true, maxAddresses = 3)
        assertEquals(3, defaults.addressLimit(developerMode = false, developerLimit = 10))
        assertEquals(10, defaults.addressLimit(developerMode = true, developerLimit = 10))
        assertEquals(3, defaults.addressLimit(developerMode = true, developerLimit = null), "not set: the brand's")
    }
}
