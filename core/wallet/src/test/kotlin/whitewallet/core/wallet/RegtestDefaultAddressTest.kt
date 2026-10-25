package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.lnurlcash.client.LnurlcashClient

/** Seed-derived default addresses against the reference mint. Needs the stack from dev/regtest and LNURLCASH_REGTEST=1. */
class RegtestDefaultAddressTest {
    private val host = "localhost:8111"

    @BeforeEach
    fun requireStack() = assumeTrue(System.getenv("LNURLCASH_REGTEST") == "1", "LNURLCASH_REGTEST not set")

    @Test
    fun `the suggested name comes from the phrase the account gets, and a restore finds it again`() = runBlocking<Unit> {
        run {
            val first = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            val name = assertNotNull(first.defaultUsername(host))
            assertEquals(name, first.defaultUsername(host), "stable until an account is made")
            assertIs<OnboardResult.Ready>(first.onboard(name, host, "Main"))
            assertEquals(emptyList(), first.addressesToWriteDown())
            assertNotEquals(name, first.defaultUsername(host), "the next account gets a new phrase")
            val words = first.recoveryPhrase()

            val restored = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            assertIs<RestoreResult.Restored>(restored.restore(words.joinToString(" "), host, "Restored"))
            assertEquals(name, restored.recoverableUsername(host))
            assertIs<OnboardResult.Ready>(restored.onboard(name, host, null))
            assertEquals("$name@${host}", restored.profile.first()?.address)
        }
    }

    @Test
    fun `a taken derived name offers the next one, and other names are to be written down`() = runBlocking<Unit> {
        run {
            val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            val name = assertNotNull(wallet.defaultUsername(host))
            // Someone else registers it first.
            val other = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            assertIs<OnboardResult.Ready>(other.onboard(name, host, "Other"))

            val taken = assertIs<OnboardResult.UsernameTaken>(wallet.onboard(name, host, "Main"))
            assertEquals(name, wallet.defaultUsername(host), "the phrase is kept for the next attempt")
            val second = taken.suggestions.first()
            assertIs<OnboardResult.Ready>(wallet.onboard(second, host, "Main"))
            assertEquals(second, wallet.recoverableUsername(host))

            val handpicked = "picked" + System.nanoTime().toString(16).takeLast(8)
            assertIs<OnboardResult.Ready>(wallet.addAddress(handpicked, 2))
            assertEquals(listOf("$handpicked@$host"), wallet.addressesToWriteDown())
        }
    }
}
