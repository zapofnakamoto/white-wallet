package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.testkit.MockMint
import fr.acinq.bitcoin.MnemonicCode
import whitewallet.lnurlcash.CashBranch

/** The Notes tab's per-note tools: recheck, renew (rotate) and share a whole note. */
class NoteToolsTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    /** One phone of this recovery phrase, holding one ₿21 note from an address payment. */
    private suspend fun phone(mint: MockMint): Pair<InMemoryWalletStore, Wallet> {
        val store = InMemoryWalletStore()
        val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
        store.addAccount("a", "Main", Profile("alice", mint.host))
        assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
        return store to wallet
    }

    private fun withMint(test: suspend (MockMint) -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { mint ->
            val branch = CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)
            mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 0)), 21_000)
            test(mint)
        }
    }

    private suspend fun InMemoryWalletStore.outstanding() = notes("a").first().filter { it.state == NoteState.Outstanding }

    @Test
    fun `recheck confirms a note, and notices when the same phrase spent it on another phone`() = withMint { mint ->
        val (store, wallet) = phone(mint)
        wallet.refresh()
        val id = store.outstanding().single().id
        assertEquals(NoteCheck.Ok(21_000, certified = true), wallet.recheckNote(id))

        // The other phone renews the same note: here it is gone.
        val (_, other) = phone(mint)
        other.refresh()
        assertIs<RotateResult.Rotated>(other.rotateNote(id))
        assertEquals(NoteCheck.Spent, wallet.recheckNote(id))
        assertEquals(0, wallet.balanceMsat.first())
        assertEquals(NoteCheck.NotFound, wallet.recheckNote(id), "a spent note isn't checked again")
    }

    @Test
    fun `recheck all refreshes and reports the notes that are gone`() = withMint { mint ->
        val (store, wallet) = phone(mint)
        wallet.refresh()
        val id = store.outstanding().single().id
        val (_, other) = phone(mint)
        other.refresh()
        other.rotateNote(id)
        // Refreshing finds nothing new on this phone (the address payment is known); the lookup finds it spent.
        assertEquals(NotesCheck(checked = 1, gone = 1, unreachable = emptyList()), wallet.recheckNotes())
    }

    @Test
    fun `renew moves the note to a fresh wallet key, same amount`() = withMint { mint ->
        val (store, wallet) = phone(mint)
        wallet.refresh()
        val old = store.outstanding().single()
        val rotated = assertIs<RotateResult.Rotated>(wallet.rotateNote(old.id))
        assertEquals(21_000, rotated.amountMsat)
        val new = store.outstanding().single()
        assertNotEquals(old.id, new.id)
        assertEquals(NotePurpose.Wallet.value, new.purpose)
        assertEquals(21_000, wallet.balanceMsat.first())
    }

    @Test
    fun `a whole note is shared as it is, out of the balance, and can be taken back`() = withMint { mint ->
        val (store, wallet) = phone(mint)
        wallet.refresh()
        val id = store.outstanding().single().id
        val shared = assertIs<ShareNoteResult.Shared>(wallet.shareNote(id)).note
        assertEquals(id, shared.id)
        assertEquals(21_000, shared.amountMsat)
        assertEquals(21_000, assertNotNull(NoteLink.parse(assertNotNull(shared.link))).declaredAmountMsat)
        assertEquals(0, wallet.balanceMsat.first())
        val row = store.activities("a").first().single { it.kind == ActivityKind.SentNote }
        assertEquals(ActivityStatus.Pending, row.status)
        assertEquals(null, row.feeMsat, "nothing was split, so no fee")
        assertEquals(ShareNoteResult.NotShareable, wallet.shareNote(id), "already shared")

        assertIs<ReclaimResult.Reclaimed>(wallet.reclaimNote(id))
        assertEquals(21_000, wallet.balanceMsat.first())
    }

    @Test
    fun `a note spent elsewhere is not handed out`() = withMint { mint ->
        val (store, wallet) = phone(mint)
        wallet.refresh()
        val id = store.outstanding().single().id
        val (_, other) = phone(mint)
        other.refresh()
        other.rotateNote(id)
        assertEquals(ShareNoteResult.AlreadySpent, wallet.shareNote(id))
        assertEquals(0, wallet.balanceMsat.first())
        assertEquals(emptyList(), store.activities("a").first().filter { it.kind == ActivityKind.SentNote })
    }
}
