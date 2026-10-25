package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.brand.NoteStep
import whitewallet.brand.NoteStrategy
import whitewallet.brand.ServiceNotes
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/** A brand's own note strategy, run by the wallet (see [NoteStrategy]). Against the mock mint. */
class CustomStrategyTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    /** Keeps notes at or below [maxMsat]: splits off [maxMsat] from any bigger note. */
    private class Chunks(private val maxMsat: Long) : NoteStrategy {
        override val id = "chunks"
        override val title = 0
        override val description = 0
        override fun plan(service: ServiceNotes): List<NoteStep> =
            service.notes.filter { it.amountMsat > maxMsat + service.feeBaseMsat }.map { NoteStep.Split(it.id, maxMsat) }
    }

    /** Proposes only what can't be done: unknown notes, impossible amounts, rotating a wallet note. */
    private object Nonsense : NoteStrategy {
        override val id = "nonsense"
        override val title = 0
        override val description = 0
        override fun plan(service: ServiceNotes): List<NoteStep> = listOf(NoteStep.Combine(listOf("nope", "nada"))) +
            service.notes.flatMap { listOf(NoteStep.Split(it.id, it.amountMsat), NoteStep.Combine(listOf(it.id)), NoteStep.Rotate(it.id)) }
    }

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branch: CashBranch) {
        suspend fun outstanding() = store.allNotes().filter { it.state == NoteState.Outstanding }
    }

    private fun withMint(strategy: NoteStrategy, test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "LNURLCASH_CONFORMANCE not set")
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val strategies = NoteStrategies(listOf(ManualStrategy(), strategy), listOf(ManualStrategy.ID, strategy.id), strategy.id)
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), strategies = strategies)
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
            Fixture(mint, store, wallet, CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)).test()
        }
    }

    @Test
    fun `a brand strategy reshapes incoming notes, round after round`() = withMint(Chunks(10_000)) {
        assertEquals("chunks", wallet.noteStrategy.first().id, "the brand's default")
        val fee = assertNotNull(store.service(mint.host)).feeBaseMsat ?: 0
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 0)), 35_000 + 3 * fee)
        wallet.refresh()
        val amounts = outstanding().map { it.amountMsat }.sortedDescending()
        assertEquals(listOf(10_000L, 10_000, 10_000, 5_000), amounts)
        assertEquals(3, assertNotNull(wallet.lastPolicyRun.first()).split)
    }

    @Test
    fun `steps that can't apply are skipped and the run ends`() = withMint(Nonsense) {
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 0)), 5_000)
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 1)), 7_000)
        wallet.refresh()
        assertEquals(listOf(5_000L, 7_000), outstanding().map { it.amountMsat }.sorted())
        // Rotating an incoming note is allowed, so Nonsense rotates both; then nothing it proposes applies.
        assertEquals(listOf(0, 0), outstanding().map { it.purpose })
        assertEquals(12_000, wallet.balanceMsat.first())
    }

    @Test
    fun `a user can still choose another offered strategy`() = withMint(Chunks(10_000)) {
        wallet.setNoteStrategy(ManualStrategy.ID)
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 0)), 35_000)
        wallet.refresh()
        assertEquals(listOf(35_000L), outstanding().map { it.amountMsat })
        assertEquals(listOf("manual", "chunks"), wallet.noteStrategies.map { it.id })
    }
}
