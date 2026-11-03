package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.ReceivedState
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.NoteLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
    fun `a script note whose spend doesn't check out is not spendable, and the mint is never asked`() = runBlocking<Unit> {
        account()
        val good = LockedNote.until(1_700_000_000).spend("other.example")
        assertEquals(ClaimPreview.NotSpendable, wallet.previewClaim(NoteLink(withdrawUrl, good, null, null).encode()), "signed for another mint")
        val tampered = assertIs<Spend.Script>(Spend.parse(good.cw1.copy(lockTime = 1_700_000_001).encode()))
        assertEquals(ClaimPreview.NotSpendable, wallet.previewClaim(NoteLink(withdrawUrl, tampered, null, null).encode()))
    }

    @Test
    fun `a note opened offline can be kept, and asks for a sync once the device is online`() = runBlocking<Unit> {
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
    fun `a note locked until a date ahead is locked without asking the mint for its spend, and kept as locked`() = runBlocking<Unit> {
        account()
        val until = System.currentTimeMillis() / 1000 + 86_400
        val preview = assertIs<ClaimPreview.Locked>(wallet.previewClaim(LockedNote.until(until).link(host, withdrawUrl, declaredMsat = 7_000)))
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
}
