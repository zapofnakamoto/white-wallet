package whitewallet.core.wallet

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A note's spend leaves the wallet only in the mutation that spends it, at its own mint (LUD-25 "Checking a note
 * without exposing it"): every flow that talks to a mint, watched on the wire.
 */
class RegtestSpendDisclosureTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `no flow sends a spend except in a mutation to the note's own mint`() = runBlocking<Unit> {
        val spends = mutableListOf<HttpUrl>()
        val client = Regtest.client { chain ->
            chain.request().url.takeIf { url -> url.queryParameterNames.any { it.equals("k1", ignoreCase = true) } }?.let(spends::add)
            chain.proceed(chain.request())
        }
        val alice = Regtest.phone(client = client)
        val wallet = alice.wallet

        alice.fund(3000, 2000)
        wallet.recheckNotes()
        wallet.scanKeysAhead(HOST, 0, gap = 3)
        wallet.recheckAllKeys(HOST, 0, gap = 3)
        assertIs<NoteActionResult.Done>(wallet.combineNotes(alice.notes().map { it.id }))
        assertIs<NoteActionResult.Done>(wallet.splitNote(alice.notes().single().id, 1_000_000))
        assertIs<RotateResult.Rotated>(wallet.rotateNote(alice.notes().first().id))
        val invoice = Regtest.invoice(500)
        assertIs<PayResult.Paid>(wallet.pay(assertIs<PayPreview.Ready>(wallet.previewPayment(invoice)), 500_000))
        val shared = assertIs<CreateNoteResult.Created>(wallet.createNote(500_000)).note
        assertEquals(ReclaimResult.Reclaimed(500_000), wallet.reclaimNote(shared.id))
        assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(Regtest.note(1000)))))
        assertIs<MintMoveResult.Moved>(wallet.moveToMint(alice.notes().map { it.id }, HOST2))
        wallet.refresh()

        // The same phrase on another phone, restored and refreshed.
        val phone2 = Wallet(InMemoryWalletStore(), InMemorySeedStore(), client)
        assertIs<RestoreResult.Restored>(phone2.restore(wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
        phone2.refresh()

        assertTrue(spends.isNotEmpty())
        val stray = spends.filterNot { it.isMutation && "${it.host}:${it.port}" in setOf(HOST, HOST2) }
        assertEquals(emptyList(), stray.map { it.newBuilder().query(null).build() }, "requests that carried a spend outside a mutation")
    }
}
