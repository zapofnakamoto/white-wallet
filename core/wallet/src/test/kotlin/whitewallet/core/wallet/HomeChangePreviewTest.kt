package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/**
 * The summary before changing the home mint ([Wallet.previewChangeHome]) and the money moved with it. The account has
 * no address: its notes are claimed on the old home, a [MockMint]; the new home is a [NotesTest.TargetService].
 */
class HomeChangePreviewTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private var clock = 1_800_000_000_000L
    private var nextBearer = 0x11

    private class Fixture(val old: MockMint, val store: InMemoryWalletStore, val wallet: Wallet, private val claim: suspend (Long) -> Unit) {
        suspend fun fund(vararg sats: Long) = sats.forEach { claim(it) }
        suspend fun on(host: String) = store.notes("a").first().filter { it.state == NoteState.Outstanding && it.host == host }.map { it.amountMsat }.sorted()
    }

    private fun withOldHome(defaults: CollectDefaults = CollectDefaults(), test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { old ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient(), now = { clock }, requireAddress = false,
                policies = NotePolicies.InMemory(ManualStrategy.ID), collectDefaults = defaults)
            wallet.payWaitMillis = 3_000
            store.addAccount("a", "Main", null, host = old.host)
            assertIs<ConnectResult.Connected>(wallet.connectService(old.host))
            val claim: suspend (Long) -> Unit = { sats ->
                val bearer = Spend.Bearer(ByteVector32(Hex.decode("%02x".format(nextBearer++).repeat(32))))
                old.credit(bearer.h.toHex(), sats * 1000)
                assertIs<ClaimResult.Claimed>(wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim("lnurlw://${old.host}/w?k1=${bearer.k1}"))))
            }
            Fixture(old, store, wallet, claim).test()
        }
    }

    @Test
    fun `the summary quotes the move like collecting would, and the change then moves it`() = withOldHome(CollectDefaults(enabled = true)) {
        fund(30, 20)
        NotesTest.TargetService(feeMsat = 500).use { home ->
            val preview = assertIs<HomeChangePreview>(wallet.previewChangeHome(home.host))
            assertEquals(true, preview.collects)
            assertEquals(listOf(HomeChangePreview.Line(old.host, 50_000, HomeChangePreview.Outcome.Moves(49_500, 500))), preview.lines)
            assertEquals(50_000, preview.movingMsat)
            assertEquals(500, preview.feeMsat)
            assertEquals(listOf(30_000L, 20_000L).sorted(), on(old.host), "a preview pays nothing")

            assertIs<ConnectResult.Connected>(wallet.changeHome(home.host))
            assertEquals(listOf(49_500L), on(home.host))
            assertEquals(emptyList(), on(old.host))
        }
    }

    @Test
    fun `money over the fee cap or too small is said to stay`() = withOldHome(CollectDefaults(enabled = true)) {
        fund(50)
        NotesTest.TargetService(feeMsat = 2_000).use { home ->
            val line = assertIs<HomeChangePreview>(wallet.previewChangeHome(home.host)).lines.single()
            assertEquals(HomeChangePreview.Outcome.TooExpensive(2_000), line.outcome)
        }
        NotesTest.TargetService(feeMsat = 60_000).use { home ->
            assertIs<HomeChangePreview.Outcome.TooSmall>(assertIs<HomeChangePreview>(wallet.previewChangeHome(home.host)).lines.single().outcome)
        }
    }

    @Test
    fun `nothing elsewhere, or the same mint, needs no summary`() = withOldHome {
        NotesTest.TargetService(feeMsat = 0).use { home ->
            assertEquals(emptyList(), assertIs<HomeChangePreview>(wallet.previewChangeHome(home.host)).lines)
        }
        assertNull(wallet.previewChangeHome(old.host))
    }

    @Test
    fun `without collecting the user may have the money moved this once`() = withOldHome(CollectDefaults(enabled = false)) {
        fund(30)
        NotesTest.TargetService(feeMsat = 0).use { home ->
            val preview = assertIs<HomeChangePreview>(wallet.previewChangeHome(home.host))
            assertEquals(false, preview.collects)
            assertEquals(true, preview.canMove)
            assertIs<HomeChangePreview.Outcome.Moves>(preview.lines.single().outcome)

            assertIs<ConnectResult.Connected>(wallet.changeHome(home.host, moveMoney = true))
            assertEquals(listOf(30_000L), on(home.host))
            assertEquals(false, wallet.collectAtHome.first(), "the account still doesn't collect")
        }
    }

    @Test
    fun `left there when the user chooses so`() = withOldHome(CollectDefaults(enabled = false)) {
        fund(30)
        NotesTest.TargetService(feeMsat = 0).use { home ->
            assertIs<ConnectResult.Connected>(wallet.changeHome(home.host, moveMoney = false))
            assertEquals(listOf(30_000L), on(old.host))
        }
    }

    @Test
    fun `a brand that hides collecting moves nothing, and says so`() = withOldHome(CollectDefaults(offered = false)) {
        fund(30)
        NotesTest.TargetService(feeMsat = 0).use { home ->
            val preview = assertIs<HomeChangePreview>(wallet.previewChangeHome(home.host))
            assertEquals(false, preview.canMove)
            assertEquals(HomeChangePreview.Outcome.Stays, preview.lines.single().outcome)
            assertIs<ConnectResult.Connected>(wallet.changeHome(home.host, moveMoney = true))
            assertEquals(listOf(30_000L), on(old.host))
        }
    }
}
