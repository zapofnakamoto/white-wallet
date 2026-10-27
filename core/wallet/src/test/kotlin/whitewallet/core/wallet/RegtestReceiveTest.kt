package whitewallet.core.wallet

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.JournalStatus
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.client.LnurlcashClient

/** Money coming in: by invoice, and at the address, on the real mint. */
class RegtestReceiveTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `an account without an address receives by invoice, minted to its own key`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID), requireAddress = false)
        assertIs<CreateAccountResult.Created>(wallet.createAccount(HOST, "Main"))
        val request = assertIs<InvoiceRequest.Ready>(wallet.requestInvoice(2_000_000))
        assertEquals(1_999_000, request.expectedMsat, "less the mint's 1 sat fee")
        assertEquals(InvoiceStatus.Waiting, wallet.invoiceStatus(request.id))

        Regtest.pay(request.invoice, 2000)
        var status = wallet.invoiceStatus(request.id)
        repeat(20) {
            if (status !is InvoiceStatus.Waiting) return@repeat
            delay(500)
            status = wallet.invoiceStatus(request.id)
        }
        assertEquals(InvoiceStatus.Paid(1_999_000), status)
        assertEquals(1_999_000, wallet.balanceMsat.first())
        val note = store.allNotes().single()
        assertEquals(0, note.purpose, "a wallet key, not an address key")
        assertEquals(true, note.certificate != null)

        // One row for it, however often the wallet looks again.
        wallet.refresh()
        wallet.refresh()
        assertEquals(listOf(ActivityKind.Received to 1_999_000L), wallet.activity.first().map { it.kind to it.amountMsat })
    }

    @Test
    fun `an invoice paid while the app was away is picked up by the next refresh`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID), requireAddress = false)
        assertIs<CreateAccountResult.Created>(wallet.createAccount(HOST, "Main"))
        val request = assertIs<InvoiceRequest.Ready>(wallet.requestInvoice(3_000_000))
        Regtest.pay(request.invoice, 3000)

        // No one asks for the invoice's status: the refresh on the next start settles the journaled mint.
        var balance = 0L
        repeat(20) {
            if (balance > 0) return@repeat
            wallet.refresh()
            balance = wallet.balanceMsat.first()
            if (balance == 0L) delay(500)
        }
        assertEquals(2_999_000, balance)
        assertEquals(0, wallet.unresolvedOperations())
        assertEquals(listOf(ActivityKind.Received to 2_999_000L), wallet.activity.first().map { it.kind to it.amountMsat })
        assertEquals(InvoiceStatus.Paid(2_999_000), wallet.invoiceStatus(request.id))
    }

    @Test
    fun `an invoice nobody paid expires, and leaves nothing open`() = runBlocking<Unit> {
        var clock = System.currentTimeMillis()
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), now = { clock },
            policies = NotePolicies.InMemory(ManualStrategy.ID), requireAddress = false)
        assertIs<CreateAccountResult.Created>(wallet.createAccount(HOST, "Main"))
        suspend fun open() = store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown)).size
        val request = assertIs<InvoiceRequest.Ready>(wallet.requestInvoice(1_000_000))
        wallet.refresh()
        assertEquals(InvoiceStatus.Waiting, wallet.invoiceStatus(request.id))
        assertEquals(1, open(), "still waiting for its payment")

        // Past its expiry, and the margin for a payment still on its way.
        clock = request.expiresAtMillis + 11 * 60_000
        wallet.refresh()
        assertEquals(0, open())
        assertEquals(InvoiceStatus.Expired, wallet.invoiceStatus(request.id))
        assertEquals(emptyList(), wallet.activity.first())
        assertEquals(0, wallet.balanceMsat.first())
    }

    @Test
    fun `each address payment is one row, and a re-scan adds none`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(1000, 2000)
        assertEquals(0, alice.wallet.refresh().newNotes)
        assertEquals(listOf(999_000L, 1_999_000L), alice.wallet.activity.first().filter { it.kind == ActivityKind.Received }.map { it.amountMsat }.sorted())
    }

    @Test
    fun `an address payment beyond the gap is found by scanning further, and receiving goes on after it`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        // Every invoice the address hands out takes the next address key, paid or not: 25 unpaid ones leave the next
        // payment past the 20-key gap a refresh looks at.
        val name = alice.address.substringBefore('@')
        val callback = Regex("\"callback\":\\s*\"([^\"]+)\"").find(URI("http://$HOST/.well-known/lnurlp/$name").toURL().readText())!!.groupValues[1]
        repeat(25) { URI("$callback${if ('?' in callback) '&' else '?'}amount=1000000").toURL().readText() }
        Regtest.pay(alice.address, 1000)
        assertEquals(0, alice.wallet.refresh().newNotes, "out of the refresh's reach")

        val scan = alice.wallet.scanKeysAhead(HOST, 2, gap = 40)
        assertEquals(1, scan.imported)
        assertEquals(999_000, alice.balance())

        Regtest.pay(alice.address, 2000)
        assertEquals(1_999_000, alice.wallet.refresh().receivedMsat, "the next payment is in reach again")
    }
}
