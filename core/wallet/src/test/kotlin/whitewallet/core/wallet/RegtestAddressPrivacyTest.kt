package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.Share
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.legacy.LegacyBranch
import whitewallet.lnurlcash.legacy.LegacyPurpose

/**
 * What the real mint's published text/cpub (legacy 2f17578) tells anyone who looks the address up: the branch `cx1` derives the legacy
 * wallet and change keys too, so notes on them are visible to anyone with the address. Notes on LUD-26 keys are not: the account's chains
 * don't derive from what an address publishes (see NoteKeysTest for a share's `cx1`).
 */
class RegtestAddressPrivacyTest {
    @Test
    fun `the address tells nobody about the account's wallet and change notes`() =
        runBlocking<Unit> {
            Regtest.requireStack()
            // An address note, two claimed notes on wallet keys, and a payment that leaves change.
            val phone = Regtest.phone()
            phone.fund(2000)
            repeat(2) {
                assertIs<ClaimResult.Claimed>(
                    phone.wallet.claim(assertIs<ClaimPreview.Ready>(phone.wallet.previewClaim(Regtest.note(3000))))
                )
            }
            assertIs<PayResult.Paid>(
                phone.wallet.pay(assertIs<PayPreview.Ready>(phone.wallet.previewPayment(Regtest.invoice(500))), 500_000)
            )
            val own = phone.notes().filter { it.purpose == 0 || it.purpose == 1 }
            assertEquals(setOf(KeySet.Wallet, KeySet.Change), own.map { KeySet.of(it) }.toSet(), "$own")

            // An outsider with only the address derives every key its cx1 reaches, well past the indexes used: none is the account's.
            val visible =
                if (Regtest.protocol == MintDialect.Current) {
                    // No mint publishes a cx1 any more; the mint itself holds the share's.
                    val share = assertNotNull(Share.fromCx1(phone.wallet.keys(phone.wallet.activeId(), HOST).share(0).cx1()))
                    (0L until 40L).map { share.notePubkey(it).toHex() }.toSet()
                } else {
                    val watcher = watcher(phone.address)
                    LegacyPurpose.entries.flatMap { p -> (0L until 40L).map { watcher.notePubkey(p, it).toHex() } }.toSet()
                }
            assertEquals(emptyList(), own.filter { it.id in visible }, "notes an outsider can find")
        }

    @Test
    fun `legacy 2f17578 - anyone with the address finds the account's notes on legacy wallet keys, and what they hold`() =
        runBlocking<Unit> {
            Regtest.requireStack(MintDialect.Legacy)
            val phone = Regtest.phone()
            // A note on legacy wallet key #0, as the wallet made them before LUD-26 at 92e430f.
            val client = LnurlcashClient()
            val pay = client.servicePayRequest(HOST)
            val q = phone.wallet.branch(phone.wallet.activeId(), HOST).notePubkey(LegacyPurpose.Wallet, 0)
            Regtest.pay(assertIs<MintInvoice.Ok>(MintInvoice.fetch(phone.wallet, pay, 3_000_000, q)).invoice, 3000)
            var amount: Long? = null
            repeat(30) {
                if (amount == null) amount = runCatching { client.lookup(pay.withdrawUrl, q).maxWithdrawableMsat }.getOrNull()
                if (amount == null) delay(1_000)
            }

            // The mint publishes the account's cx1 for the address, from which an outsider derives the key and asks what it holds.
            val watcher = watcher(phone.address)
            assertEquals(q, watcher.notePubkey(LegacyPurpose.Wallet, 0))
            assertEquals(assertNotNull(amount), LnurlcashClient().lookup(pay.withdrawUrl, q).maxWithdrawableMsat)
        }

    /** What an outsider makes of [address]: the branch its published text/cpub names. */
    private suspend fun watcher(address: String): LegacyBranch {
        val pay = assertIs<LnurlResponse.PayRequest>(LnurlcashClient().get(checkNotNull(Lnurl.lightningAddressUrl(address))))
        val cpub = assertNotNull(pay.metadataEntries.firstOrNull { it.first == "text/cpub" }?.second, "text/cpub")
        return assertNotNull(LegacyBranch.fromCx1(cpub.substringBeforeLast(':')))
    }
}
