package whitewallet.core.wallet

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2

/** A mint that doesn't answer: the wallet goes on with the others, knows which one is silent, and pays from another if it can. */
class RegtestMintDownTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    /** The mints whose requests are dropped before they go out, by host. */
    private val down = mutableSetOf<String>()
    /** Every mint a request went to (or was meant to), in order. */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val connection = MutableStateFlow(Connection.Online)
    private val client = Regtest.client { chain ->
        val url = chain.request().url
        val host = "${url.host}:${url.port}"
        asked += host
        if (host in down) Regtest.dropped()
        chain.proceed(chain.request())
    }

    private suspend fun phone() = Regtest.phone(client = client, connection = connection)

    /** [phone] with a 3000 sat note on the second mint, claimed there. */
    private suspend fun Regtest.Phone.withNoteOnSecondMint() {
        wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(Regtest.note(3000, second = true))))
        assertEquals(1, notes(HOST2).size)
    }

    private suspend fun Regtest.Phone.silent() = wallet.mintsNotAnswering.first()

    /** An invoice the second mint can pay: of mint-lnd, as the second mint runs on user-lnd (and LND can't pay its own invoices). */
    private fun invoiceForSecondMint(sats: Long): String {
        val out = Regtest.lncliMint("addinvoice", "--amt", "$sats", "--memo", "regtest test")
        return checkNotNull(Regex("\"payment_request\":\\s*\"([^\"]+)\"").find(out)) { out }.groupValues[1]
    }

    @Test
    fun `a mint that doesn't answer is known after a refresh, which goes on with the others`() =
        runBlocking<Unit> {
            val alice = phone()
            alice.withNoteOnSecondMint()
            down += HOST2
            Regtest.pay(alice.address, 2000)
            alice.wallet.refresh()
            assertEquals(1, alice.notes(HOST).size, "the payment to the address on the home mint came in")
            assertEquals(setOf(HOST2), alice.silent())
            assertEquals(
                mapOf(HOST to true, HOST2 to false),
                alice.wallet.accountMints.first().associate { it.host to it.answering },
            )

            down.clear()
            alice.wallet.refresh()
            assertEquals(emptySet(), alice.silent(), "it answers again")
        }

    @Test
    fun `a received note's mint that doesn't answer is known, also one the account holds nothing on, and answers again`() =
        runBlocking<Unit> {
            val bob = phone()
            down += HOST2
            val preview = assertIs<ClaimPreview.Offline>(bob.wallet.previewClaim(Regtest.note(1000, second = true)))
            bob.wallet.keepClaim(preview)
            assertEquals(ReceivedState.Waiting, bob.wallet.receivedNotes.first().single().state)
            assertEquals(setOf(HOST2), bob.silent(), "its claim got no answer")

            down.clear()
            bob.wallet.refresh()
            assertEquals(ReceivedState.Claimed, bob.wallet.receivedNotes.first().single().state)
            assertEquals(emptySet(), bob.silent(), "it answered the claim")
        }

    @Test
    fun `the home mint that doesn't answer is known after a refresh`() =
        runBlocking<Unit> {
            val alice = phone()
            down += HOST
            alice.wallet.refresh()
            assertEquals(setOf(HOST), alice.silent())
            assertFalse(alice.wallet.accountMints.first().single { it.isHome }.answering)
        }

    @Test
    fun `with the phone offline a mint's silence says nothing about the mint`() =
        runBlocking<Unit> {
            val alice = phone()
            connection.value = Connection.Offline
            down += HOST
            alice.wallet.refresh()
            assertEquals(emptySet(), alice.silent())
        }

    @Test
    fun `over a network not known to reach the internet a mint's silence says nothing either`() =
        runBlocking<Unit> {
            val alice = phone()
            connection.value = Connection.Unverified
            down += HOST
            alice.wallet.refresh()
            assertEquals(emptySet(), alice.silent())
        }

    @Test
    fun `a payment the home mint doesn't answer for is paid from the next mint that can`() =
        runBlocking<Unit> {
            val alice = phone()
            alice.fund(2000)
            alice.withNoteOnSecondMint()
            val home = alice.notes(HOST)
            val preview = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(invoiceForSecondMint(1000)))
            down += HOST
            assertIs<PayResult.Paid>(alice.wallet.pay(preview, 1_000_000))
            assertEquals(home, alice.notes(HOST), "nothing changed on the home mint")
            assertTrue(alice.notes(HOST2).sumOf { it.amountMsat } < 2_000_000, "paid from the second mint")
            assertEquals(setOf(HOST), alice.silent())
        }

    @Test
    fun `a mint that didn't answer the last time is asked last`() =
        runBlocking<Unit> {
            val alice = phone()
            alice.fund(2000)
            alice.withNoteOnSecondMint()
            down += HOST
            alice.wallet.refresh()
            val home = alice.notes(HOST)
            down.clear()
            // Back, but the wallet doesn't know yet: the second mint, which did answer, pays without the home mint being asked.
            val preview = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(invoiceForSecondMint(1000)))
            asked.clear()
            assertIs<PayResult.Paid>(alice.wallet.pay(preview, 1_000_000))
            assertEquals(home, alice.notes(HOST))
            assertFalse(HOST in asked)
        }

    @Test
    fun `with no other mint that can pay, the one that didn't answer is named and nothing is paid`() =
        runBlocking<Unit> {
            val alice = phone()
            alice.fund(2000)
            val preview = assertIs<PayPreview.Ready>(alice.wallet.previewPayment(Regtest.invoice(1000)))
            down += HOST
            assertEquals(PayResult.Failed(PayProblem.MintNotAnswering, host = HOST), alice.wallet.pay(preview, 1_000_000))
            assertEquals(1_999_000, alice.balance())

            connection.value = Connection.Offline
            assertEquals(PayResult.Failed(PayProblem.Unreachable), alice.wallet.pay(preview, 1_000_000), "offline: the phone, not the mint")
        }

    @Test
    fun `an invoice the home mint doesn't answer for marks it as silent`() =
        runBlocking<Unit> {
            val alice = phone()
            down += HOST
            assertEquals(InvoiceRequest.Unreachable, alice.wallet.requestInvoice(1_000_000))
            assertEquals(setOf(HOST), alice.silent())
            down.clear()
            assertIs<InvoiceRequest.Ready>(alice.wallet.requestInvoice(1_000_000))
            assertEquals(emptySet(), alice.silent())
        }
}
