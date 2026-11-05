package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.KeyScheme
import whitewallet.core.wallet.Regtest.HOST4
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.MintDialect

/** A mint moving from lnurl-mint 0.12.2 to 0.13 under wallets that registered on it: what a production upgrade looks like to them. */
class RegtestMintUpgradeTest {
    @BeforeEach fun requireStack() = Regtest.requireStack(MintDialect.Current)

    @Test
    fun `a mint's upgrade moves the wallet's notes and address on by themselves, and a restored phone takes its address back`() =
        runBlocking<Unit> {
            Regtest.resetMint4()
            val alice = Regtest.phone(host = HOST4)
            val bob = Regtest.phone(host = HOST4)
            alice.fund(2000)
            bob.fund(3000)
            val aliceId = alice.wallet.activeId()
            assertEquals(listOf(KeyScheme.Legacy), alice.notes(HOST4).map { it.scheme }, "address notes stay put on a legacy mint")
            assertNull(alice.store.addresses(aliceId).first().single().share)

            Regtest.upgradeMint4()

            // Paid before this wallet has heard of the upgrade: the mint still credits the old keys.
            Regtest.pay(alice.address, 1000)
            alice.wallet.refresh()
            assertEquals(0L, alice.store.addresses(aliceId).first().single().share, "moved to a share")
            assertEquals(1_999_000 + 999_000, alice.balance(), "nothing lost, and the move is free")
            assertEquals(setOf(KeyScheme.Bip32), alice.notes(HOST4).map { it.scheme }.toSet(), "swept: ${alice.notes(HOST4)}")
            assertEquals(AddressStatus.Ours, alice.wallet.checkAddress(alice.address.substringBefore('@'), HOST4))
            assertTrue(alice.wallet.activity.first().none { it.kind == ActivityKind.Rotated }, "silently")

            Regtest.pay(alice.address, 500)
            assertEquals(499_000, alice.wallet.refresh().receivedMsat)
            assertEquals(1, alice.notes(HOST4).count { it.share == 0L })

            // Bob's old phone never came back. Only the branch key on file can prove the name: claiming it moves it.
            val store = InMemoryWalletStore()
            val restored = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            assertIs<RestoreResult.Restored>(restored.restore(bob.wallet.recoveryPhrase().joinToString(" "), HOST4, "Main"))
            val name = bob.address.substringBefore('@')
            assertTrue(assertIs<OnboardResult.Ready>(restored.onboard(name, HOST4)).confirmed)
            assertEquals(0L, store.addresses(restored.activeId()).first().single().share)
            Regtest.pay(bob.address, 1000)
            restored.refresh()
            assertEquals(2_999_000 + 999_000, restored.balanceMsat.first())
        }
}
