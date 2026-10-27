package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.NoteState
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/** The developer key page observes the same store as Home, so background changes reach it without its own actions. */
class KeyViewSyncTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    @Test
    fun `an auto-rotate during a refresh reaches observers of the key view`() = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val wallet = Wallet(store, InMemorySeedStore(mapOf("a" to words)), LnurlcashClient())
            store.addAccount("a", "Main", Profile("alice", mint.host))
            assertIs<ConnectResult.Connected>(wallet.connectService(mint.host))
            wallet.setAutoRotate(true)

            val changes = CopyOnWriteArrayList<Unit>()
            val indexes = CopyOnWriteArrayList<KeyIndexes>()
            val observers = listOf(
                launch(Dispatchers.Default) { wallet.keyViewChanges(mint.host).collect { changes += it } },
                launch(Dispatchers.Default) { wallet.keyIndexesFlow(mint.host).collect { indexes += it } },
            )
            delay(200)
            val before = changes.size

            val branch = CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)
            mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 0)), 2_000)
            wallet.refresh() // not one of the page's actions
            delay(200)

            assertTrue(changes.size > before, "the key view was told something changed")
            assertEquals(KeyIndexes(wallet = 1, change = 0, addressScanFrom = 1), indexes.last(), "the index flow shows the rotate")
            val address = wallet.derivedKeys(mint.host, NotePurpose.LightningAddress.value).first()
            val walletKey = wallet.derivedKeys(mint.host, NotePurpose.Wallet.value).first()
            assertEquals(NoteState.Spent, address.noteState)
            assertEquals(NoteState.Outstanding, walletKey.noteState)
            // Nothing was looked up from the page, yet every row says what it is.
            assertEquals(KeyStatus.Spent, address.status)
            assertEquals(KeyStatus.Funded(walletKey.noteAmountMsat!!), walletKey.status)
            assertEquals(KeyStatus.Next, wallet.derivedKeys(mint.host, NotePurpose.Wallet.value)[1].status)
            observers.forEach { it.cancel() }
        }
    }
}
