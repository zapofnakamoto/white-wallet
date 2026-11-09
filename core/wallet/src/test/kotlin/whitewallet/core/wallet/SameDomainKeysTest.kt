package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.Note
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.core.wallet.legacy.LegacyWallet
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain

/** LUD-26 keys derive from the domain without its port: two mints on one hostname see the same keys of a phrase. */
class SameDomainKeysTest {
    private val words = MnemonicCode.toMnemonics(ByteArray(16).also(SecureRandom()::nextBytes))

    @Test
    fun `mints on one domain derive the same keys, whatever their ports, and another domain derives others`() {
        val a = testKeys(words, "mint.example:3338")
        val b = testKeys(words, "mint.example:4448")
        for (set in listOf(KeySet.Wallet, KeySet.Change, KeySet.share(0))) {
            assertEquals(a.pubkey(set, 0), b.pubkey(set, 0), "$set")
            assertNotEquals(a.pubkey(set, 0), testKeys(words, "other.example:3338").pubkey(set, 0), "$set")
        }
        assertEquals(a.share(0).cx1(), b.share(0).cx1())
    }

    @Test
    fun `legacy 2f17578 - the port was part of the keys`() {
        val a = testKeys(words, "mint.example:3338")
        val b = testKeys(words, "mint.example:4448")
        assertNotEquals(a.pubkey(KeySet.LegacyWallet, 0), b.pubkey(KeySet.LegacyWallet, 0))
    }

    private fun phone(store: InMemoryWalletStore) =
        Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))

    /** Buys the phone's next note on its home mint, by invoice. */
    private suspend fun Wallet.firstNote(store: InMemoryWalletStore, sats: Long): Note {
        val invoice = assertIs<InvoiceRequest.Ready>(requestInvoice(sats * 1000))
        Regtest.pay(invoice.invoice, 0)
        assertIs<InvoiceStatus.Paid>(invoiceStatus(invoice.id))
        return store.notes(activeId()).first().single()
    }

    @OptIn(RevealsNoteSecret::class)
    @Test
    fun `two real mints on one domain give a phrase's first note the same key, yet hold it apart, and one wallet never uses it twice`() =
        runBlocking<Unit> {
            Regtest.requireStack()
            val store = InMemoryWalletStore()
            val wallet = phone(store)
            assertIs<CreateAccountResult.Created>(wallet.createAccount(HOST, "Main"))
            val otherStore = InMemoryWalletStore()
            val other = phone(otherStore)
            val phrase = wallet.recoveryPhrase()
            assertIs<RestoreResult.Restored>(other.restore(phrase.joinToString(" "), HOST2, "Main"))

            val here = wallet.firstNote(store, 2000)
            val there = other.firstNote(otherStore, 2000)
            assertEquals(0L to 0L, here.purpose!!.toLong() to here.index!!, "the first key of the wallet chain")
            assertEquals(here.id, there.id, "the same key on both mints")
            assertEquals(HOST to HOST2, here.host to there.host)
            assertEquals(1_999_000L to 1_998_000L, here.amountMsat to there.amountMsat, "each mint its own note, at its own fee")

            // One wallet counts per domain: the other mint's next key is the one after, so nothing is used twice.
            assertEquals(1, store.indexOf(wallet.activeId(), HOST2, KeySet.Wallet))

            // Spending it on one mint leaves the other mint's note alone.
            val client = LnurlcashClient()
            val q = ByteVector32(here.id)
            val mint = client.servicePayRequest(HOST)
            val info = client.lookup(mint.withdrawUrl, q)
            val spend = wallet.keys(wallet.activeId(), HOST).spend(here, spendDomain(HOST))
            val elsewhere = testKeys(words, HOST).pubkey(KeySet.Wallet, 0)
            assertIs<MutationOutcome.Confirmed>(client.mutate(MutationType.Rotate, Requests.rotate(info.callback, spend.k1, elsewhere)))
            val gone = assertFailsWith<NoteLookupError> { client.lookup(mint.withdrawUrl, q) }
            assertEquals(RejectionKind.Spent, gone.kind, gone.reason)
            assertEquals(1_998_000, client.lookup(client.servicePayRequest(HOST2).withdrawUrl, q).maxWithdrawableMsat)
        }
}
