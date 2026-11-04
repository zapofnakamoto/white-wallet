@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.PrivateKey
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain

/**
 * Notes locked by time (LUD-25 "Timelocks"), on the real mint, received through the wallet's claim. The wallet makes none: a note of its
 * own is renewed to one ([lockTo]), and its link is opened like any other.
 */
class RegtestTimelockTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    @Test
    fun `a note whose lock has passed is claimed like any other, in either order of its script`() =
        runBlocking<Unit> {
            for (reordered in listOf(false, true)) {
                val alice = Regtest.phone()
                alice.fund(2000)
                val locked = LockedNote.until(nowSeconds() - 3600, reordered)
                alice.lockTo(locked.q)
                assertEquals(0, alice.balance())

                val preview =
                    assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(locked.link(HOST, alice.withdrawUrl())), "reordered: $reordered")
                assertEquals(1_999_000, preview.amountMsat)
                assertEquals(false, preview.hasOtherPaths)
                assertEquals(ClaimResult.Claimed(1_999_000, true), alice.wallet.claim(preview))
                assertEquals(1_999_000, alice.balance(), "renewing is free")
                assertEquals(ReceivedState.Claimed, alice.wallet.receivedNotes.first().single().state)
                assertEquals(0, alice.wallet.incomingMsat.first())
            }
        }

    @Test
    fun `a note locked until tomorrow is kept, counted as incoming, and stays locked`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            alice.fund(2000)
            val until = nowSeconds() + 86_400
            val locked = LockedNote.until(until)
            alice.lockTo(locked.q)

            // The mint refuses the spend, and names the lock; nothing is burned.
            val callback = alice.wallet.client.lookup(alice.withdrawUrl(), locked.q).callback
            val output = PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes)).xOnlyPublicKey().value
            val rotate = Requests.rotate(callback, locked.spend(spendDomain(HOST)).k1, output)
            val refusal = assertIs<MutationOutcome.Rejected>(alice.wallet.client.mutate(MutationType.Rotate, rotate))
            assertTrue("in the future" in refusal.reason.orEmpty(), "the mint names the unmet lock: ${refusal.reason}")

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
    fun `a kept note is claimed by the first refresh after its lock passes`() =
        runBlocking<Unit> {
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
    fun `a note locked for a while after the mint credits it is known as locked by the mint's refusal of the claim`() =
        runBlocking<Unit> {
            val alice = Regtest.phone()
            alice.fund(2000)
            val locked = LockedNote.after(1)
            alice.lockTo(locked.q)

            // Looked at by its key alone, it is there; only the claim tells whether its wait is over.
            val preview = assertIs<ClaimPreview.Ready>(alice.wallet.previewClaim(locked.link(HOST, alice.withdrawUrl())))
            val kept = assertIs<ClaimResult.Kept>(alice.wallet.claim(preview)).note
            assertEquals(ReceivedState.Locked, kept.state)
            assertEquals(512, kept.lockedForSeconds)
            assertEquals(1_999_000, alice.wallet.incomingMsat.first())
            // Only the mint knows when the wait started: asked on every refresh, it still refuses.
            alice.wallet.refresh()
            assertEquals(ReceivedState.Locked, alice.wallet.receivedNotes.first().single().state)
            assertEquals(0, alice.balance())
        }

    private suspend fun Regtest.Phone.withdrawUrl(): String = assertNotNull(store.mint(HOST)).withdrawUrl
}
