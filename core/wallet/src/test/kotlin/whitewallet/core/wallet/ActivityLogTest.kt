package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.testkit.MockMint

/** History records what happened to the money once per event, and note maintenance separately. */
class ActivityLogTest {
    private val wordsA = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val wordsB = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    private class Fixture(val mint: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, val branchA: CashBranch)

    private fun withMint(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to wordsA, "b" to wordsB)), LnurlcashClient())
            store.addAccount("b", "Savings", Profile("bob", mint.host))
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
            Fixture(mint, store, wallet, CashBranch.fromSeed(MnemonicCode.toSeed(wordsA, ""), mint.host)).test()
        }
    }

    private fun Fixture.pay(vararg amounts: Long, from: Long = 0) =
        amounts.forEachIndexed { i, msat -> mint.credit(Cp1.encode(branchA.notePubkey(NotePurpose.LightningAddress, from + i)), msat) }

    private suspend fun Fixture.history(account: String = "a"): List<Activity> = store.activities(account).first()
    private suspend fun Fixture.money(account: String = "a") = history(account).filterNot { it.kind.isMaintenance }

    private suspend fun Fixture.claimBearer(byte: String, msat: Long): ClaimResult {
        val bearer = Spend.Bearer(ByteVector32(Hex.decode(byte.repeat(32))))
        mint.credit(bearer.h.toHex(), msat)
        return wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim("lnurlw://${mint.host}/w?k1=${bearer.k1}")))
    }

    @Test
    fun `claiming under keep only one note shows one claim, with the merge as maintenance`() = withMint {
        pay(1_000)
        wallet.refresh()
        assertIs<ClaimResult.Claimed>(claimBearer("21", 21_000))

        val money = money()
        assertEquals(listOf(ActivityKind.Claimed, ActivityKind.Received), money.map { it.kind }, "newest first")
        assertEquals(listOf(21_000L, 1_000L), money.map { it.amountMsat })
        assertTrue(money.all { it.status == ActivityStatus.Done })
        assertEquals(true, money.first().certified)

        val maintenance = history().filter { it.kind.isMaintenance }
        val combined = maintenance.single { it.kind == ActivityKind.Combined }
        assertEquals(2, combined.noteCount)
        assertEquals(22_000, combined.amountMsat)
        assertTrue(maintenance.all { it.automatic })
        assertEquals(22_000, wallet.balanceMsat.first())
    }

    @Test
    fun `each address payment is one row, and a re-scan adds none`() = withMint {
        wallet.setNoteStrategy(ManualStrategy.ID)
        pay(1_000, 2_000)
        wallet.refresh()
        wallet.refresh()
        assertEquals(listOf(2_000L, 1_000L), money().map { it.amountMsat })
        assertTrue(money().all { it.kind == ActivityKind.Received })
        assertTrue(history().none { it.kind.isMaintenance }, "manual strategy: no maintenance")
    }

    @Test
    fun `a move shows on both accounts with the other account's name`() = withMint {
        pay(3_000)
        wallet.refresh()
        val before = wallet.balanceMsat.first()
        assertEquals(MoveResult.Moved(1_000), wallet.moveFunds("b", 1_000))
        val after = wallet.balanceMsat.first()

        val out = money("a").first()
        assertEquals(ActivityKind.MovedOut, out.kind)
        assertEquals(1_000, out.amountMsat)
        assertEquals("Savings", out.counterparty)
        assertEquals((before - after - 1_000).takeIf { it > 0 }, out.feeMsat, "the fee is what the account lost beyond the amount")

        val incoming = money("b").single()
        assertEquals(ActivityKind.MovedIn, incoming.kind)
        assertEquals(1_000, incoming.amountMsat)
        assertEquals("Main", incoming.counterparty)
    }

    @Test
    fun `a rotation from the developer key page is manual maintenance`() = withMint {
        wallet.setNoteStrategy(ManualStrategy.ID)
        pay(4_000)
        wallet.refresh()
        assertIs<RotateResult.Rotated>(wallet.rotateKey(mint.host, NotePurpose.LightningAddress.value, 0))
        val rotated = history().single { it.kind == ActivityKind.Rotated }
        assertEquals(4_000, rotated.amountMsat)
        assertEquals(false, rotated.automatic)
        assertEquals(listOf(ActivityKind.Received), money().map { it.kind })
    }

    @Test
    fun `a pending row is settled when its journal entry is`() = withMint {
        val bearer = Spend.Bearer(ByteVector32(Hex.decode("44".repeat(32))))
        mint.credit(bearer.h.toHex(), 3_000)
        val service = assertNotNull(store.serviceMap[mint.host])
        val q = branchA.notePubkey(NotePurpose.Wallet, 0)
        // A claim that was journaled but never reached the mint (e.g. the app died).
        store.putJournal(JournalEntry("crashed", "a", OperationType.Rotate, mint.host, service.withdrawUrl, listOf(bearer.q.toHex()),
            listOf(bearer.k1), listOf(OutputRef("a", 0, 0, q.toHex())), Requests.rotate("${mint.url}/w/cb", bearer.k1, q),
            JournalStatus.Prepared, null, 0, 0))
        store.putActivity(Activity("claim:crashed", "a", ActivityKind.Claimed, 3_000, null, mint.host, null, true, null, false,
            ActivityStatus.Pending, "crashed", 0))
        wallet.refresh()
        assertEquals(ActivityStatus.Failed, history().single().status)
    }
}
