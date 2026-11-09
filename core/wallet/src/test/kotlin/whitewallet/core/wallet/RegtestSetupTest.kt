package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.SetupStep
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.MintDialect

/** A wallet's setup the way the app goes through it, one step per screen, against real mints. */
class RegtestSetupTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    private fun wallet(store: InMemoryWalletStore = InMemoryWalletStore()) =
        Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))

    /** The mint step: what it shows, then adding it with that key. */
    private suspend fun Wallet.addShownMint(host: String, progress: MutableList<ScanProgress> = mutableListOf()): MintPreview.Ready {
        val preview = assertIs<MintPreview.Ready>(previewMint(host))
        assertIs<ConnectResult.Connected>(addMint(host, preview.signingKey) { progress += it })
        return preview
    }

    @Test
    fun `a created wallet adds its mint without a scan, gets its derived address and is paid there`() =
        runBlocking<Unit> {
            val store = InMemoryWalletStore()
            val wallet = wallet(store)
            assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
            assertTrue(store.mintMap.value.isEmpty(), "creating contacts no mint")

            val progress = mutableListOf<ScanProgress>()
            wallet.addShownMint(HOST, progress)
            assertTrue(progress.isEmpty(), "a wallet made here holds nothing anywhere yet")
            assertEquals(HOST, wallet.homeHost.first())

            wallet.setSetupStep(SetupStep.Address)
            val name = assertNotNull(wallet.defaultUsername(HOST))
            assertIs<OnboardResult.Ready>(wallet.claimAddress(name, HOST))
            wallet.setSetupStep(null)
            assertTrue(wallet.isSetUp())

            Regtest.pay("$name@$HOST", 1000)
            wallet.refresh()
            assertEquals(999_000L, wallet.balanceMsat.first())
        }

    @Test
    fun `a restore adds the mint, finds both accounts and their money while showing it, and takes the address back by itself`() =
        runBlocking<Unit> {
            // Set up as the app does: the address is the one derived from the phrase.
            val firstStore = InMemoryWalletStore()
            val first = wallet(firstStore)
            assertIs<CreateAccountResult.Created>(first.createWallet("Main"))
            first.addShownMint(HOST)
            val derived = assertNotNull(first.defaultUsername(HOST))
            assertIs<OnboardResult.Ready>(first.claimAddress(derived, HOST))
            first.setSetupStep(null)
            val phone = Regtest.Phone(firstStore, first, "$derived@$HOST")
            phone.fund(2000)
            assertIs<CreateAccountResult.Created>(phone.wallet.createAccount("Savings"))
            val invoice = assertIs<InvoiceRequest.Ready>(phone.wallet.requestInvoice(3_000_000))
            Regtest.pay(invoice.invoice, 0)
            assertIs<InvoiceStatus.Paid>(phone.wallet.invoiceStatus(invoice.id))

            val store = InMemoryWalletStore()
            val other = wallet(store)
            assertIs<RestoreResult.Restored>(other.restore(phone.wallet.recoveryPhrase().joinToString(" "), null, "Main"))
            assertEquals(SetupStep.Mint, other.setupStep.first())

            val progress = mutableListOf<ScanProgress>()
            other.addShownMint(HOST, progress)
            val last = progress.last().accounts
            assertTrue(last.any { it.index == 0L && it.foundMsat == 1_999_000L }, "$last")
            assertTrue(last.any { it.index == 1L && it.foundMsat == 2_999_000L }, "$last")
            assertEquals(listOf(1_999_000L, 2_999_000L), other.heldAt(HOST).sortedBy { it.first.index }.map { it.second })

            // The address step: the name the phrase had there comes back without asking.
            assertEquals(0L, other.activeAccount.first()?.account?.index)
            val name = phone.address.substringBefore('@')
            assertEquals(name, other.recoverableUsername(HOST))
            assertTrue(assertIs<OnboardResult.Ready>(other.claimAddress(name, HOST)).confirmed)
            other.setSetupStep(null)

            Regtest.pay(phone.address, 1000)
            other.refresh()
            assertEquals(2_998_000L, other.balanceMsat.first())
        }

    @Test
    fun `an added account receives at once, without contacting a mint first`() =
        runBlocking<Unit> {
            val phone = Regtest.phone()
            val mints = phone.store.mintMap.value
            assertIs<CreateAccountResult.Created>(phone.wallet.createAccount("Savings"))
            assertEquals(mints, phone.store.mintMap.value)
            val invoice = assertIs<InvoiceRequest.Ready>(phone.wallet.requestInvoice(1_000_000))
            Regtest.pay(invoice.invoice, 0)
            assertIs<InvoiceStatus.Paid>(phone.wallet.invoiceStatus(invoice.id))
            assertEquals(999_000L, phone.balance())
        }

    @Test
    fun `what a mint shows about itself before it is added, on either protocol, saves nothing`() =
        runBlocking<Unit> {
            val store = InMemoryWalletStore()
            val wallet = wallet(store)
            assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
            val preview = assertIs<MintPreview.Ready>(wallet.previewMint(HOST))
            assertEquals(Regtest.protocol == MintDialect.Legacy, preview.legacy)
            assertEquals(Regtest.mintKey(HOST), preview.signingKey)
            assertTrue(preview.maxSendableMsat >= preview.minSendableMsat)
            assertTrue(store.mintMap.value.isEmpty())
            assertEquals(null, wallet.homeHost.first())
        }
}
