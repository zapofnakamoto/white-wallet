package whitewallet.core.wallet

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.spendDomain

/** Several accounts `S/a'` under one recovery phrase: their own money and addresses, backed up, restored and deleted as one wallet. */
class RegtestWalletAccountsTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    private fun restored(store: InMemoryWalletStore = InMemoryWalletStore()) =
        Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))

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
    fun `giving up a restore's setup removes the wallet it brought, and its phrase can come back again`() =
        runBlocking<Unit> {
            val phone = phone()
            val name = phone.address.substringBefore('@')
            secondAccount(phone, 1000)

            val store = InMemoryWalletStore()
            val other = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            assertIs<RestoreResult.Restored>(other.restore(phone.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
            assertIs<OnboardResult.Ready>(other.onboard(name, HOST))
            assertTrue(other.inSetup(), "until the address step is done")
            assertEquals(listOf(0L, 1L), store.accounts.first().map { it.account.index }.sorted())

            // A restored wallet has its phrase written down: it can always go, whatever it holds.
            assertEquals(AbandonResult.Abandoned(remains = false), other.abandonSetup())
            assertTrue(store.accounts.first().isEmpty())
            assertIs<RestoreResult.Restored>(other.restore(phone.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
            assertEquals(listOf(0L, 1L), store.accounts.first().map { it.account.index }.sorted())
        }

    @Test
    fun `a wallet of three accounts with two addresses each comes back from its phrase, and each address goes on being paid to its account`() =
        runBlocking<Unit> {
            Regtest.requireStack(MintDialect.Current)
            val phone = phone()
            val names = mutableListOf(phone.address.substringBefore('@'))
            suspend fun second() {
                names += Regtest.name()
                assertIs<OnboardResult.Ready>(phone.wallet.addAddress(names.last(), limit = 5))
            }
            second()
            for (account in listOf("Savings", "Spare")) {
                names += Regtest.name()
                assertIs<OnboardResult.Ready>(phone.wallet.onboard(names.last(), HOST, account, inActiveWallet = true))
                second()
            }
            names.forEachIndexed { i, name -> Regtest.pay("$name@$HOST", 1000L * (i + 1)) }

            val store = InMemoryWalletStore()
            val other = restored(store)
            assertIs<RestoreResult.Restored>(other.restore(phone.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
            val paid = mapOf(0L to 999_000L + 1_999_000, 1L to 2_999_000L + 3_999_000, 2L to 4_999_000L + 5_999_000)
            assertEquals(paid, other.balances())
            val ids = store.accounts.first().associate { it.account.index to it.account.id }
            assertEquals(
                mapOf(0L to 2L, 1L to 2L, 2L to 2L),
                ids.mapValues { store.keyIndexOrNull(it.value, spendDomain(HOST), KeyCounter.Shares) },
            )

            // Paid again before the phone knows any of the names: each lands in its own account.
            names.forEach { Regtest.pay("$it@$HOST", 1000) }
            for (id in ids.values) {
                other.switchAccount(id)
                other.refresh()
            }
            assertEquals(paid.mapValues { it.value + 2 * 999_000 }, other.balances())

            // The account proves a name it held, on the share it was on.
            other.switchAccount(ids.getValue(1))
            assertTrue(assertIs<OnboardResult.Ready>(other.onboard(names[3], HOST)).confirmed)
            assertEquals(1L, store.addresses(ids.getValue(1)).first().single { it.username == names[3] }.share)
        }

    @Test
    fun `two accounts of one wallet collect at different home mints, each only its own notes`() =
        runBlocking<Unit> {
            val phone = phone()
            val main = phone.wallet.activeId()
            suspend fun claim(second: Boolean) =
                assertIs<ClaimResult.Claimed>(
                    phone.wallet.claim(assertIs<ClaimPreview.Ready>(phone.wallet.previewClaim(Regtest.note(3000, second = second))))
                )
            // Main (home: the first mint) doesn't collect: a note claimed on the other mint stays there.
            claim(second = true)
            assertEquals(1, phone.notes(HOST2).size)

            // Savings starts with Main's home, moves it to the other mint and collects there.
            assertIs<CreateAccountResult.Created>(phone.wallet.createAccount(HOST, "Savings", inActiveWallet = true))
            val savings = phone.wallet.activeId()
            assertEquals(HOST, phone.wallet.homeHost.first(), "a new account starts with the home of the account in use")
            assertIs<ConnectResult.Connected>(phone.wallet.setHomeMint(HOST2).result)
            phone.wallet.setCollectAtHome(true)
            claim(second = false)
            assertEquals(emptyList(), phone.notes(HOST), "Savings' note went to its home")
            assertTrue(phone.notes(HOST2).sumOf { it.amountMsat } > 2_990_000, "there, less the move's fees: ${phone.notes(HOST2)}")
            assertEquals(1, phone.store.notes(main).first().count { it.host == HOST2 && it.state == NoteState.Outstanding })

            // Main's settings are its own: still home on the first mint, still not collecting, its note where it was.
            phone.wallet.switchAccount(main)
            assertEquals(HOST, phone.wallet.homeHost.first())
            assertEquals(false, phone.wallet.collectAtHome.first())
            phone.wallet.refresh()
            assertEquals(1, phone.notes(HOST2).size)

            // Collecting for Main moves its note the other way, to its own home; Savings' money stays on the other mint.
            phone.wallet.setCollectAtHome(true)
            assertEquals(emptyList(), phone.notes(HOST2))
            assertTrue(phone.notes(HOST).sumOf { it.amountMsat } > 2_990_000, "home, less the move's fees: ${phone.notes(HOST)}")
            val ofSavings = phone.store.notes(savings).first().filter { it.state == NoteState.Outstanding }
            assertEquals(setOf(HOST2), ofSavings.map { it.host }.toSet())
            assertTrue(phone.wallet.mints.first().filter { it.host in setOf(HOST, HOST2) }.all { it.trusted }, "both homes are trusted")
        }

    @Test
    fun `an account that holds nothing is removed, and one added again takes its index and finds what was paid there since`() =
        runBlocking<Unit> {
            val store = InMemoryWalletStore()
            val seeds = InMemorySeedStore()
            val wallet = Wallet(store, seeds, LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            val address = Regtest.name().let { name -> "$name@$HOST".also { assertIs<OnboardResult.Ready>(wallet.onboard(name, HOST)) } }
            val phone = Regtest.Phone(store, wallet, address)
            val first = wallet.activeId()
            assertEquals(RemoveAccountResult.LastAccount, wallet.removeAccount(), "a wallet keeps one account")
            secondAccount(phone, 3000)
            val second = wallet.activeId()

            // Money keeps an account on the phone.
            assertEquals(AccountRemoval(RemoveAccountResult.InUse(AccountUse.Money), 2_999_000, emptyList()), wallet.accountRemoval())
            assertEquals(RemoveAccountResult.InUse(AccountUse.Money), wallet.removeAccount())
            assertEquals(listOf(0L, 1L), store.accounts.first().map { it.account.index }.sorted())

            // The first one holds nothing: it goes, the address it leaves is named, and the other one is in use.
            wallet.switchAccount(first)
            assertEquals(AccountRemoval(null, 0, listOf(address)), wallet.accountRemoval())
            assertEquals(RemoveAccountResult.Removed(second), wallet.removeAccount())
            assertEquals(second, wallet.activeId())
            assertEquals(mapOf(1L to 2_999_000L), wallet.balances())

            // Its address is still registered at the mint and is paid; the account left doesn't see that money.
            Regtest.pay(address, 2000)
            wallet.refresh()
            assertEquals(mapOf(1L to 2_999_000L), wallet.balances())

            // While the mint doesn't answer no account is added: an account that looks empty would hide that money.
            val offline =
                Wallet(
                    store,
                    seeds,
                    Regtest.client { throw IOException("the mint is down") },
                    policies = NotePolicies.InMemory(ManualStrategy.ID),
                )
            assertEquals(HOST, assertIs<CreateAccountResult.ScanFailed>(offline.createAccount("Again")).host)
            assertEquals(second, store.currentAccountId())
            assertEquals(listOf(1L), store.accounts.first().map { it.account.index })

            // The next account takes the free index, is looked for at the mint, and has what was paid to the address.
            var progress: ScanProgress.AccountScan? = null
            val again = assertIs<CreateAccountResult.Created>(wallet.createAccount("Again") { progress = it.accounts.single() }).accountId
            assertEquals(again, wallet.activeId())
            assertEquals(0L, store.account(again)?.index)
            assertEquals(mapOf(0L to 1_999_000L, 1L to 2_999_000L), wallet.balances())
            assertEquals(true to 1_999_000L, progress?.let { it.done to it.foundMsat })
            wallet.refresh()
            assertEquals(mapOf(0L to 1_999_000L, 1L to 2_999_000L), wallet.balances(), "counted once")
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
