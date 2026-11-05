package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.MintDialect

/** Several accounts `S/a'` under one recovery phrase: their own money and addresses, backed up, restored and deleted as one wallet. */
class RegtestWalletAccountsTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    private fun restored(store: InMemoryWalletStore = InMemoryWalletStore()) =
        Wallet(store, InMemorySeedStore(), LnurlcashClient(), requireAddress = false, policies = NotePolicies.InMemory(ManualStrategy.ID))

    private suspend fun Wallet.balances() = accounts.first().associate { it.account.index to it.balanceMsat }

    /** A second account of [phone]'s wallet with [sats] on it, paid by invoice: on a legacy mint it can't have an address. */
    private suspend fun secondAccount(phone: Regtest.Phone, sats: Long) {
        assertIs<CreateAccountResult.Created>(phone.wallet.createAccount(HOST, "Savings", inActiveWallet = true))
        val invoice = assertIs<InvoiceRequest.Ready>(phone.wallet.requestInvoice(sats * 1000))
        Regtest.pay(invoice.invoice, 0)
        assertIs<InvoiceStatus.Paid>(phone.wallet.invoiceStatus(invoice.id))
    }

    private fun phone() = runBlocking {
        val store = InMemoryWalletStore()
        val wallet = restored(store)
        val name = Regtest.name()
        assertIs<OnboardResult.Ready>(wallet.onboard(name, HOST))
        Regtest.Phone(store, wallet, "$name@$HOST")
    }

    @Test
    fun `accounts of one wallet share its phrase and nothing else, and a restore finds them all, each with its own money`() =
        runBlocking<Unit> {
            val phone = phone()
            phone.fund(2000)
            val phrase = phone.wallet.recoveryPhrase()
            val first = phone.wallet.activeId()
            secondAccount(phone, 3000)
            assertNotEquals(first, phone.wallet.activeId())
            assertEquals(phrase, phone.wallet.recoveryPhrase(), "one phrase")
            assertEquals(1, phone.store.accounts.first().map { it.account.walletId }.distinct().size)
            assertEquals(mapOf(0L to 1_999_000L, 1L to 2_999_000L), phone.wallet.balances())

            // Nothing of the first account's reaches the second, also not its legacy keys on a legacy mint.
            phone.wallet.refresh()
            assertEquals(mapOf(0L to 1_999_000L, 1L to 2_999_000L), phone.wallet.balances())

            val store = InMemoryWalletStore()
            val other = restored(store)
            assertIs<RestoreResult.Restored>(other.restore(phrase.joinToString(" "), HOST, "Main"))
            assertEquals(mapOf(0L to 1_999_000L, 1L to 2_999_000L), other.balances(), "and no third account")
            other.refresh()
            assertEquals(mapOf(0L to 1_999_000L, 1L to 2_999_000L), other.balances(), "counted once")
        }

    @Test
    fun `deleting a wallet takes all its accounts and only them`() =
        runBlocking<Unit> {
            val phone = phone()
            val other = phone.wallet.activeId()
            secondAccount(phone, 1000)
            assertEquals(listOf(0L, 1L), phone.store.accounts.first().map { it.account.index }.sorted())
            assertEquals(false, phone.wallet.deleteActiveAccount(), "the wallet was the only one")
            assertEquals(emptyList(), phone.store.accounts.first().map { it.account.id }.filter { it == other })
        }

    @Test
    fun `giving up the address step of a restored further account removes that account, not the wallet`() =
        runBlocking<Unit> {
            val phone = phone()
            val name = phone.address.substringBefore('@')
            secondAccount(phone, 1000)

            val store = InMemoryWalletStore()
            val other = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            assertIs<RestoreResult.Restored>(other.restore(phone.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
            assertIs<OnboardResult.Ready>(other.onboard(name, HOST))
            other.switchAccount(store.accounts.first().single { it.account.index == 1L }.account.id)
            assertTrue(other.needsAddress())

            assertTrue(other.abortRestore())
            assertEquals(listOf(0L), store.accounts.first().map { it.account.index })
            assertEquals(phone.wallet.recoveryPhrase(), other.recoveryPhrase())
        }

    @Test
    fun `an address of a further account is paid to that account alone`() =
        runBlocking<Unit> {
            Regtest.requireStack(MintDialect.Current)
            val phone = phone()
            val name = Regtest.name()
            assertIs<OnboardResult.Ready>(phone.wallet.onboard(name, HOST, "Savings", inActiveWallet = true))
            assertEquals(AddressStatus.Ours, phone.wallet.checkAddress(name, HOST))
            Regtest.pay("$name@$HOST", 1000)
            phone.wallet.refresh()
            assertEquals(mapOf(0L to 0L, 1L to 999_000L), phone.wallet.balances())
        }

    @Test
    fun `legacy 2f17578 - a further account can't get an address on a legacy mint, whose one key is the first account's`() =
        runBlocking<Unit> {
            Regtest.requireStack(MintDialect.Legacy)
            val phone = phone()
            assertEquals(OnboardResult.MintTooOld, phone.wallet.onboard(Regtest.name(), HOST, "Savings", inActiveWallet = true))
            assertEquals(1, phone.store.accounts.first().size)
            assertTrue(phone.store.currentAccountId() != null)
        }
}
