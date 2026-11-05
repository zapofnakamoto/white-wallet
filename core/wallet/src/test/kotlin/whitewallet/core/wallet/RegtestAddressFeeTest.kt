package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST5
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.MintDialect

/** An address on a mint that charges for one (LUD-26): held until the fee is paid, from outside or from the account's notes. */
class RegtestAddressFeeTest {
    private val fee = 21_000L

    @BeforeEach fun requireStack() = Regtest.requireStack(MintDialect.Current)

    private fun wallet(
        store: InMemoryWalletStore = InMemoryWalletStore(),
        maxFee: Long? = null,
        now: () -> Long = System::currentTimeMillis,
    ) =
        Wallet(
            store,
            InMemorySeedStore(),
            LnurlcashClient(),
            now = now,
            requireAddress = false,
            maxAddressFeeMsat = maxFee,
            policies = NotePolicies.InMemory(ManualStrategy.ID),
        )

    @Test
    fun `a held address is registered once its invoice is paid, becomes the primary one, and gets paid`() =
        runBlocking<Unit> {
            val store = InMemoryWalletStore()
            val wallet = wallet(store)
            assertEquals(fee, wallet.addressFeeMsat(HOST5))
            val name = Regtest.name()
            val held = assertIs<OnboardResult.AwaitingFee>(wallet.onboard(name, HOST5))
            assertEquals(fee, held.amountMsat)
            assertNull(wallet.profile.first(), "no address until the fee is paid")
            wallet.refresh()
            assertNotNull(store.addresses(wallet.activeId()).first().single().feeInvoice, "still held")

            // Asked again before it is paid: the same hold, not a second one.
            assertEquals(held.address, assertIs<OnboardResult.AwaitingFee>(wallet.onboard(name, HOST5)).address)

            Regtest.pay(held.address.feeInvoice!!, 0)
            Regtest.untilRegistered(name, HOST5)
            wallet.refresh()
            assertEquals("$name@$HOST5", wallet.profile.first()?.address)
            assertNull(store.addresses(wallet.activeId()).first().single().feeInvoice)
            assertEquals(AddressStatus.Ours, wallet.checkAddress(name, HOST5))

            Regtest.pay("$name@$HOST5", 1000)
            assertEquals(999_000, wallet.refresh().receivedMsat)
        }

    @Test
    fun `the fee is paid from the account's notes on that mint`() =
        runBlocking<Unit> {
            val store = InMemoryWalletStore()
            val wallet = wallet(store)
            assertIs<CreateAccountResult.Created>(wallet.createAccount(HOST5, "Main"))
            val invoice = assertIs<InvoiceRequest.Ready>(wallet.requestInvoice(2_000_000))
            Regtest.pay(invoice.invoice, 0)
            assertIs<InvoiceStatus.Paid>(wallet.invoiceStatus(invoice.id))
            val before = wallet.balanceMsat.first()

            val name = Regtest.name()
            val held = assertIs<OnboardResult.AwaitingFee>(wallet.addAddress(name, limit = 1))
            assertIs<PayResult.Paid>(assertNotNull(wallet.payAddressFee(held.address)))
            assertEquals("$name@$HOST5", wallet.profile.first()?.address)
            assertNull(store.addresses(wallet.activeId()).first().single().feeInvoice)
            assertTrue(wallet.balanceMsat.first() <= before - fee, "the fee came out of the balance")
        }

    @Test
    fun `a fee above the app's limit is refused, and nothing is kept`() =
        runBlocking<Unit> {
            val store = InMemoryWalletStore()
            val wallet = wallet(store, maxFee = 20_000)
            assertEquals(OnboardResult.FeeRefused(fee, 20_000), wallet.onboard(Regtest.name(), HOST5))
            assertEquals(emptyList(), store.accounts.first())
        }

    @Test
    fun `a hold that expired unpaid is dropped, and the wallet can register a name again`() =
        runBlocking<Unit> {
            var clock = System.currentTimeMillis()
            val store = InMemoryWalletStore()
            val wallet = wallet(store, now = { clock })
            val held = assertIs<OnboardResult.AwaitingFee>(wallet.onboard(Regtest.name(), HOST5))
            clock += 2 * 60 * 60_000L
            wallet.refresh()
            assertEquals(emptyList(), store.addresses(wallet.activeId()).first(), "${held.address.username} is gone")
        }
}
