package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

class NoteRotatorTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branch: CashBranch)

    private fun withMint(vararg flags: String, client: LnurlcashClient = LnurlcashClient(), test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start(*flags).use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), client, policies = NotePolicies.InMemory(ManualStrategy.ID))
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
            Fixture(mint, store, wallet, CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)).test()
        }
    }

    /** An address payment the wallet has already found (credited at the mint, then scanned in). */
    private suspend fun Fixture.received(vararg amounts: Long) {
        amounts.forEachIndexed { i, msat -> mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, i.toLong())), msat) }
        assertEquals(amounts.size, wallet.refresh().newNotes)
    }

    @Test
    fun `an address note rotates onto the next wallet key`() = withMint {
        received(3_000)
        val result = assertIs<RotateResult.Rotated>(wallet.rotateKey(mint.host, NotePurpose.LightningAddress.value, 0))
        assertEquals(RotateResult.Rotated(fromIndex = 0, toIndex = 0, amountMsat = 3_000, certified = true), result)

        assertEquals(3_000, wallet.balanceMsat.first(), "a rotate is free")
        val notes = store.allNotes()
        assertEquals(NoteState.Spent, notes.single { it.purpose == 2 }.state)
        val fresh = notes.single { it.purpose == 0 }
        assertEquals(NoteState.Outstanding, fresh.state)
        assertEquals(Cp1.encode(branch.notePubkey(NotePurpose.Wallet, 0)), Cp1.encode(fr.acinq.bitcoin.ByteVector32(fresh.id)))
        assertNotNull(fresh.certificate)
        assertEquals(1, wallet.nextWalletIndex(mint.host))
        assertEquals(JournalStatus.Confirmed, store.journal.values.single().status)
        assertIs<KeyProbe.Spent>(wallet.keyProbes.first()[KeyId("a", mint.host, 2, 0)])
        assertIs<KeyProbe.Outstanding>(wallet.keyProbes.first()[KeyId("a", mint.host, 0, 0)])
    }

    @Test
    fun `a key without an outstanding note is not rotated and nothing is sent`() = withMint {
        assertIs<RotateResult.NotRotatable>(wallet.rotateKey(mint.host, NotePurpose.Wallet.value, 5))
        assertTrue(store.journal.isEmpty())
        assertEquals(0, wallet.nextWalletIndex(mint.host), "no index was reserved")
    }

    @Test
    fun `rotate all moves every outstanding note of the tab onto wallet keys`() = withMint {
        received(1_000, 2_000, 4_000)
        val result = wallet.rotateAllKeys(mint.host, NotePurpose.LightningAddress.value)
        assertEquals(RotateAllResult(rotated = 3, failed = 0, pending = 0, totalMsat = 7_000, stoppedEarly = null), result)
        assertEquals(7_000, wallet.balanceMsat.first())
        val outstanding = store.allNotes().filter { it.state == NoteState.Outstanding }
        assertEquals(listOf(0L, 1L, 2L), outstanding.filter { it.purpose == 0 }.mapNotNull { it.index }.sorted())
        assertTrue(outstanding.none { it.purpose == 2 })
    }

    @Test
    fun `a rotate whose answer is lost is settled by reconciliation`() =
        withMint("--dropAfterMutation", client = LnurlcashClient(mutationReplays = 0)) {
            received(5_000)
            val result = wallet.rotateKey(mint.host, NotePurpose.LightningAddress.value, 0)
            assertIs<RotateResult.Rotated>(result, "the output exists at the mint, so reconciliation confirms it")
            assertEquals(5_000, wallet.balanceMsat.first())
            assertEquals(JournalStatus.Confirmed, store.journal.values.single().status)
        }
}
