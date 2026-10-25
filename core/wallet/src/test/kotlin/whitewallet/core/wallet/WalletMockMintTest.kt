package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.NoteState
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

class WalletMockMintTest {
    private companion object {
        const val ACCOUNT = "main"
    }

    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    @BeforeEach
    fun requireMockMint() = assumeTrue(MockMint.available, "LNURLCASH_CONFORMANCE not set")

    private suspend fun wallet(mint: MockMint, store: InMemoryWalletStore = InMemoryWalletStore(), client: LnurlcashClient = LnurlcashClient()): Wallet {
        val wallet = Wallet(store, InMemorySeedStore(mapOf(ACCOUNT to words)), client)
        store.addAccount(ACCOUNT, "Main", Profile("alice", mint.host))
        assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
        return wallet
    }

    @Test
    fun `connecting pins the service key`() = runBlocking<Unit> {
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            wallet(mint, store)
            val service = assertNotNull(store.serviceMap[mint.host])
            assertEquals("034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa", service.mintPubkey)
        }
    }

    @Test
    fun `refresh finds auto-minted Lightning Address notes across a gap`() = runBlocking<Unit> {
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val wallet = wallet(mint, store)
            val branch = CashBranch.fromSeed(fr.acinq.bitcoin.MnemonicCode.toSeed(words, ""), mint.host)
            for ((index, amount) in listOf(0L to 1_000L, 1L to 2_000L, 5L to 4_000L)) {
                mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, index)), amount)
            }
            val result = wallet.refresh()
            assertEquals(3, result.newNotes)
            assertEquals(7_000, wallet.balanceMsat.first())
            assertEquals(6, store.scanFrom[ACCOUNT to mint.host])
            assertTrue(store.allNotes().all { it.certificate != null }, "certificates verified and kept")
            assertEquals(0, wallet.refresh().newNotes)
        }
    }

    @Test
    fun `claiming a bearer note rotates it into the wallet`() = runBlocking<Unit> {
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val wallet = wallet(mint, store)
            val bearer = Spend.Bearer(ByteVector32(Hex.decode("22".repeat(32))))
            mint.credit(bearer.h.toHex(), 21_000)
            val preview = assertIs<ClaimPreview.Ready>(wallet.previewClaim("lightning:lnurlw://${mint.host}/w?k1=${bearer.k1}&amount=21000"))
            assertEquals(21_000, preview.amountMsat)
            assertTrue(preview.certified)
            assertTrue(preview.isOwnService)

            val claimed = assertIs<ClaimResult.Claimed>(wallet.claim(preview))
            assertEquals(21_000, claimed.amountMsat)
            assertTrue(claimed.certified)
            assertEquals(21_000, wallet.balanceMsat.first())
            assertEquals(JournalStatus.Confirmed, store.journal.values.single().status)
            assertEquals(1, store.counters[Triple(ACCOUNT, mint.host, 0)])

            // The sender's copy is now worthless.
            assertIs<ClaimPreview.AlreadySpent>(wallet.previewClaim("lnurlw://${mint.host}/w?k1=${bearer.k1}"))
        }
    }

    @Test
    fun `a claim whose answer is lost is settled by reconciliation`() = runBlocking<Unit> {
        MockMint.start("--dropAfterMutation").use { mint ->
            val store = InMemoryWalletStore()
            val wallet = wallet(mint, store, LnurlcashClient(mutationReplays = 0))
            val bearer = Spend.Bearer(ByteVector32(Hex.decode("33".repeat(32))))
            mint.credit(bearer.h.toHex(), 5_000)
            val preview = assertIs<ClaimPreview.Ready>(wallet.previewClaim("lnurlw://${mint.host}/w?k1=${bearer.k1}"))
            assertIs<ClaimResult.Claimed>(wallet.claim(preview))
            assertEquals(5_000, wallet.balanceMsat.first())
            assertEquals(JournalStatus.Confirmed, store.journal.values.single().status)
        }
    }

    @Test
    fun `a prepared entry that never reached the service is rejected on refresh`() = runBlocking<Unit> {
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val wallet = wallet(mint, store)
            val bearer = Spend.Bearer(ByteVector32(Hex.decode("44".repeat(32))))
            mint.credit(bearer.h.toHex(), 3_000)
            val service = assertNotNull(store.serviceMap[mint.host])
            val q = CashBranch.fromSeed(fr.acinq.bitcoin.MnemonicCode.toSeed(words, ""), mint.host).notePubkey(NotePurpose.Wallet, 0)
            store.putJournal(JournalEntry("crashed", ACCOUNT, OperationType.Rotate, mint.host, service.withdrawUrl, listOf(bearer.q.toHex()),
                listOf(bearer.k1), listOf(OutputRef(ACCOUNT, 0, 0, q.toHex())), Requests.rotate("${mint.url}/w/cb", bearer.k1, q),
                JournalStatus.Prepared, null, 0, 0))
            wallet.refresh()
            assertEquals(JournalStatus.Rejected, store.journal.getValue("crashed").status)
            assertEquals(0L, wallet.balanceMsat.first())
        }
    }
}
