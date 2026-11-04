package whitewallet.core.wallet

import kotlin.test.assertIs
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import org.junit.jupiter.api.BeforeEach
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient

/** [RestoreBoundaryTest] against the real reference mint: notes are minted to the chosen keys by paying the mint's invoices for them. */
class RegtestRestoreBoundaryTest : RestoreBoundaryTest() {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    override val host = Regtest.HOST

    /** Asks the mint for an invoice minting to the key (LUD-25 minting), pays it from user-lnd, and waits until the note is there. */
    override suspend fun credit(words: List<String>, purpose: NotePurpose, index: Long, sats: Long): Long {
        val client = client()
        val pay = client.servicePayRequest(host)
        val key = branch(words).notePubkey(purpose, index)
        val asker = Wallet(InMemoryWalletStore(), InMemorySeedStore(), client)
        Regtest.pay(assertIs<MintInvoice.Ok>(MintInvoice.fetch(asker, pay, sats * 1000, key)).invoice, sats)
        repeat(30) {
            val note = runCatching { client.lookup(pay.withdrawUrl, key) }.getOrNull()
            if (note != null) return note.maxWithdrawableMsat
            delay(1_000)
        }
        error("no note on $purpose/$index after paying its invoice")
    }

    override fun client(intercept: Interceptor?): LnurlcashClient = intercept?.let(Regtest::client) ?: LnurlcashClient()
}
