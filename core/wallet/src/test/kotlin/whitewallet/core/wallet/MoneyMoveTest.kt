package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/**
 * Moving money between mints at a fee the user saw ([Wallet.previewMove], [Wallet.moveMoney]), and changing the home
 * mint without moving it. The account has no address: its notes are claimed on a [MockMint] (its home); the target
 * is a [NotesTest.TargetMint].
 */
class MoneyMoveTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private var clock = 1_800_000_000_000L
    private var nextBearer = 0x11

    private class Fixture(val old: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, private val claim: suspend (Long) -> Unit) {
        suspend fun fund(vararg sats: Long) = sats.forEach { claim(it) }
        suspend fun on(host: String) = store.notes("a").first().filter { it.state == NoteState.Outstanding && it.host == host }.map { it.amountMsat }.sorted()
    }

    private fun withMint(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { old ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), now = { clock }, requireAddress = false,
                policies = NotePolicies.InMemory(ManualStrategy.ID))
            wallet.payWaitMillis = 3_000
            store.addAccount("a", "Main", null, host = old.host)
            assertIs<ConnectResult.Connected>(wallet.connectMint(old.host))
            val claim: suspend (Long) -> Unit = { sats ->
                val bearer = Spend.Bearer(ByteVector32(Hex.decode("%02x".format(nextBearer++).repeat(32))))
                old.credit(bearer.h.toHex(), sats * 1000)
                assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim("lnurlw://${old.host}/w?k1=${bearer.k1}"))))
            }
            Fixture(old, store, wallet, claim).test()
        }
    }

    @Test
    fun `the preview quotes each mint's money, and the move delivers it as quoted`() = withMint {
        fund(30, 20)
        NotesTest.TargetMint(feeMsat = 500).use { target ->
            val preview = requireNotNull(wallet.previewMove(target.host))
            assertEquals(listOf(MovePreview.Line(old.host, 50_000, MovePreview.Quote.Ok(49_500, 500))), preview.lines)
            assertEquals(listOf(20_000L, 30_000L), on(old.host), "a preview pays nothing")

            val moved = wallet.moveMoney(target.host, preview.lines).single()
            assertEquals(MoneyMoved(old.host, 50_000, MintMoveResult.Moved(49_500, 500)), moved)
            assertEquals(listOf(49_500L), on(target.host))
            assertEquals(emptyList(), on(old.host))
        }
    }

    @Test
    fun `changing the home mint moves no money by itself`() = withMint {
        fund(30)
        NotesTest.TargetMint(feeMsat = 0).use { target ->
            assertIs<ConnectResult.Connected>(wallet.changeHome(target.host))
            assertEquals(target.host, wallet.homeHost.first())
            assertEquals(listOf(30_000L), on(old.host))
            assertEquals(listOf(old.host), requireNotNull(wallet.previewMove(target.host)).lines.map { it.host }, "still there to move")
        }
    }

    @Test
    fun `over the fee cap is only a note for collecting, a confirmed move still goes`() = withMint {
        fund(50)
        NotesTest.TargetMint(feeMsat = 2_000).use { target ->
            val line = requireNotNull(wallet.previewMove(target.host)).lines.single()
            assertTrue(line.overCap(2))
            assertFalse(line.overCap(5))
            assertIs<MintMoveResult.Moved>(wallet.moveMoney(target.host, listOf(line)).single().result)
            assertEquals(listOf(48_000L), on(target.host))
        }
    }

    @Test
    fun `money worth less than the target's fee can't move`() = withMint {
        fund(3)
        NotesTest.TargetMint(feeMsat = 5_000).use { target ->
            assertIs<MovePreview.Quote.TooSmall>(requireNotNull(wallet.previewMove(target.host)).lines.single().quote)
        }
    }

    @Test
    fun `a move costing more than confirmed sends nothing`() = withMint {
        fund(30)
        NotesTest.TargetMint(feeMsat = 500).use { target ->
            val line = requireNotNull(wallet.previewMove(target.host)).lines.single()
            val cheaper = line.copy(quote = MovePreview.Quote.Ok(30_000, 0))
            assertEquals(MintMoveResult.Failed(NoteProblem.Declined), wallet.moveMoney(target.host, listOf(cheaper)).single().result)
            assertEquals(listOf(30_000L), on(old.host))
        }
    }

    @Test
    fun `only the mint asked for, and nothing to move where nothing is`() = withMint {
        NotesTest.TargetMint(feeMsat = 0).use { target ->
            assertEquals(emptyList(), requireNotNull(wallet.previewMove(target.host)).lines)
            fund(30)
            assertEquals(emptyList(), requireNotNull(wallet.previewMove(target.host, fromHost = "elsewhere.example")).lines)
            assertEquals(emptyList(), requireNotNull(wallet.previewMove(old.host)).lines, "already there")
            val line = requireNotNull(wallet.previewMove(target.host, fromHost = old.host)).lines.single()
            wallet.moveMoney(target.host, listOf(line))
            assertEquals(MintMoveResult.Failed(NoteProblem.NotFound), wallet.moveMoney(target.host, listOf(line)).single().result, "already moved")
        }
    }
}
