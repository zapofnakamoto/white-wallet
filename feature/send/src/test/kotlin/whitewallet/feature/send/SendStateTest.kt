package whitewallet.feature.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whitewallet.core.wallet.PayProblem
import whitewallet.core.wallet.PayResult

class SendStateTest {
    private val unasked = SendState(input = "lnurlp://payee.example/p", problem = PayProblem.Unreachable)

    @Test
    fun aLinkThatCouldntBeAskedWaitsForTheConnection() {
        assertTrue(unasked.waitsForConnection)
    }

    @Test
    fun nothingElseWaits() {
        assertFalse(unasked.copy(problem = PayProblem.NotRecognized).waitsForConnection)
        assertFalse(unasked.copy(input = "").waitsForConnection)
        assertFalse(unasked.copy(result = PayResult.Failed(PayProblem.Unreachable)).waitsForConnection)
    }

    @Test
    fun theMintsToPayFromAreHomeFirstThenByNameWithAChangedKeyLast() {
        val picked =
            inPickerOrder(
                listOf(
                    SendMint("c.example", false, 9_000),
                    SendMint("stuck.example", false, 5_000, keyChanged = true),
                    SendMint("a.example", false, 1_000),
                    SendMint("home.example", true, 2_000),
                )
            )
        assertEquals(listOf("home.example", "a.example", "c.example", "stuck.example"), picked.map { it.host })
    }
}
