package whitewallet.feature.receive

import org.junit.Assert.assertEquals
import org.junit.Test
import whitewallet.core.wallet.InvoiceRequest
import whitewallet.core.wallet.InvoiceStatus
import whitewallet.core.wallet.RefreshResult

/** How the payments that come to the address while Receive is open add up, and what the screen takes from them. */
class ArrivalsTest {
    private fun refresh(payments: Int, msat: Long) = RefreshResult(payments, msat)

    @Test
    fun `nothing has arrived before a refresh finds something`() {
        assertEquals(Arrivals(), Arrivals() + refresh(0, 0))
        assertEquals(null, ReceiveState("alice@mint.example").arrivedMsat)
    }

    @Test
    fun `one payment is one`() {
        val arrivals = Arrivals() + refresh(1, 21_000)
        assertEquals(Arrivals(1, 21_000), arrivals)
        val state = ReceiveState("alice@mint.example", receivedMsat = arrivals.msat, receivedPayments = arrivals.payments)
        assertEquals(1, state.arrivedPayments)
        assertEquals(21_000L, state.arrivedMsat)
    }

    @Test
    fun `two found by one refresh are two, and their total`() {
        val arrivals = Arrivals() + refresh(2, 3_000)
        val state = ReceiveState("alice@mint.example", receivedMsat = arrivals.msat, receivedPayments = arrivals.payments)
        assertEquals(2, state.arrivedPayments)
        assertEquals(3_000L, state.arrivedMsat)
    }

    @Test
    fun `one found, then another while the success screen is up, are two`() {
        val first = Arrivals() + refresh(1, 1_000)
        val both = first + refresh(0, 0) + refresh(1, 2_000)
        assertEquals(Arrivals(2, 3_000), both)
    }

    @Test
    fun `money without a count still counts as a payment`() {
        assertEquals(Arrivals(1, 5_000), Arrivals() + refresh(0, 5_000))
    }

    @Test
    fun `a paid invoice is always one payment`() {
        val request = InvoiceRequest.Ready("i", "lnbcrt21u1test", 21_000, 20_000, 0)
        // Whatever arrived at an address before is not the invoice's.
        val state =
            ReceiveState(
                receivedMsat = 9_000,
                receivedPayments = 3,
                invoice = ShownInvoice(request, InvoiceStatus.Paid(20_000)),
            )
        assertEquals(1, state.arrivedPayments)
        assertEquals(20_000L, state.arrivedMsat)
    }
}
