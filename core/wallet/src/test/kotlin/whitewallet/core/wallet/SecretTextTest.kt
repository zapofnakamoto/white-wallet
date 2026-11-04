@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.OperationType
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.Requests

/** Debug text (`toString`, so logs, crash reports, failed assertions) shows a note's key, never its secret. */
class SecretTextTest {
    private val domain = "mint.example"
    private val keyPath = Spend.keyPath(PrivateKey(ByteArray(32) { 7 }), domain)
    private val bearer = Spend.Bearer(ByteVector32(ByteArray(32) { 9 }))
    private val bearerCw1 = bearer.copy(asCw1 = true)
    private val script = LockedNote.until(1_700_000_000).spend(domain)
    private val spends: List<Spend> = listOf(keyPath, bearer, bearerCw1, script)

    /** The parts of [spend] that are secret, as they could show up in text. */
    private fun secrets(spend: Spend): List<String> =
        listOf(spend.k1) +
            when (spend) {
                is Spend.KeyPath -> listOf(spend.signature.toHex())
                is Spend.Bearer -> listOf(spend.preimage.toHex())
                is Spend.Script -> spend.cw1.witness.map { it.toHex() }
            }

    private fun assertHidden(text: String, spend: Spend) {
        assertTrue(spend.q.toHex() in text, "the key is shown: $text")
        for (secret in secrets(spend)) assertFalse(secret in text, "a secret in: $text")
    }

    @Test
    fun `a spend, its link and a preview show the key, not the secret`() {
        val info = NoteInfo("https://$domain/cb", 1_000, PublicKey(PrivateKey(ByteArray(32) { 3 }).publicKey().value), null)
        for (spend in spends) {
            assertHidden(spend.toString(), spend)
            val link = NoteLink("https://$domain/w", spend, 1_000, null)
            assertHidden(link.toString(), spend)
            assertHidden(ClaimPreview.Ready(link, info, 1_000, certified = false, isOwnMint = false, trusted = false).toString(), spend)
            assertHidden(ClaimPreview.Offline(link, 1_000, null).toString(), spend)
        }
        assertFalse(script.cw1.witness.first().toHex() in script.cw1.toString())
    }

    @Test
    fun `a received note, a journal entry and a shared note show their keys, not their spends`() {
        for (spend in spends) {
            val received =
                ReceivedNote(
                    spend.q.toHex(),
                    "acc",
                    domain,
                    "https://$domain/w",
                    spend.k1,
                    null,
                    null,
                    1_000,
                    null,
                    null,
                    ReceivedState.Waiting,
                    null,
                    1,
                    1,
                )
            assertHidden(received.toString(), spend)
            assertHidden(ClaimResult.Kept(received).toString(), spend)

            val url = Requests.rotate("https://$domain/cb", spend.k1, keyPath.q)
            val entry =
                JournalEntry(
                    "j",
                    "acc",
                    OperationType.Rotate,
                    domain,
                    "https://$domain/w",
                    listOf(spend.q.toHex()),
                    listOf(spend.k1),
                    emptyList(),
                    url,
                    JournalStatus.Prepared,
                    null,
                    1,
                    1,
                )
            assertHidden(entry.toString(), spend)

            val link = NoteLink("https://$domain/w", spend, 1_000, null).encode()
            val shared = SharedNote(spend.q.toHex(), domain, 1_000, false, SharedNoteState.Waiting, 1, link)
            assertHidden(shared.toString(), spend)
            assertEquals(link, shared.link, "the link itself is still the whole note")
        }
    }
}
