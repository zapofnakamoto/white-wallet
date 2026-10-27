package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/**
 * "Collect at home mint": notes on other mints move to the home mint when the move costs at most the fee cap. The
 * account's notes start on the mock mint ([MockMint]); its home mint is a [NotesTest.TargetMint].
 */
class CollectTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private var clock = 1_800_000_000_000L

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branch: CashBranch) {
        var next = 0L

        /** Notes paid to the address on the mock mint, found by a refresh. */
        suspend fun fund(vararg sats: Long) {
            sats.forEach { mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, next++)), it * 1000) }
            wallet.refresh()
        }

        suspend fun on(host: String) = store.notes("a").first().filter { it.state == NoteState.Outstanding && it.host == host }.map { it.amountMsat }.sorted()
        suspend fun melts() = store.journal(JournalStatus.entries.toSet()).count { it.type == OperationType.Melt }

        /** Makes [host] the account's home mint, as changing to an address there would. */
        suspend fun moveHomeTo(host: String) = store.saveAccount(store.account("a")!!.copy(host = host))
    }

    private fun withMint(vararg flags: String, defaults: CollectDefaults = CollectDefaults(), test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start(*flags).use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), now = { clock },
                policies = NotePolicies.InMemory(ManualStrategy.ID), collectDefaults = defaults)
            wallet.payWaitMillis = 3_000
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectMint(mint.host))
            Fixture(mint, store, wallet, CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)).test()
        }
    }

    @Test
    fun `notes on another mint move home when the fee is under the cap`() = withMint {
        fund(30, 20)
        NotesTest.TargetMint(feeMsat = 500).use { home ->
            moveHomeTo(home.host)
            wallet.setCollectAtHome(true)
            assertEquals(emptyList(), on(mint.host))
            assertEquals(listOf(49_500L), on(home.host))
            val moved = store.activities("a").first().single { it.kind == ActivityKind.MovedMint }
            assertEquals(true, moved.automatic)
            assertEquals(listOf(Collected(mint.host, 49_500, 500)), wallet.lastPolicyRun.first()?.collected)
        }
    }

    @Test
    fun `a note claimed from a mint the wallet never used is collected right away`() = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { other ->
            NotesTest.TargetMint(feeMsat = 0).use { home ->
                val store = InMemoryWalletStore()
                val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), now = { clock },
                    policies = NotePolicies.InMemory(ManualStrategy.ID), collectDefaults = CollectDefaults(enabled = true))
                wallet.payWaitMillis = 3_000
                store.addAccount("a", "Main", null, host = home.host)
                val bearer = Spend.Bearer(ByteVector32(Hex.decode("55".repeat(32))))
                other.credit(bearer.h.toHex(), 21_000)
                assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim("lnurlw://${other.host}/w?k1=${bearer.k1}"))))
                assertNotNull(store.mint(other.host), "the claimed note's mint is remembered")
                val notes = store.notes("a").first().filter { it.state == NoteState.Outstanding }
                assertEquals(listOf(home.host to 21_000L), notes.map { it.host to it.amountMsat })
            }
        }
    }

    @Test
    fun `over the cap nothing is sent, until the cap allows it`() = withMint {
        fund(50)
        NotesTest.TargetMint(feeMsat = 2_000).use { home ->
            moveHomeTo(home.host)
            wallet.setCollectAtHome(true)
            assertEquals(listOf(50_000L), on(mint.host))
            assertEquals(0, melts(), "nothing was paid")
            assertEquals(listOf(NotCollected(mint.host, 50_000, NotCollected.Reason.FeeTooHigh(2_000, 2))), wallet.lastPolicyRun.first()?.notCollected)

            wallet.setCollectFeeCap(5)
            assertEquals(listOf(48_000L), on(home.host))
        }
    }

    @Test
    fun `notes worth less than the home mint's fee stay`() = withMint {
        fund(3)
        NotesTest.TargetMint(feeMsat = 5_000).use { home ->
            moveHomeTo(home.host)
            wallet.setCollectFeeCap(100)
            wallet.setCollectAtHome(true)
            assertEquals(listOf(3_000L), on(mint.host))
            assertIs<NotCollected.Reason.TooSmall>(wallet.lastPolicyRun.first()?.notCollected?.single()?.reason)
        }
    }

    @Test
    fun `after a failed payment the mint is left alone for an hour`() = withMint("--meltAlwaysFails") {
        fund(30)
        NotesTest.TargetMint(feeMsat = 0, mints = false).use { home ->
            moveHomeTo(home.host)
            wallet.setCollectAtHome(true)
            assertEquals(1, melts())
            assertEquals(NotCollected.Reason.Failed(NoteProblem.PaymentFailed), wallet.lastPolicyRun.first()?.notCollected?.single()?.reason)
            assertEquals(listOf(30_000L), on(mint.host), "the money is back")

            wallet.refresh()
            assertEquals(1, melts(), "no second payment right away")
            clock += 61 * 60_000L
            wallet.refresh()
            assertEquals(2, melts(), "tried again after the cooldown")
        }
    }

    @Test
    fun `a payment still unsettled on that mint holds collecting back`() = withMint {
        fund(30)
        NotesTest.TargetMint(feeMsat = 0).use { home ->
            moveHomeTo(home.host)
            store.putJournal(JournalEntry("melt", "a", OperationType.Melt, mint.host, "http://${mint.host}/w", emptyList(), emptyList(), emptyList(),
                "lnbc1", JournalStatus.Unknown, null, clock, clock))
            wallet.setCollectAtHome(true)
            assertEquals(listOf(30_000L), on(mint.host))
        }
    }

    @Test
    fun `the home mint's notes stay, and collecting is off unless chosen or the brand starts it on`() = withMint {
        fund(30)
        assertEquals(false, wallet.collectAtHome.first())
        wallet.setCollectAtHome(true)
        assertEquals(listOf(30_000L), on(mint.host), "already home")
        assertNull(wallet.lastPolicyRun.first()?.collected?.firstOrNull())
    }

    @Test
    fun `a brand can hide it or start it on`() = withMint(defaults = CollectDefaults(offered = false)) {
        assertEquals(false, wallet.collectAtHome.first())
        assertFailsWith<IllegalStateException> { wallet.setCollectAtHome(true) }
        val on = CollectDefaults.fromConfig("on", 5)
        assertEquals(CollectDefaults(offered = true, enabled = true, feeCapPercent = 5), on)
        assertEquals(CollectDefaults(offered = false, enabled = false, feeCapPercent = 2), CollectDefaults.fromConfig("hidden", 2))
        assertFailsWith<IllegalStateException> { CollectDefaults.fromConfig("sometimes", 2) }
        assertNotNull(Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), collectDefaults = on))
    }

    @Test
    fun `a brand can make every account collect, with no switch`() = withMint(defaults = CollectDefaults.fromConfig("always", 2)) {
        assertEquals(CollectDefaults(offered = false, enabled = true, feeCapPercent = 2, always = true), CollectDefaults.fromConfig("always", 2))
        assertFailsWith<IllegalStateException> { wallet.setCollectAtHome(false) }
        assertEquals(true, wallet.collectAtHome.first())
        fund(30, 20)
        NotesTest.TargetMint(feeMsat = 500).use { home ->
            moveHomeTo(home.host)
            wallet.refresh()
            assertEquals(emptyList(), on(mint.host))
            assertEquals(listOf(49_500L), on(home.host))
        }
    }

    @Test
    fun `where notes are shown is a brand choice`() {
        assertEquals(NotesView.Hidden, NotesView.fromConfig(" Hidden "))
        assertEquals(NotesView.Tab, NotesView.fromConfig("tab"))
        assertFailsWith<IllegalStateException> { NotesView.fromConfig("drawer") }
    }

    @Test
    fun `which history filters there are is a brand choice`() {
        assertEquals(HistoryFilterOption.Types, HistoryFilterOption.fromConfig("types"))
        assertEquals(HistoryFilterOption.None, HistoryFilterOption.fromConfig(" None "))
        assertEquals(HistoryFilterOption.Maintenance, HistoryFilterOption.fromConfig("maintenance"))
        assertFailsWith<IllegalStateException> { HistoryFilterOption.fromConfig("all") }
    }
}
