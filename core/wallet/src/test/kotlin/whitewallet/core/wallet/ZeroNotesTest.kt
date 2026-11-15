@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.PrivateKey
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.wallet.LookupMint.Companion.note
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.spendDomain

/**
 * A note worth 0 is a note like any other (a token, say, that stands for something other than its value): Claim offers it, a kept one is
 * claimed, and the wallet keeps its own. Only nothing picks it to pay or to combine: it adds nothing, and spending it would burn whatever
 * it stands for.
 */
class ZeroNotesTest {
    private val clock = 1_800_000_000_000L

    @Test
    fun `Claim offers a note worth 0 like any other`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val spend = someoneElses(mint)
                mint.lookups[spend.q.toHex()] = note(0)
                val preview = NoteClaimer(wallet).preview(NoteLink(mint.withdrawUrl, spend, null, null).encode(), accountId)
                assertEquals(0, assertIs<ClaimPreview.Ready>(preview).amountMsat)
            }
        }

    @Test
    fun `a kept note worth 0 is claimed like any other`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val received = received(wallet, accountId, mint)
                mint.lookups[received.id] = note(0)
                mint.callback = """{"status":"OK"}"""
                ReceivedNotes(wallet).claim(received)
                assertEquals(1, mint.callbacks, "the claim went out")
                assertEquals(ReceivedState.Claimed, wallet.store.receivedNote(received.id)?.state)
            }
        }

    @Test
    fun `note maintenance never takes a note worth 0`() =
        runBlocking<Unit> {
            LookupMint().use { mint ->
                val (wallet, accountId) = wallet(mint)
                val token = held(wallet, KeySet.Wallet.key(accountId, mint.host, 900), 0)
                val money = held(wallet, KeySet.Wallet.key(accountId, mint.host, 901), 5_000)
                mint.lookups[token] = note(0)
                mint.lookups[money] = note(5_000)
                NotePolicyEngine(wallet).apply(accountId, wallet.noteStrategy.first(), autoRotate = true)
                assertEquals(0, mint.callbacks, "keeping one note: one note of value, nothing to combine")
                assertEquals(NoteState.Outstanding, wallet.store.note(token)?.state, "kept, shown, never spent")
            }
        }

    private suspend fun wallet(mint: LookupMint): Pair<Wallet, String> {
        val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient(), now = { clock })
        val accountId = (wallet.createAccount(mint.host, "Main") as CreateAccountResult.Created).accountId
        return wallet to accountId
    }

    /** A note of the wallet's own at [id] worth [amountMsat]; its key as hex. */
    private suspend fun held(wallet: Wallet, id: KeyId, amountMsat: Long): String {
        val q = wallet.keys(id.accountId, id.host).pubkey(id).toHex()
        wallet.store.upsertNotes(
            listOf(
                Note(q, id.accountId, id.host, NoteKind.KeyPath, id.set.purpose, id.index, amountMsat, null, NoteState.Outstanding, clock)
            )
        )
        return q
    }

    private fun someoneElses(mint: LookupMint): Spend.KeyPath =
        Spend.keyPath(PrivateKey(ByteArray(32).also(SecureRandom()::nextBytes)), spendDomain(mint.host))

    /** Someone else's note on [mint], kept by [accountId] and waiting to be claimed. */
    private suspend fun received(wallet: Wallet, accountId: String, mint: LookupMint): ReceivedNote {
        val spend = someoneElses(mint)
        val note =
            ReceivedNote(
                spend.q.toHex(),
                accountId,
                mint.host,
                mint.withdrawUrl,
                spend.k1,
                certificate = null,
                declaredMsat = null,
                amountMsat = null,
                lockedUntilMillis = null,
                lockedForSeconds = null,
                state = ReceivedState.Waiting,
                journalId = null,
                receivedAtMillis = clock,
                updatedAtMillis = clock,
            )
        wallet.store.putReceivedNote(note)
        return note
    }
}
