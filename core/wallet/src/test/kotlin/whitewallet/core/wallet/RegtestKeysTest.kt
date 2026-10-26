package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.client.LnurlcashClient

/** The developer key tools (Settings → Developer options → keys), looking keys up on the real mint. */
class RegtestKeysTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `the key tools find the index, refuse a used one, and see what the same phrase did on another phone`() = runBlocking<Unit> {
        // Phone 1: an address note (purpose 2, key 0) and two claims (wallet keys 0 and 1).
        val phone1 = Regtest.phone()
        phone1.fund(1000)
        repeat(2) { assertIs<ClaimResult.Claimed>(phone1.wallet.claim(assertIs<ClaimPreview.Ready>(phone1.wallet.previewClaim(Regtest.note(2000))))) }
        val account = phone1.store.currentAccountId()!!

        // With the wallet counter forced back to 0, finding the index looks the keys up and lands past the used ones.
        phone1.store.setIndex(account, HOST, 0, 0)
        assertEquals(2, phone1.wallet.findKeyIndex(HOST, 0).next)
        assertEquals(SetIndexResult.BelowFloor(2), phone1.wallet.setKeyIndex(HOST, 0, 1))
        assertIs<SetIndexResult.Applied>(phone1.wallet.setKeyIndex(HOST, 0, 5))

        // Phone 2 (the same phrase) renews wallet key 0's note onto its next key.
        val phone2 = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        assertIs<RestoreResult.Restored>(phone2.restore(phone1.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
        val renewed = assertIs<RotateResult.Rotated>(phone2.rotateKey(HOST, 0, 0))
        val before = phone1.balance()

        // Phone 1 sees key 0 spent, and a recheck of all wallet keys picks the note up at its new key: the same money.
        assertIs<KeyProbe.Spent>(phone1.wallet.recheckKey(HOST, 0, 0))
        assertEquals(before - 1_999_000, phone1.balance())
        assertEquals(1, phone1.wallet.recheckAllKeys(HOST, 0).imported, "the note at wallet key ${renewed.toIndex}")
        assertEquals(before, phone1.balance())

        // Rotating by hand from the key page shows as manual note maintenance.
        assertIs<RotateResult.Rotated>(phone1.wallet.rotateKey(HOST, 2, 0))
        val row = phone1.wallet.activity.first().single { it.kind == ActivityKind.Rotated }
        assertEquals(false, row.automatic)
        assertEquals(before, phone1.balance())
    }
}
