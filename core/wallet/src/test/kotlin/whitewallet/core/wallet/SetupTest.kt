package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.SetupStep
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * A wallet's setup: created or restored, then a mint, then an address, each step kept across restarts; giving it up, and when it can't be.
 */
class SetupTest {
    private val store = InMemoryWalletStore()
    private val seeds = InMemorySeedStore()
    private val wallet = Wallet(store, seeds, LnurlcashClient(), maxAccounts = 2)
    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun `a new wallet starts its setup with no mint, and the step is kept until it is done`() =
        runBlocking<Unit> {
            assertFalse(wallet.isSetUp())
            val id = assertIs<CreateAccountResult.Created>(wallet.createWallet("Main")).accountId
            assertEquals(id, store.currentAccountId())
            assertEquals(SetupStep.Mint, wallet.setupStep.first())
            assertFalse(wallet.isSetUp(), "the app opens on the setup step")
            assertNull(store.homeOf(id))
            assertTrue(store.mintMap.value.isEmpty(), "no mint was contacted")

            wallet.setSetupStep(SetupStep.Address)
            assertEquals(SetupStep.Address, Wallet(store, seeds, LnurlcashClient()).setupStep.first(), "after a restart too")
            wallet.setSetupStep(null)
            assertTrue(wallet.isSetUp())
            assertNull(store.wallet(id)?.activeBeforeSetup)
        }

    @Test
    fun `an account is added to the active wallet without a mint, up to the brand's limit`() =
        runBlocking<Unit> {
            val first = assertIs<CreateAccountResult.Created>(wallet.createWallet("Main")).accountId
            wallet.setSetupStep(null)
            val second = assertIs<CreateAccountResult.Created>(wallet.createAccount("Savings")).accountId
            assertEquals(second, store.currentAccountId())
            assertEquals(first to 1L, store.account(second)?.let { it.walletId to it.index })
            assertTrue(wallet.isSetUp(), "an account needs no setup")
            assertEquals(CreateAccountResult.AccountLimitReached(2), wallet.createAccount("Third"))
        }

    @Test
    fun `an account that holds nothing is removed unless it is its wallet's last, and the next one added takes its index`() =
        runBlocking<Unit> {
            val wallet = Wallet(store, seeds, LnurlcashClient(), maxAccounts = 3)
            val first = assertIs<CreateAccountResult.Created>(wallet.createWallet("Main")).accountId
            wallet.setSetupStep(null)
            assertEquals(RemoveAccountResult.LastAccount, wallet.removeAccount())
            assertEquals(AccountRemoval(RemoveAccountResult.LastAccount, 0, emptyList()), wallet.accountRemoval())
            val second = assertIs<CreateAccountResult.Created>(wallet.createAccount("Savings")).accountId
            val third = assertIs<CreateAccountResult.Created>(wallet.createAccount("Travel")).accountId

            // The one in use goes; the wallet's oldest other account is in use then.
            wallet.switchAccount(second)
            assertEquals(AccountRemoval(null, 0, emptyList()), wallet.accountRemoval())
            assertEquals(RemoveAccountResult.Removed(first), wallet.removeAccount())
            assertEquals(first, store.currentAccountId())
            assertEquals(listOf(0L, 2L), wallet.accounts.first().map { it.account.index }.sorted())
            assertEquals(listOf(first, third), wallet.walletGroups.first().single().accounts.map { it.account.id })

            val again = assertIs<CreateAccountResult.Created>(wallet.createAccount("Again")).accountId
            assertEquals(1L, store.account(again)?.index, "the lowest free index")
        }

    @Test
    fun `giving up a new wallet's setup removes it and goes back to the account before`() =
        runBlocking<Unit> {
            val before = assertIs<CreateAccountResult.Created>(wallet.createWallet("Main")).accountId
            wallet.setSetupStep(null)
            val id = assertIs<CreateAccountResult.Created>(wallet.createWallet("Other")).accountId
            assertFailsWith<IllegalStateException>("only a wallet in its setup") {
                Wallet(store, seeds, LnurlcashClient()).also { store.setActiveAccount(before) }.abandonSetup()
            }
            store.setActiveAccount(id)

            assertEquals(AbandonResult.Abandoned(remains = true), wallet.abandonSetup())
            assertEquals(before, store.currentAccountId())
            assertNull(store.wallet(id))
            assertEquals(setOf(before), seeds.walletIds())
        }

    @Test
    fun `a new wallet never backed up stays once it holds anything, a restored one can always go`() =
        runBlocking<Unit> {
            val holdings =
                listOf<suspend (String) -> Unit>(
                    {
                        store.upsertNotes(
                            listOf(Note("n".repeat(64), it, "mint.example", NoteKind.KeyPath, 0, 0, 1_000, null, NoteState.Outstanding, 1))
                        )
                    },
                    { store.saveAddress(it, RegisteredAddress("alice", "mint.example", 1, share = 0, feeInvoice = "lnbc1held")) },
                )
            for (hold in holdings) {
                val id = assertIs<CreateAccountResult.Created>(wallet.createWallet("Main")).accountId
                hold(id)
                assertEquals(AbandonResult.Refused, wallet.abandonSetup())
                assertEquals(id, store.currentAccountId(), "it stays, with its phrase")
                wallet.setSetupStep(null)
                store.deleteAccount(id)
            }

            val restored = assertIs<RestoreResult.Restored>(wallet.restore(phrase, null, "Restored")).accountId
            assertEquals(SetupStep.Mint, wallet.setupStep.first())
            store.upsertNotes(
                listOf(Note("r".repeat(64), restored, "mint.example", NoteKind.KeyPath, 0, 0, 1_000, null, NoteState.Outstanding, 1))
            )
            assertEquals(AbandonResult.Abandoned(remains = false), wallet.abandonSetup(), "its phrase is written down")
            assertNull(store.currentAccountId())
        }
}
