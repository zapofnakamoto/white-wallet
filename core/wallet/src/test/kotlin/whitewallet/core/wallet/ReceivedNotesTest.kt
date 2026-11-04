@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.Mint
import whitewallet.core.model.ReceivedState
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.spendDomain

/** What the wallet decides about a received note on its own, before (or without) its mint. */
class ReceivedNotesTest {
    /** Nothing listens there: every request fails as if offline. */
    private val host = "localhost:1"
    private val withdrawUrl = "http://$host/w"
    private val store = InMemoryWalletStore()
    private var syncAsked = 0
    private val wallet = Wallet(store, InMemorySeedStore(), LnurlcashClient(), onWaitingForNetwork = { syncAsked++ })

    private fun account() = runBlocking { store.addAccount("acc", "Main", null, host) }

    @Test
    fun `a script note whose spend doesn't check out is not spendable, and the mint is never asked`() =
        runBlocking<Unit> {
            account()
            val good = LockedNote.until(1_700_000_000).spend("other.example")
            assertEquals(
                ClaimPreview.NotSpendable,
                wallet.previewClaim(NoteLink(withdrawUrl, good, null, null).encode()),
                "signed for another mint",
            )
            val tampered = assertIs<Spend.Script>(Spend.parse(good.cw1.copy(lockTime = 1_700_000_001).encode()))
            assertEquals(ClaimPreview.NotSpendable, wallet.previewClaim(NoteLink(withdrawUrl, tampered, null, null).encode()))
        }

    @Test
    fun `a note opened offline can be kept, and asks for a sync once the device is online`() =
        runBlocking<Unit> {
            account()
            val note = LockedNote.until(1_700_000_000)
            val preview = assertIs<ClaimPreview.Offline>(wallet.previewClaim(note.link(host, withdrawUrl, declaredMsat = 5_000)))
            assertEquals(5_000, preview.declaredMsat)
            assertNull(preview.certifiedMsat, "no certificate, and an unknown mint")
            val kept = wallet.keepClaim(preview).note
            assertEquals(ReceivedState.Waiting, kept.state)
            assertEquals(1, syncAsked)
            assertEquals(5_000, wallet.incomingMsat.first(), "what the link says, until the mint says")
            assertEquals(0, wallet.balanceMsat.first())

            // Kept again (opened twice): one note, still waiting, received when first kept.
            wallet.keepClaim(preview)
            assertEquals(listOf(kept.receivedAtMillis), wallet.receivedNotes.first().map { it.receivedAtMillis })
        }

    @Test
    fun `a note locked until a date ahead is locked without asking the mint for its spend, and kept as locked`() =
        runBlocking<Unit> {
            account()
            val until = System.currentTimeMillis() / 1000 + 86_400
            val preview =
                assertIs<ClaimPreview.Locked>(wallet.previewClaim(LockedNote.until(until).link(host, withdrawUrl, declaredMsat = 7_000)))
            assertEquals(until * 1000, preview.untilMillis)
            assertNull(preview.amountMsat, "the mint couldn't be asked")
            val kept = wallet.keepClaim(preview).note
            assertEquals(ReceivedState.Locked, kept.state)
            assertEquals(until * 1000, kept.lockedUntilMillis)
            assertEquals(0, syncAsked, "nothing to claim before the date")
            assertEquals(7_000, wallet.incomingMsat.first())

            runCatching { wallet.refresh() }
            assertEquals(ReceivedState.Locked, wallet.receivedNotes.first().single().state)
        }

    @Test
    fun `a link whose spend is broken is refused, never a crash`() =
        runBlocking<Unit> {
            account()
            val note = LockedNote.until(1_700_000_000)
            val good = note.spend("localhost:1").cw1
            val broken =
                listOf(
                    good.copy(controlBlock = ByteVector(ByteArray(10))),
                    good.copy(script = ByteVector(ByteArray(300) { 0x51 })),
                    good.copy(witness = emptyList()),
                    good.copy(witness = listOf(ByteVector(ByteArray(0xffff)))),
                )
            for (cw1 in broken) {
                val link = "lnurlw://$host/w?k1=${cw1.encode()}"
                val preview = runCatching {
                    wallet.previewClaim(link)
                }
                    .getOrElse { throw AssertionError("${cw1.controlBlock.size()} / ${cw1.script.size()} threw $it", it) }
                assertTrue(preview is ClaimPreview.Invalid || preview == ClaimPreview.NotSpendable, "$preview")
            }
            assertEquals(emptyList(), wallet.receivedNotes.first())
        }

    @Test
    fun `offline, only a certificate the mint's pinned key signed for this note at its amount counts`() =
        runBlocking<Unit> {
            account()
            val mintKey = PrivateKey(ByteArray(32) { 3 })
            store.saveMint(Mint(host, withdrawUrl, mintKey.publicKey().toHex(), 1_000, 1_000_000_000, null, null))
            val note = LockedNote.until(1_700_000_000)
            val spend = note.spend(spendDomain(host))
            fun certifiedMsat(certificate: String?): Long? = runBlocking {
                val preview = wallet.previewClaim(NoteLink(withdrawUrl, spend, 9_000, certificate).encode())
                assertEquals(9_000, assertIs<ClaimPreview.Offline>(preview).declaredMsat)
                preview.certifiedMsat
            }
            val signed = Certificates.sign(5_000, note.q, mintKey)
            assertEquals(5_000, certifiedMsat(signed.encode()), "what the certificate says, not what the link declares")
            assertNull(certifiedMsat(Cs1(6_000, signed.signature).encode()), "its amount changed")
            assertNull(
                certifiedMsat(Certificates.sign(5_000, LockedNote.until(1_700_000_000).q, mintKey).encode()),
                "signed for another note",
            )
            assertNull(certifiedMsat(Certificates.sign(5_000, note.q, PrivateKey(ByteArray(32) { 4 })).encode()), "signed by another key")
            assertNull(certifiedMsat("cs1notacertificate"))
            assertNull(certifiedMsat(null))
        }
}
