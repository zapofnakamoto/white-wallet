package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.Regtest.HOST
import whitewallet.core.wallet.Regtest.HOST2
import whitewallet.lnurlcash.client.LnurlcashClient

/**
 * More notes than one merge request may spend, against the real mint: lnurl-mint refuses more than 100, and a proxy may refuse a long URL
 * sooner. The notes come from splitting one funded note, which needs no Lightning payment each.
 */
class RegtestManyNotesTest {
    @BeforeEach fun requireStack() = Regtest.requireStack()

    /** The most notes any request spent so far. */
    private var mostNotesInARequest = 0

    private suspend fun phone(settings: WalletSettings = SMALL_MERGES): Regtest.Phone {
        val client = Regtest.client { chain ->
            val spent = chain.request().url.queryParameterValues("k1").size
            mostNotesInARequest = maxOf(mostNotesInARequest, spent)
            chain.proceed(chain.request())
        }
        return Regtest.phone(client = client, settings = settings)
    }

    /** Funds the active account with one note and splits it until it holds [count] notes of about [NOTE_SATS]. */
    private suspend fun Regtest.Phone.fundNotes(count: Int) {
        fund(count * (NOTE_SATS + 2))
        repeat(count - 1) {
            val largest = notes().maxBy { it.amountMsat }
            assertIs<NoteActionResult.Done>(wallet.splitNote(largest.id, NOTE_SATS * 1000))
        }
        assertEquals(count, notes().size)
    }

    /** A note of [sats] the mint has never seen, recorded as outstanding. */
    private suspend fun Regtest.Phone.unknownNote(sats: Long): Note {
        val accountId = store.currentAccountId()!!
        val q = wallet.keys(accountId, HOST).pubkey(KeySet.Wallet, 900).toHex()
        return Note(
                q,
                accountId,
                HOST,
                NoteKind.KeyPath,
                KeySet.Wallet.purpose,
                900,
                sats * 1000,
                null,
                NoteState.Outstanding,
                0,
                scheme = KeyScheme.Bip32,
            )
            .also { store.upsertNotes(listOf(it)) }
    }

    private suspend fun Regtest.Phone.balances() = wallet.accounts.first().associate { it.account.id to it.balanceMsat }

    @Test
    fun `a payment from more notes than the mint takes in one request is paid, ten notes at a time`() =
        runBlocking<Unit> {
            val alice = phone(WalletSettings())
            alice.fundNotes(105)
            val before = alice.balance()
            val sats = before / 1000 - 5
            val preview = alice.wallet.previewPayment(Regtest.invoice(sats)) as PayPreview.Ready
            val paid = assertIs<PayResult.Paid>(alice.wallet.pay(preview, sats * 1000))
            assertEquals(sats * 1000, paid.amountMsat)
            // The mint refunds a base fee per note merged away, so more than the difference is left; paid.feeMsat stops at 0.
            val left = alice.balance() - (before - paid.amountMsat)
            assertTrue(left in 0..105_000, "at most one refund of 1 sat per note: $left")
            assertEquals(10, mostNotesInARequest)
        }

    @Test
    fun `combining notes merges them in several requests into one note, losing nothing`() =
        runBlocking<Unit> {
            val alice = phone()
            alice.fundNotes(8)
            val before = alice.balance()
            assertIs<NoteActionResult.Done>(alice.wallet.combineNotes(alice.notes().map { it.id }))
            assertEquals(1, alice.notes().size)
            assertTrue(alice.balance() >= before, "a merge never loses value")
            assertEquals(3, mostNotesInARequest)
            assertEquals(1, alice.wallet.activity.first().count { it.kind == ActivityKind.Combined }, "one combine for the user")
        }

    private suspend fun accounts(): Triple<Regtest.Phone, String, String> {
        val phone = phone()
        val main = phone.wallet.activeId()
        assertIs<OnboardResult.Ready>(phone.wallet.onboard(Regtest.name(), HOST, newAccountName = "Savings"))
        val savings = assertNotNull(phone.store.currentAccountId())
        phone.wallet.switchAccount(main)
        return Triple(phone, main, savings)
    }

    @Test
    fun `moving everything to another account takes several requests, and only the last reaches it`() =
        runBlocking<Unit> {
            val (phone, main, savings) = accounts()
            phone.fundNotes(8)
            val moved = assertIs<MoveResult.Moved>(phone.wallet.moveFunds(savings, null))
            assertEquals(mapOf(main to 0L, savings to moved.amountMsat), phone.balances())
            assertEquals(3, mostNotesInARequest)
        }

    @Test
    fun `moving part of the money to another account merges in several requests, then splits`() =
        runBlocking<Unit> {
            val (phone, main, savings) = accounts()
            phone.fundNotes(8)
            val before = phone.balance()
            assertEquals(MoveResult.Moved(200_000), phone.wallet.moveFunds(savings, 200_000))
            val balances = phone.balances()
            assertEquals(200_000, balances[savings])
            assertTrue(balances.getValue(main) >= before - 200_000 - 1_000, "only the split's fee: $balances")
            assertEquals(3, mostNotesInARequest)
        }

