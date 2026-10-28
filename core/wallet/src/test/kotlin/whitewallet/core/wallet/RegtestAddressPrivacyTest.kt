package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient

/** What the real mint's published text/cpub tells anyone who looks the address up. */
class RegtestAddressPrivacyTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    @Test
    fun `anyone with the address finds the account's wallet and change notes on that mint, and what they hold`() = runBlocking<Unit> {
        // An address note, two claimed notes on wallet keys, and a payment that leaves change.
        val phone = Regtest.phone()
        phone.fund(2000)
        repeat(2) { assertIs<ClaimResult.Claimed>(phone.wallet.claim(assertIs<ClaimPreview.Ready>(phone.wallet.previewClaim(Regtest.note(3000))))) }
        assertIs<PayResult.Paid>(phone.wallet.pay(assertIs<PayPreview.Ready>(phone.wallet.previewPayment(Regtest.invoice(500))), 500_000))
        val own = phone.notes().filter { it.purpose == NotePurpose.Wallet.value || it.purpose == NotePurpose.Change.value }
        assertEquals(setOf(NotePurpose.Wallet.value, NotePurpose.Change.value), own.map { it.purpose }.toSet(), "$own")

        // An outsider: no seed, only the address. The mint publishes the account's cx1 for it...
        val outsider = LnurlcashClient()
        val pay = assertIs<LnurlResponse.PayRequest>(outsider.get(checkNotNull(Lnurl.lightningAddressUrl(phone.address))))
        val cpub = assertNotNull(pay.metadataEntries.firstOrNull { it.first == "text/cpub" }?.second, "text/cpub")
        val watcher = assertNotNull(CashBranch.fromCx1(cpub.substringBeforeLast(':')))
        val withdrawUrl = outsider.servicePayRequest(HOST).withdrawUrl

        // ...from which they derive the wallet and change keys, and the mint tells them what each note is worth.
        for (note in own) {
            val q = watcher.notePubkey(NotePurpose.entries.first { it.value == note.purpose }, note.index!!)
            assertEquals(note.id, q.toHex(), "purpose ${note.purpose} #${note.index}")
            assertEquals(note.amountMsat, outsider.lookup(withdrawUrl, q).maxWithdrawableMsat, "purpose ${note.purpose} #${note.index}")
        }
    }
}
