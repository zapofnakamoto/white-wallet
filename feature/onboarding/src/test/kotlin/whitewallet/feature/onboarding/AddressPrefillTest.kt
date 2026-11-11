package whitewallet.feature.onboarding

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import whitewallet.core.model.AmountUnit
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.CreateAccountResult
import whitewallet.core.wallet.InMemorySeedStore
import whitewallet.core.wallet.InMemoryWalletStore
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.NotePolicies
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.NetworkConditions

/**
 * What the address form starts with: the mint and the name are there before it shows, and never change by themselves afterwards. Runs
 * without any mint: one that doesn't answer is the slow case, where a form that fills in later would show something else for longest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressPrefillTest {
    /** Nothing listens here: every request fails at once. */
    private val host = "127.0.0.1:9"
    private val other = "127.0.0.1:19"

    private val store = InMemoryWalletStore()
    private val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
    private val defaults = AppDefaults(listOf(host, other), allowMintChange = true)

    @BeforeTest fun setMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun resetMain() = Dispatchers.resetMain()

    /** Every state the screen showed until the form was free to use: before [OnboardingState.ready] there is nothing to see. */
    private suspend fun shown(mode: OnboardingMode, presetHost: String? = null): List<OnboardingState> {
        val screen = OnboardingViewModel(wallet, defaults, Preferences())
        val seen = mutableListOf<OnboardingState>()
        val watching = CoroutineScope(Dispatchers.Unconfined).launch { screen.state.collect { if (it.ready) seen += it } }
        screen.init(mode, presetHost)
        repeat(300) {
            val now = screen.state.value
            if (now.ready && !now.busy && now.availability != Availability.Checking && now.availability != Availability.Idle) {
                watching.cancel()
                return seen.toList()
            }
            delay(100)
        }
        error("the form never got ready: ${screen.state.value}")
    }

    @Test
    fun `the setup's form shows the name derived from the phrase from the start, locked until the mint was asked`() =
        runBlocking<Unit> {
            assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
            val derived = assertNotNull(wallet.defaultUsername(host))

            val seen = shown(OnboardingMode.Setup, host)
            assertEquals(setOf(derived), seen.map { it.username }.toSet(), "no other name, not for a moment")
            assertEquals(setOf(host), seen.map { it.host }.toSet(), "no other mint")
            assertTrue(seen.first().busy, "locked while the mint is asked whether the name is the wallet's")
            assertEquals(Availability.Checking, seen.first().availability)
            // A mint that doesn't answer: the form unlocks all the same.
            assertFalse(seen.last().busy)
            assertEquals(Availability.Unknown, seen.last().availability)
        }

    @Test
    fun `an address from settings starts with one suggested name on the home mint`() =
        runBlocking<Unit> {
            assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
            store.saveAccount(store.accounts.first().single().account.copy(homeHost = other))
            for (mode in listOf(OnboardingMode.AddAddress, OnboardingMode.ChangeAddress)) {
                val seen = shown(mode)
                assertEquals(1, seen.map { it.username }.toSet().size, "$mode: one name, set once")
                assertTrue(seen.first().username.isNotBlank())
                assertEquals(setOf(other), seen.map { it.host }.toSet(), "$mode: the home mint, not the brand's first")
                assertFalse(seen.first().busy, "nothing comes back by itself here, so the field is free from the start")
            }
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
        override val seenReleaseNotes = MutableStateFlow<Set<String>?>(null)

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
