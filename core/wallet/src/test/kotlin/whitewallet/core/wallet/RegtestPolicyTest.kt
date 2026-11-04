package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach

/** Note policies against the real reference mint (dev/regtest, LNURLCASH_REGTEST=1). */
class RegtestPolicyTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    @Test
    fun `keeping only one note merges what arrives into one wallet note`() =
        runBlocking<Unit> {
            val alice = Regtest.phone(strategy = SingleNoteStrategy.ID)
            alice.fund(1000, 1000, 2000)
            val single = alice.notes().single()
            assertEquals(0, single.purpose, "on a wallet key, not the address's")
            assertNotNull(single.certificate, "the mint certified the merged note")
            // Each payment arrives less the mint's 1 sat fee.
            val received = (1000L + 1000 + 2000 - 3) * 1000
            assertTrue(single.amountMsat >= received, "a merge never loses value (LUD-25 may refund base fees)")
        }

    @Test
    fun `auto-rotate moves an address note onto a wallet key`() =
        runBlocking<Unit> {
            // Manual keeps notes as they arrive, so only auto-rotate moves them (single would rotate by itself).
            val bob = Regtest.phone(strategy = ManualStrategy.ID)
            bob.fund(1500)
            val incoming = bob.notes().single()
            assertEquals(2, incoming.purpose, "still on the address's key")
            val balance = bob.balance()

            bob.wallet.setAutoRotate(true)
            val rotated = bob.notes().single()
            assertEquals(0, rotated.purpose)
            assertEquals(balance, bob.balance())
        }

    @Test
    fun `auto-rotate leaves wallet and change notes where they are`() =
        runBlocking<Unit> {
            val carol = Regtest.phone(strategy = ManualStrategy.ID)
            carol.fund(3000)
            // A split puts the address note onto a wallet key and a change key.
            assertIs<NoteActionResult.Done>(carol.wallet.splitNote(carol.notes().single().id, 1_000_000))
            val kept = carol.notes().map { it.id }.toSet()
            assertEquals(listOf(0, 1), carol.notes().mapNotNull { it.purpose }.sorted())
            carol.fund(1500)

            carol.wallet.setAutoRotate(true)
            val after = carol.notes()
            assertTrue(after.none { it.purpose == 2 }, "the address note is rotated")
            assertTrue(after.map { it.id }.containsAll(kept), "the wallet and change notes are untouched")
            assertEquals(3, after.size)
            assertEquals(1, assertNotNull(carol.wallet.lastPolicyRun.first()).rotated, "only the address note")
        }
}