    @Test
    fun `moving notes to another mint merges them in several requests first`() =
        runBlocking<Unit> {
            val alice = phone()
            alice.wallet.payWaitMillis = 5_000
            alice.fundNotes(8)
            val ids = alice.notes().map { it.id }
            assertIs<MintMoveQuote.Ready>(alice.wallet.quoteMintMove(ids, HOST2))
            assertIs<MintMoveResult.Moved>(alice.wallet.moveToMint(ids, HOST2))
            assertEquals(emptyList(), alice.notes(HOST))
            assertEquals(1, alice.notes(HOST2).size)
            assertEquals(3, mostNotesInARequest)
        }

    @Test
    fun `a move stopped by a note the mint doesn't know leaves everything in the account, and goes through without it`() =
        runBlocking<Unit> {
            val (phone, main, savings) = accounts()
            phone.fundNotes(8)
            val known = phone.balance()
            // The smallest, so the batches before it have gone through when the mint refuses it.
            val unknown = phone.unknownNote(1)

            assertIs<MoveResult.Failed>(phone.wallet.moveFunds(savings, null))
            assertEquals(NoteState.Unknown, phone.store.note(unknown.id)?.state)
            assertEquals(0, phone.balances().getValue(savings), "nothing reached the other account")
            assertTrue(phone.balances().getValue(main) >= known, "the notes merged so far are still here")
            assertTrue(phone.notes().size < 8, "and they are merged")

            val moved = assertIs<MoveResult.Moved>(phone.wallet.moveFunds(savings, null))
            assertEquals(mapOf(main to 0L, savings to moved.amountMsat), phone.balances())
        }

    @Test
    fun `a phrase brings back a wallet of more notes than one request may spend, and it can spend them`() =
        runBlocking<Unit> {
            val alice = phone(WalletSettings())
            alice.fundNotes(105)
            val store = InMemoryWalletStore()
            val restored = Wallet(store, InMemorySeedStore(), LnurlcashClient(), policies = NotePolicies.InMemory(ManualStrategy.ID))
            val result = assertIs<RestoreResult.Restored>(restored.restore(alice.wallet.recoveryPhrase().joinToString(" "), HOST, "Main"))
            assertEquals(105, result.notes)
            assertEquals(alice.balance(), result.balanceMsat)
            val sats = result.balanceMsat / 1000 - 5
            val preview = restored.previewPayment(Regtest.invoice(sats)) as PayPreview.Ready
            assertIs<PayResult.Paid>(restored.pay(preview, sats * 1000))
        }

    @Test
    fun `combining, moving to another account and moving to another mint all take more notes than one request may spend`() =
        runBlocking<Unit> {
            val alice = phone(WalletSettings())
            alice.fundNotes(105)
            val before = alice.balance()
            assertIs<NoteActionResult.Done>(alice.wallet.combineNotes(alice.notes().map { it.id }))
            assertEquals(1, alice.notes().size)
            assertTrue(alice.balance() >= before)

            val (phone, main, savings) = accounts()
            phone.fundNotes(105)
            val moved = assertIs<MoveResult.Moved>(phone.wallet.moveFunds(savings, null))
            assertEquals(mapOf(main to 0L, savings to moved.amountMsat), phone.balances())

            val bob = phone(WalletSettings())
            bob.wallet.payWaitMillis = 5_000
            bob.fundNotes(105)
            assertIs<MintMoveResult.Moved>(bob.wallet.moveToMint(bob.notes().map { it.id }, HOST2))
            assertEquals(1, bob.notes(HOST2).size)
            assertEquals(10, mostNotesInARequest)
        }

    @Test
    fun `renewing every note of a wallet of many notes renews them all`() =
        runBlocking<Unit> {
            val alice = phone(WalletSettings())
            alice.fundNotes(105)
            val before = alice.notes().map { it.id }.toSet()
            val balance = alice.balance()
            for (set in listOf(KeySet.Wallet, KeySet.Change)) alice.wallet.rotateAllKeys(HOST, set)
            assertEquals(105, alice.notes().size)
            assertEquals(balance, alice.balance(), "a renewal is free")
            assertTrue(alice.notes().none { it.id in before }, "every note is on a new key")
        }

    @Test
    fun `the keep one note strategy brings a wallet of many notes down to one, however many runs it takes`() =
        runBlocking<Unit> {
            val alice = phone(WalletSettings())
            alice.fundNotes(105)
            val before = alice.balance()
            alice.wallet.setNoteStrategy(SingleNoteStrategy.ID)
            alice.wallet.refresh()
            assertEquals(1, alice.notes().size)
            assertTrue(alice.balance() >= before, "a merge never loses value")
            assertEquals(10, mostNotesInARequest)
        }

    private companion object {
        const val NOTE_SATS = 30L
        val SMALL_MERGES = WalletSettings(noteMergeMaxNotesPerRequest = 3)
    }
}
