package whitewallet.core.wallet

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.spendDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Notes locked by time (LUD-25 "Timelocks"), on the real mint, received through the wallet's claim. The wallet makes
 * none: a note of its own is renewed to one ([lockTo]), and its link is opened like any other.
 */
class RegtestTimelockTest {
    @BeforeEach
    fun requireStack() = Regtest.requireStack()

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    @Test
    fun `a note whose lock has passed is claimed like any other, in either order of its script`() = runBlocking<Unit> {
        for (reordered in listOf(false, true)) {
            val alice = Regtest.phone()
            alice.fund(2000)
            val locked = LockedNote.until(nowSeconds() - 3600, reordered)
            alice.lockTo(locked.q)
            assertEquals(0, alice.balance())

            val preview = assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(locked.link(HOST, alice.withdrawUrl())), "reordered: $reordered")
            assertEquals(1_999_000, preview.amountMsat)
            assertEquals(false, preview.hasOtherPaths)
            assertEquals(ClaimResult.Claimed(1_999_000, true), alice.wallet.claim(preview))
            assertEquals(1_999_000, alice.balance(), "renewing is free")
            assertEquals(ReceivedState.Claimed, alice.wallet.receivedNotes.first().single().state)
            assertEquals(0, alice.wallet.incomingMsat.first())
        }
    }

    @Test
    fun `a note locked until tomorrow is kept, counted as incoming, and stays locked`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val until = nowSeconds() + 86_400
        val locked = LockedNote.until(until)
        alice.lockTo(locked.q)

        // The mint refuses the spend, and names the lock; nothing is burned.
        val refusal = assertFailsWith<NoteLookupError> { alice.wallet.client.noteInfo(alice.withdrawUrl(), locked.spend(spendDomain(HOST)).k1) }
        assertTrue("in the future" in refusal.message.orEmpty(), "the mint names the unmet lock: ${refusal.message}")

        val preview = assertIs<ClaimPreview.Locked>(alice.wallet.previewClaim(locked.link(HOST, alice.withdrawUrl())))
        assertEquals(ClaimPreview.Locked(preview.link, until * 1000, null, 1_999_000), preview, "what it's worth, asked by its key")
        val kept = alice.wallet.keepClaim(preview).note
        assertEquals(ReceivedState.Locked, kept.state)
        assertEquals(1_999_000, alice.wallet.incomingMsat.first())
        assertEquals(0, alice.balance())

        alice.wallet.refresh()
        assertEquals(ReceivedState.Locked, alice.wallet.receivedNotes.first().single().state, "not tried before the date")
        assertEquals(1_999_000, alice.wallet.client.lookup(alice.withdrawUrl(), locked.q).maxWithdrawableMsat)
    }

    @Test
    fun `a kept note is claimed by the first refresh after its lock passes`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val until = nowSeconds() + 10
        val locked = LockedNote.until(until)
        alice.lockTo(locked.q)
        val preview = assertIs<ClaimPreview.Locked>(alice.wallet.previewClaim(locked.link(HOST, alice.withdrawUrl())))
        alice.wallet.keepClaim(preview)

        while (nowSeconds() <= until + 1) delay(500)
        alice.wallet.refresh()
        assertEquals(ReceivedState.Claimed, alice.wallet.receivedNotes.first().single().state)
        assertEquals(1_999_000, alice.balance())
        assertEquals(0, alice.wallet.incomingMsat.first())
    }

    @Test
    fun `a note locked for a while after the mint credits it is known as locked by the mint's refusal`() = runBlocking<Unit> {
        val alice = Regtest.phone()
        alice.fund(2000)
        val locked = LockedNote.after(1)
        alice.lockTo(locked.q)

        val preview = assertIs<ClaimPreview.Locked>(alice.wallet.previewClaim(locked.link(HOST, alice.withdrawUrl())))
        assertEquals(ClaimPreview.Locked(preview.link, null, 512, 1_999_000), preview)
        alice.wallet.keepClaim(preview)
        // Only the mint knows when the wait started: asked on every refresh, it still refuses.
        alice.wallet.refresh()
        assertEquals(ReceivedState.Locked, alice.wallet.receivedNotes.first().single().state)
        assertEquals(0, alice.balance())
    }

    private suspend fun Regtest.Phone.withdrawUrl(): String = assertNotNull(store.mint(HOST)).withdrawUrl
}
