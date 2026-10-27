package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

class NotePolicyTest {
    private val wordsA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val wordsB = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val policies: NotePolicies, val wallet: Wallet) {
        val branchA = CashBranch.fromSeed(MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "), ""), mint.host)
        val branchB = CashBranch.fromSeed(MnemonicCode.toSeed("legal winner thank year wave sausage worth useful legal winner thank yellow".split(" "), ""), mint.host)
    }

    private fun withMint(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val policies = NotePolicies.InMemory()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to wordsA, "b" to wordsB)), LnurlcashClient(), policies = policies)
            store.addAccount("b", "Savings", Profile("bob", mint.host))
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
            Fixture(mint, store, policies, wallet).test()
        }
    }

    /** Address payments to account "a" at indexes 0.. (found by the next refresh). */
    private fun Fixture.pay(vararg amounts: Long, branch: CashBranch = branchA) =
        amounts.forEachIndexed { i, msat -> mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, i.toLong())), msat) }

    private suspend fun Fixture.outstanding(account: String = "a") =
        store.allNotes().filter { it.accountId == account && it.state == NoteState.Outstanding }

    @Test
    fun `by default notes on a host are kept as one`() = withMint {
        assertEquals(SingleNoteStrategy.ID, wallet.noteStrategy.first().id)
        pay(1_000, 2_000)
        wallet.refresh()
        assertEquals(listOf(0), outstanding().map { it.purpose })
        assertEquals(3_000, wallet.balanceMsat.first(), "merging is free")
    }

    @Test
    fun `under manual, incoming notes are left alone`() = withMint {
        wallet.setNoteStrategy(ManualStrategy.ID)
        pay(1_000, 2_000)
        wallet.refresh()
        assertEquals(listOf(2, 2), outstanding().map { it.purpose })
    }

    @Test
    fun `auto-rotate moves every incoming note onto a wallet key`() = withMint {
        wallet.setNoteStrategy(ManualStrategy.ID)
        wallet.setAutoRotate(true)
        pay(1_000, 2_000)
        wallet.refresh()
        val notes = outstanding()
        assertEquals(listOf(0, 0), notes.map { it.purpose })
        assertEquals(3_000, wallet.balanceMsat.first(), "rotating is free")
        assertEquals(2, assertNotNull(wallet.lastPolicyRun.first()).rotated)

        // A later payment is rotated by the next refresh too.
        mint.credit(Cp1.encode(branchA.notePubkey(NotePurpose.LightningAddress, 2)), 4_000)
        wallet.refresh()
        assertTrue(outstanding().none { it.purpose == 2 })
        assertEquals(7_000, wallet.balanceMsat.first())
    }

    @Test
    fun `keep only one note merges everything on the service into one wallet note`() = withMint {
        // An existing wallet note plus three incoming payments.
        val index = store.reserveIndex("a", mint.host, 0)
        mint.credit(Cp1.encode(branchA.notePubkey(NotePurpose.Wallet, index)), 5_000)
        store.upsertNotes(listOf(Note(branchA.notePubkey(NotePurpose.Wallet, index).toHex(), "a", mint.host, NoteKind.KeyPath, 0, index, 5_000, null, NoteState.Outstanding, 0)))
        wallet.setNoteStrategy(SingleNoteStrategy.ID)
        pay(1_000, 2_000, 4_000)
        wallet.refresh()
        val single = outstanding().single()
        assertEquals(0, single.purpose)
        assertEquals(12_000, single.amountMsat)
        assertNotNull(single.certificate)
        assertEquals(12_000, wallet.balanceMsat.first())
    }

    @Test
    fun `many notes are merged in batches`() = withMint {
        wallet.setNoteStrategy(ManualStrategy.ID)
        pay(*LongArray(12) { 1_000L * (it + 1) })
        wallet.refresh()
        assertEquals(12, outstanding().size)
        wallet.setNoteStrategy(SingleNoteStrategy.ID)
        val single = outstanding().single()
        assertEquals(78_000, single.amountMsat)
        assertEquals(13, assertNotNull(wallet.lastPolicyRun.first()).merged, "10 notes, then the merged note plus the remaining 2")
    }

    @Test
    fun `a single incoming note is rotated under keep only one note`() = withMint {
        wallet.setNoteStrategy(SingleNoteStrategy.ID)
        pay(3_000)
        wallet.refresh()
        assertEquals(0, outstanding().single().purpose)
    }

    @Test
    fun `choosing a strategy applies it right away`() = withMint {
        wallet.setNoteStrategy(ManualStrategy.ID)
        pay(1_000, 2_000)
        wallet.refresh()
        assertEquals(2, outstanding().size)
        wallet.setNoteStrategy(SingleNoteStrategy.ID)
        assertEquals(3_000, outstanding().single().amountMsat)
        assertEquals(SingleNoteStrategy.ID, wallet.noteStrategy.first().id)
    }

    @Test
    fun `policies are per account`() = withMint {
        wallet.setNoteStrategy(ManualStrategy.ID)
        pay(1_000, 2_000)
        pay(1_000, 2_000, branch = branchB)
        wallet.refresh()
        assertEquals(2, outstanding("a").size)
        assertEquals(1, outstanding("b").size, "account b keeps the default, keep only one note")
        wallet.switchAccount("b")
        assertEquals(SingleNoteStrategy.ID, wallet.noteStrategy.first().id)
        assertFalse(wallet.autoRotate.first())
    }

    @Test
    fun `deleting an account clears its policies`() = withMint {
        wallet.setAutoRotate(true)
        wallet.deleteActiveAccount()
        assertFalse(policies.autoRotate("a").first())
    }
}
