package whitewallet.feature.onboarding

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.AmountUnit
import whitewallet.core.model.SetupStep
import whitewallet.core.wallet.AddressFeeChoice
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.CreateAccountResult
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.InvoiceRequest
import whitewallet.core.wallet.InvoiceStatus
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.NotePolicies
import whitewallet.core.wallet.RestoreResult
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.NetworkConditions

/**
 * A wallet's setup on a mint that charges for an address (dev/regtest's fifth mint, 21 sats), as the screens run it: the add-mint and the
 * address view models over a real [Wallet]. The wallet's own part is in core/wallet's RegtestAddressFeeTest; the screens' looks in
 * [AddressFeeScreenTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegtestAddressFeeFlowTest {
    private val host = "localhost:8116"
    private val fee = 21_000L

    @BeforeTest
    fun requireStack() {
        assumeTrue(System.getenv("LNURLCASH_REGTEST") == "1", "LNURLCASH_REGTEST not set")
        assumeTrue(System.getenv("REGTEST_PROTOCOL") != "legacy", "lnurl-mint 0.12 has no address fee")
        Dispatchers.setMain(Dispatchers.Default)
    }

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    /** One phone: its stores, its wallet, and the brand's settings its screens follow. */
    private inner class Phone(
        choice: AddressFeeChoice = AddressFeeChoice.Ask,
        maxFee: Long? = null,
        val seeds: InMemorySeedStore = InMemorySeedStore(),
    ) {
        val store = InMemoryWalletStore()
        val wallet =
            Wallet(store, seeds, LnurlcashClient(), maxAddressFeeMsat = maxFee, policies = NotePolicies.InMemory(ManualStrategy.ID))
        val defaults = AppDefaults(listOf(host), allowMintChange = true, addressFee = choice)
        val preferences = Preferences()

        /** The setup's mint step, up to where the address step opens: what the mint says an address costs, as shown before adding it. */
        suspend fun addMint(): Long? {
            val mint = AddMintViewModel(wallet, defaults, preferences)
            mint.init(setup = true)
            mint.state.await { it.step == AddMintStep.Info || it.mintOptions.isNotEmpty() }
            if (mint.state.value.step == AddMintStep.Pick) mint.next()
            val info = mint.state.await { it.step == AddMintStep.Info }
            mint.add()
            mint.state.await { it.step == AddMintStep.Result }
            assertNull(mint.state.value.failed)
            mint.done()
            assertEquals(host, assertIs<AddMintFinish.Address>(mint.state.await { it.finish != null }.finish).host)
            assertEquals(SetupStep.Address, wallet.setupStep.first())
            return info.preview?.addressFeeMsat
        }

        /** The address screen, once its form is ready: the name checked and the mint's fee known. */
        suspend fun addressScreen(mode: OnboardingMode = OnboardingMode.Setup): OnboardingViewModel {
            val screen = OnboardingViewModel(wallet, defaults, preferences)
            screen.init(mode, host.takeIf { mode == OnboardingMode.Setup })
            screen.state.await { it.mode == mode && it.availability == Availability.Available && it.feeMsat != null }
            return screen
        }

        suspend fun held() = wallet.addresses.first().map { it.address }.filter { it.feeInvoice != null }
    }

    private suspend fun newPhone(choice: AddressFeeChoice = AddressFeeChoice.Ask, maxFee: Long? = null): Phone =
        Phone(choice, maxFee).also { assertIs<CreateAccountResult.Created>(it.wallet.createWallet("Main")) }

    @Test
    fun `the fee shows before the mint is added and on the form, and a reserved name is registered once its invoice is paid`() =
        runBlocking<Unit> {
            val phone = newPhone()
            assertEquals(fee, phone.addMint(), "what the mint's page says an address costs")

            val screen = phone.addressScreen()
            assertEquals(fee, screen.state.value.feeToShow, "said on the form, before anything is asked for")
            val name = screen.state.value.username

            screen.start()
            assertEquals(fee, screen.state.await { it.feePrompt != null }.feePrompt)
            assertEquals(emptyList(), phone.held(), "nothing is reserved before the user agrees")

            screen.acceptFee()
            val held = assertNotNull(screen.state.await { it.held != null }.held)
            assertEquals(fee, held.amountMsat)
            assertEquals("$name@$host", held.address.address)
            assertFalse(held.canPayFromBalance, "a new wallet holds nothing to pay with")
            assertNull(phone.wallet.primaryAddress.first(), "no address until the fee is paid")

            // Paid from another wallet while the screen shows the invoice: it notices by itself.
            pay(assertNotNull(held.address.feeInvoice))
            val claimed = assertNotNull(screen.state.await { it.claimed != null }.claimed)
            assertEquals("$name@$host", claimed.address)
            assertTrue(claimed.confirmed)
            assertNull(screen.state.value.held)

            screen.onContinue()
            screen.state.await { it.done }
            assertNull(phone.wallet.setupStep.first(), "the setup is over")
            assertEquals("$name@$host", phone.wallet.primaryAddress.first()?.address)
            assertEquals(emptyList(), phone.held())
        }

    @Test
    fun `declining the fee ends the setup without an address, and nothing is reserved`() =
        runBlocking<Unit> {
            val phone = newPhone()
            phone.addMint()
            val screen = phone.addressScreen()
            screen.start()
            screen.state.await { it.feePrompt != null }

            screen.declineFee()
            screen.state.await { it.done }
            assertNull(screen.state.value.feePrompt)
            assertNull(phone.wallet.setupStep.first())
            assertNull(phone.wallet.primaryAddress.first())
            assertEquals(emptyList(), phone.wallet.addresses.first())
        }

    @Test
    fun `pay later ends the setup with the name reserved, and paying it afterwards registers it`() =
        runBlocking<Unit> {
            val phone = newPhone()
            phone.addMint()
            val screen = phone.addressScreen()
            screen.start()
            screen.state.await { it.feePrompt != null }
            screen.acceptFee()
            val held = assertNotNull(screen.state.await { it.held != null }.held)

            screen.onContinue()
            screen.state.await { it.done }
            assertNull(phone.wallet.setupStep.first())
            assertNull(phone.wallet.primaryAddress.first())
            // What Settings lists as reserved, with its invoice.
            assertEquals(listOf(held.address), phone.wallet.addresses.first().map { it.address })

            pay(assertNotNull(held.address.feeInvoice))
            withTimeout(30_000) {
                while (phone.wallet.primaryAddress.first() == null) {
                    delay(500)
                    phone.wallet.refresh()
                }
            }
            assertEquals(held.address.address, phone.wallet.primaryAddress.first()?.address)
            assertEquals(emptyList(), phone.held())
        }

    @Test
    fun `a brand that reserves without asking goes straight to the invoice`() =
        runBlocking<Unit> {
            val phone = newPhone(AddressFeeChoice.Reserve)
            phone.addMint()
            val screen = phone.addressScreen()
            assertEquals(fee, screen.state.value.feeToShow, "the form still says what it costs")

            screen.start()
            val state = screen.state.await { it.held != null || it.feePrompt != null || it.error != null }
            assertNull(state.feePrompt)
            assertEquals(fee, state.held?.amountMsat)
        }

    @Test
    fun `a brand that skips paid addresses ends the setup without one`() =
        runBlocking<Unit> {
            val phone = newPhone(AddressFeeChoice.Skip)
            phone.addMint()
            val screen = phone.addressScreen()

            screen.start()
            val state = screen.state.await { it.done || it.feePrompt != null || it.held != null || it.error != null }
            assertTrue(state.done)
            assertNull(state.feePrompt)
            assertNull(phone.wallet.setupStep.first())
            assertEquals(emptyList(), phone.wallet.addresses.first())
        }

    @Test
    fun `from settings the fee is always asked first, and an account that holds enough pays it from its balance`() =
        runBlocking<Unit> {
            // A brand that reserves without asking in the setup: Settings asks all the same.
            val phone = newPhone(AddressFeeChoice.Reserve)
            phone.addMint()
            val setup = phone.addressScreen()
            setup.skipAddress()
            setup.state.await { it.done }
            val invoice = assertIs<InvoiceRequest.Ready>(phone.wallet.requestInvoice(2_000_000))
            pay(invoice.invoice)
            assertIs<InvoiceStatus.Paid>(phone.wallet.invoiceStatus(invoice.id))
            val before = phone.wallet.balanceMsat.first()

            val screen = phone.addressScreen(OnboardingMode.AddAddress)
            assertEquals(fee, screen.state.value.feeToShow)
            val name = screen.state.value.username
            screen.start()
            assertEquals(fee, screen.state.await { it.feePrompt != null || it.held != null }.feePrompt)

            // Declined from Settings: back on the form, nothing reserved.
            screen.declineFee()
            assertNull(screen.state.value.feePrompt)
            assertFalse(screen.state.value.done)
            assertEquals(emptyList(), phone.held())

            screen.start()
            screen.state.await { it.feePrompt != null }
            screen.acceptFee()
            val held = assertNotNull(screen.state.await { it.held != null }.held)
            assertTrue(held.canPayFromBalance)

            screen.payFee()
            val claimed = assertNotNull(screen.state.await { it.claimed != null || it.error != null }.claimed)
            assertEquals("$name@$host", claimed.address)
            assertEquals(emptyList(), phone.held())
            assertTrue(phone.wallet.balanceMsat.first() <= before - fee, "the fee came out of the balance")
        }

    @Test
    fun `a fee above what the app accepts is refused with both amounts, and nothing is reserved`() =
        runBlocking<Unit> {
            val phone = newPhone(maxFee = 20_000)
            phone.addMint()
            val screen = phone.addressScreen()
            screen.start()
            screen.state.await { it.feePrompt != null }
            screen.acceptFee()

            val state = screen.state.await { it.error != null || it.held != null }
            assertEquals(OnboardingError.FeeTooHigh(fee, 20_000), state.error)
            assertNull(state.held)
            assertEquals(emptyList(), phone.wallet.addresses.first())
        }

    @Test
    fun `a restored wallet takes its paid address back by itself, without a fee, whatever the brand does about fees`() =
        runBlocking<Unit> {
            val first = newPhone()
            first.addMint()
            val paidFor = first.addressScreen()
            val name = paidFor.state.value.username
            paidFor.start()
            paidFor.state.await { it.feePrompt != null }
            paidFor.acceptFee()
            pay(assertNotNull(paidFor.state.await { it.held != null }.held?.address?.feeInvoice))
            paidFor.state.await { it.claimed != null }
            val words = assertNotNull(first.seeds.load(first.store.accounts.first().single().account.walletId))

            // Whatever the brand does about a fee: there is none for a name that is the wallet's already.
            for (choice in AddressFeeChoice.entries) {
                val second = Phone(choice)
                assertIs<RestoreResult.Restored>(second.wallet.restore(words.joinToString(" "), null, "Main"))
                second.addMint()
                val screen = OnboardingViewModel(second.wallet, second.defaults, second.preferences)
                screen.init(OnboardingMode.Setup, host)

                val state =
                    screen.state.await { it.claimed != null || it.done || it.feePrompt != null || it.held != null || it.error != null }
                assertEquals("$name@$host", state.claimed?.address, "$choice: $state")
                assertNull(state.feePrompt)
                assertNull(state.held)
                assertNull(state.feeToShow, "a name that is the wallet's already costs nothing again")
                assertEquals("$name@$host", second.wallet.primaryAddress.first()?.address)
                assertEquals(emptyList(), second.held())
            }
        }

    /** Waits until the screen's state is as [expected]; says what it was if that never comes. */
    private suspend fun <T> StateFlow<T>.await(expected: (T) -> Boolean): T {
        repeat(600) {
            if (expected(value)) return value
            delay(100)
        }
        error("the screen never got there: $value")
    }

    /** Pays [invoice] from the stack's other Lightning node, as another wallet would. */
    private fun pay(invoice: String) {
        val script = File("../../dev/regtest/regtest.sh").canonicalFile
        val process = ProcessBuilder(script.path, "pay", invoice, "0").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }

    private class Preferences : AppPreferences {
        override val developerMode = MutableStateFlow(false)
        override val developerOptionsUnlocked = MutableStateFlow(false)
        override val maxAddressesPerMint = MutableStateFlow<Int?>(null)
        override val networkProfile = MutableStateFlow(NetworkConditions.Profile.Normal)
        override val amountUnit = MutableStateFlow<AmountUnit?>(null)
        override val balanceHidden = MutableStateFlow(false)
        override val appLock = MutableStateFlow(false)
        override val notesSort = MutableStateFlow<String?>(null)
        override val seenReleaseNotes: Flow<Set<String>?> = MutableStateFlow(null)

        override suspend fun setDeveloperMode(enabled: Boolean) = Unit

        override suspend fun setDeveloperOptionsUnlocked() = Unit

        override suspend fun setMaxAddressesPerMint(max: Int) = Unit

        override suspend fun setNetworkProfile(profile: NetworkConditions.Profile) = Unit

        override suspend fun setAmountUnit(unit: AmountUnit) = Unit

        override suspend fun setBalanceHidden(hidden: Boolean) = Unit

        override suspend fun setAppLock(enabled: Boolean) = Unit

        override suspend fun setNotesSort(sort: String) = Unit

        override suspend fun setSeenReleaseNotes(ids: Set<String>) = Unit
    }
}
