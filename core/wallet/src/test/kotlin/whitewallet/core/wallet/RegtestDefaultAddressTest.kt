package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.client.LnurlcashClient

/** Seed-derived default addresses against the reference mint. Needs the stack from dev/regtest and LNURLCASH_REGTEST=1. */
class RegtestDefaultAddressTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `the suggested name comes from the phrase the account gets, and a restore finds it again`() = runBlocking<Unit> {
        val first = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        val name = assertNotNull(first.defaultUsername(HOST))
        assertEquals(name, first.defaultUsername(HOST), "stable until an account is made")
        assertIs<OnboardResult.Ready>(first.onboard(name, HOST, "Main"))
        assertEquals(emptyList(), first.addressesToWriteDown())
        assertNotEquals(name, first.defaultUsername(HOST), "the next account gets a new phrase")
        val words = first.recoveryPhrase()

        val restored = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        assertIs<RestoreResult.Restored>(restored.restore(words.joinToString(" "), HOST, "Restored"))
        assertEquals(name, restored.recoverableUsername(HOST))
        assertIs<OnboardResult.Ready>(restored.onboard(name, HOST, null))
        assertEquals("$name@$HOST", restored.profile.first()?.address)
    }

    @Test
    fun `a taken derived name offers the next one, and other names are to be written down`() = runBlocking<Unit> {
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        val name = assertNotNull(wallet.defaultUsername(HOST))
        // Someone else registers it first.
        val other = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
        assertIs<OnboardResult.Ready>(other.onboard(name, HOST, "Other"))

        val taken = assertIs<OnboardResult.UsernameTaken>(wallet.onboard(name, HOST, "Main"))
        assertEquals(name, wallet.defaultUsername(HOST), "the phrase is kept for the next attempt")
        val second = taken.suggestions.first()
        assertIs<OnboardResult.Ready>(wallet.onboard(second, HOST, "Main"))
        assertEquals(second, wallet.recoverableUsername(HOST))

        val handpicked = "picked" + System.nanoTime().toString(16).takeLast(8)
        assertIs<OnboardResult.Ready>(wallet.addAddress(handpicked, 2))
        assertEquals(listOf("$handpicked@$HOST"), wallet.addressesToWriteDown())
    }
}
