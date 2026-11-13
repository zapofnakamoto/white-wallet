package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.Share

/**
 * What an address gives away on the real mint: nothing about the account's own notes. Its chains don't derive from a share's `cx1` (see
 * NoteKeysTest), the most anyone could learn of an address.
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
            // No mint publishes a cx1; the mint itself holds the share's.
            val share = assertNotNull(Share.fromCx1(phone.wallet.keys(phone.wallet.activeId(), HOST).share(0).cx1()))
            val visible = (0L until 40L).map { share.notePubkey(it).toHex() }.toSet()
            assertEquals(emptyList(), own.filter { it.id in visible }, "notes an outsider can find")
        }
}
