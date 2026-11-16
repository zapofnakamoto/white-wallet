@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.Mint
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * No note is looked up at a mint whose signing key doesn't match the pinned one, on any page: the real mint answers `already in use` for a
 * note used elsewhere, an answer without its key, which would otherwise end the note. Each test pins another key than the mint presents, as
 * after the mint's key changed, and starts the app again, so the key is compared afresh; once it matches again, the same answer counts.
 */
class RegtestChangedKeyLookupsTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    @Test
    fun `the key tools look nothing up while the key doesn't match, so a note used elsewhere stays held until it matches`() =
        runBlocking<Unit> {
            val alice = Phone()
            val held = alice.holding(2000)
            usedElsewhere(alice, held)

            val pinned = alice.pinAnotherKey()
            val set = KeySet.of(held)
            assertNull(alice.wallet.recheckKey(HOST, set, held.index!!))
            assertTrue(alice.wallet.scanKeysAhead(HOST, set).keyChanged)
            assertTrue(alice.wallet.recheckAllKeys(HOST, set).keyChanged)
            assertTrue(alice.wallet.findKeyIndex(HOST, set).keyChanged)
            assertEquals(NoteState.Outstanding, alice.store.note(held.id)?.state)

            alice.store.saveMint(pinned)
            assertIs<KeyProbe.Spent>(alice.wallet.recheckKey(HOST, set, held.index!!))
            assertEquals(NoteState.Spent, alice.store.note(held.id)?.state)
        }

    @Test
    fun `a note set aside as unknown stays so while the key doesn't match, and is found used once it matches`() =
        runBlocking<Unit> {
            val alice = Phone()
            val held = alice.holding(2000)
            usedElsewhere(alice, held)
            alice.store.upsertNotes(listOf(held.copy(state = NoteState.Unknown)))

            val pinned = alice.pinAnotherKey()
            alice.wallet.refresh()
            assertEquals(setOf(HOST), alice.wallet.mintsWithChangedKey.first())
            assertEquals(NoteState.Unknown, alice.store.note(held.id)?.state)

            alice.store.saveMint(pinned)
            alice.restart()
            alice.wallet.refresh()
            assertEquals(NoteState.Spent, alice.store.note(held.id)?.state)
        }

    @Test
    fun `a sent link someone claimed stays open while the key doesn't match, and is done once it matches`() =
        runBlocking<Unit> {
            val alice = Phone()
            val held = alice.holding(2000)
            val link = assertNotNull(assertIs<ShareNoteResult.Shared>(alice.wallet.shareNote(held.id)).note.link)
            val bob = Phone()
            bob.holding(1000)
            assertIs<ClaimResult.Claimed>(bob.wallet.claim(assertIs<ClaimPreview.Ready>(bob.wallet.previewClaim(link))))

            val pinned = alice.pinAnotherKey()
            alice.wallet.refresh()
            alice.wallet.checkSharedNote(held.id)
            assertEquals(NoteState.Shared, alice.store.note(held.id)?.state)

            alice.store.saveMint(pinned)
            alice.restart()
            alice.wallet.refresh()
            assertEquals(NoteState.Spent, alice.store.note(held.id)?.state)
        }

    @Test
    fun `a kept link keeps its spend while the key doesn't match, though its note was taken back, and is gone once it matches`() =
        runBlocking<Unit> {
            val alice = Phone()
            val held = alice.holding(2000)
            val link = assertNotNull(assertIs<ShareNoteResult.Shared>(alice.wallet.shareNote(held.id)).note.link)
            val bob = Phone()
            bob.holding(1000)
            val pinned = bob.pinAnotherKey()
            // Not asked: what it is worth is only what the link says.
            val preview = assertIs<ClaimPreview.KeyChanged>(bob.wallet.previewClaim(link))
            assertNull(preview.amountMsat)
            val kept = bob.wallet.keepClaim(preview).note
            assertIs<ReclaimResult.Reclaimed>(alice.wallet.reclaimNote(held.id))

            bob.wallet.refresh()
            val waiting = assertNotNull(bob.store.receivedNote(kept.id))
            assertEquals(ReceivedState.KeyChanged, waiting.state)
            assertNotNull(waiting.k1, "its spend is kept")

            bob.store.saveMint(pinned)
            bob.restart()
            bob.wallet.refresh()
            assertEquals(ReceivedState.Gone, bob.store.receivedNote(kept.id)?.state)
        }

    /** The same phrase on another phone renews [held]: at the mint, its key is in use now. */
    private suspend fun usedElsewhere(phone: Phone, held: Note) {
        val other =
            Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        assertIs<RestoreResult.Restored>(other.restore(phone.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
        assertIs<RotateResult.Rotated>(other.rotateKey(HOST, KeySet.of(held), held.index!!))
    }

    /** A phone whose app can be started again on the same data: what it compared since it started is forgotten then. */
    private class Phone {
        val store = InMemoryWalletStore()
        private val seeds = InMemorySeedStore()
        var wallet = start()
            private set

        private fun start() = Wallet(store, seeds, LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))

        fun restart() {
            wallet = start()
        }

        /** An account on the mint holding one claimed note of [sats]. */
        suspend fun holding(sats: Long): Note {
            assertIs<CreateAccountResult.Created>(wallet.createAccount(HOST, "Main"))
            assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim(Regtest.note(sats)))))
            return store.notes(store.currentAccountId()!!).first().single { it.state == NoteState.Outstanding }
        }

        /** Pins another key than the mint presents, and starts the app again; returns the mint as it was pinned. */
        suspend fun pinAnotherKey(): Mint {
            val pinned = assertNotNull(store.mint(HOST))
            store.saveMint(pinned.copy(mintPubkey = "02" + "11".repeat(32)))
            restart()
            return pinned
        }
    }
}
