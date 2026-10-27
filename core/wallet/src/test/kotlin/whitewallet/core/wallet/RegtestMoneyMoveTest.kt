package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach

/** Moving money between the regtest mints, where it can't go through. */
class RegtestMoneyMoveTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `between two mints on one Lightning node the move fails cleanly, and the money stays`() = runBlocking<Unit> {
        // The third mint signs with its node's key: the same key as the first mint means the same node.
        if (Regtest.mintKey(Regtest.HOST3) != Regtest.mintKey(Regtest.HOST)) Regtest.rotateMint3()
        assertEquals(Regtest.mintKey(Regtest.HOST), Regtest.mintKey(Regtest.HOST3))
        val phone = Regtest.phone()
        phone.fund(3000)
        val before = phone.notes()

        // The quote can't tell: it asks each mint, not the node.
        val line = assertNotNull(phone.wallet.previewMove(Regtest.HOST3)).lines.single()
        assertIs<MovePreview.Quote.Ok>(line.quote)

        // The node won't pay itself: nothing is lost, and trying again fails the same way.
        repeat(2) {
            assertEquals(MintMoveResult.Failed(NoteProblem.PaymentFailed), phone.wallet.moveMoney(Regtest.HOST3, listOf(line)).single().result)
            assertEquals(before.map { it.amountMsat }, phone.notes().map { it.amountMsat })
            assertEquals(emptyList(), phone.notes(Regtest.HOST3))
        }
    }

    @Test
    fun `back and forth between two homes, the money goes along each time even while the app refreshes`() = runBlocking<Unit> {
        val phone = Regtest.phone(strategy = SingleNoteStrategy.ID, collectDefaults = CollectDefaults(offered = false, always = true))
        phone.fund(3000)
        val name = phone.address.substringBefore("@")
        listOf(Regtest.HOST2, Regtest.HOST, Regtest.HOST2, Regtest.HOST).forEach { to ->
            val lines = assertNotNull(phone.wallet.previewMove(to)).lines
            // A refresh waiting for the lock (the app's background sync) must not get between the change and the move.
            val change = async { phone.wallet.changeHome(to, name, lines) }
            val refresh = async { phone.wallet.refresh() }
            val changed = change.await()
            refresh.await()
            assertIs<OnboardResult.Ready>(changed.result)
            assertIs<MintMoveResult.Moved>(changed.moved.single().result, "to $to")
            assertEquals(1, phone.notes(to).size)
        }
    }
}
