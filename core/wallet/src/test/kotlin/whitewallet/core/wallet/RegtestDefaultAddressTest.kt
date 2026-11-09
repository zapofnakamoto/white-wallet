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
    @BeforeEach fun requireStack() = Regtest.requireStack()

    @Test
    fun `the suggested name comes from the wallet's phrase, and a restore finds it again`() =
        runBlocking<Unit> {
            val first = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            assertEquals(null, first.defaultUsername(HOST), "no wallet, no phrase to derive from")
            val main = assertIs<CreateAccountResult.Created>(first.createWallet("Main")).accountId
            val name = assertNotNull(first.defaultUsername(HOST))
            assertEquals(name, first.defaultUsername(HOST), "the same phrase, the same name")
            assertIs<OnboardResult.Ready>(first.claimAddress(name, HOST))
            first.setSetupStep(null)
            assertEquals(emptyList(), first.addressesToWriteDown())
            assertIs<CreateAccountResult.Created>(first.createWallet("Other"))
            assertNotEquals(name, first.defaultUsername(HOST), "another wallet, another phrase")
            first.switchAccount(main)
            val words = first.recoveryPhrase()

            val restored = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            assertIs<RestoreResult.Restored>(restored.restore(words.joinToString(" "), HOST, "Restored"))
            assertEquals(name, restored.recoverableUsername(HOST))
            assertIs<OnboardResult.Ready>(restored.claimAddress(name, HOST))
            assertEquals("$name@$HOST", restored.primaryAddress.first()?.address)
        }

    @Test
    fun `a taken derived name offers the next one, and other names are to be written down`() =
        runBlocking<Unit> {
            val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            assertIs<CreateAccountResult.Created>(wallet.createWallet("Main"))
            val name = assertNotNull(wallet.defaultUsername(HOST))
            // Someone else registers it first.
            val other = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            assertIs<OnboardResult.Ready>(other.onboard(name, HOST, "Other"))

            val taken = assertIs<OnboardResult.UsernameTaken>(wallet.claimAddress(name, HOST))
            val second = taken.suggestions.first()
            assertIs<OnboardResult.Ready>(wallet.claimAddress(second, HOST))
            assertEquals(second, wallet.recoverableUsername(HOST))

            val handpicked = "picked" + System.nanoTime().toString(16).takeLast(8)
            assertIs<OnboardResult.Ready>(wallet.addAddress(handpicked, 2))
            assertEquals(listOf("$handpicked@$HOST"), wallet.addressesToWriteDown())
        }
}
