package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import whitewallet.lnurlcash.client.LnurlcashClient

class BackupTest {
    private val words = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    @Test
    fun `tapping every word in order completes the quiz`() {
        val quiz = BackupQuiz(words)
        assertNotEquals(words.indices.toList(), quiz.choices, "choices are shuffled")
        assertEquals(words.indices.toSet(), quiz.choices.toSet())
        words.indices.forEach { position ->
            val result = quiz.tap(position)
            if (position < words.lastIndex) assertEquals(BackupQuiz.Result.Correct, result) else assertEquals(BackupQuiz.Result.Complete, result)
        }
        assertTrue(quiz.isComplete)
    }

    @Test
    fun `a wrong tap resets progress and names the expected word`() {
        val quiz = BackupQuiz(words)
        quiz.tap(0)
        quiz.tap(1)
        assertEquals(BackupQuiz.Result.Wrong(expectedNumber = 3), quiz.tap(5))
        assertEquals(emptyList(), quiz.progress)
        assertFalse(quiz.isComplete)
    }

    @Test
    fun `repeated words are accepted at either position but never twice`() {
        val quiz = BackupQuiz(words)
        // "legal" is word 1 and word 9: tapping position 8 first is fine.
        assertEquals(BackupQuiz.Result.Correct, quiz.tap(8))
        assertEquals(BackupQuiz.Result.Correct, quiz.tap(1))
        quiz.tap(2); quiz.tap(3); quiz.tap(4); quiz.tap(5); quiz.tap(6); quiz.tap(7)
        // Word 9 is "legal" again, but position 8 is already used.
        assertEquals(BackupQuiz.Result.Wrong(expectedNumber = 9), quiz.tap(8))
    }

    @Test
    fun `the wallet exposes the stored phrase and remembers verification`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        store.addAccount("main", "Main", null)
        val wallet = Wallet(store, InMemorySeedStore(mapOf("main" to words)), LnurlcashClient())
        assertEquals(words, wallet.recoveryPhrase())
        assertFalse(wallet.backupVerified.first())
        wallet.markBackupVerified()
        assertTrue(wallet.backupVerified.first())
    }
}

class DeleteAccountTest {
    private val words = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    @Test
    fun `deleting the only account removes its seed, notes, profile and backup flag`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore(mapOf("main" to words))
        val wallet = Wallet(store, seeds, LnurlcashClient())
        store.addAccount("main", "Main", whitewallet.core.model.Profile("alice", "mint.example"))
        store.upsertNotes(listOf(note("aa", "main")))
        wallet.markBackupVerified()

        assertFalse(wallet.deleteActiveAccount(), "no other account to switch to")

        assertFalse(wallet.isSetUp())
        assertEquals(null, seeds.load("main"))
        assertFalse(wallet.backupVerified.first())
        assertEquals(0L, wallet.balanceMsat.first())
        assertEquals(null, wallet.addressStatus.first())
    }

    @Test
    fun `deleting one of two accounts switches to the other and keeps its data`() = runBlocking<Unit> {
        val store = InMemoryWalletStore()
        val seeds = InMemorySeedStore(mapOf("a" to words, "b" to words.reversed()))
        val wallet = Wallet(store, seeds, LnurlcashClient())
        store.addAccount("a", "A", whitewallet.core.model.Profile("alice", "mint.example"))
        store.addAccount("b", "B", whitewallet.core.model.Profile("bob", "mint.example"))
        store.upsertNotes(listOf(note("aa", "a"), note("bb", "b")))
        assertEquals("bob", wallet.profile.first()?.username)

        assertTrue(wallet.deleteActiveAccount())

        assertEquals("a", store.currentAccountId())
        assertEquals("alice", wallet.profile.first()?.username)
        assertEquals(1000L, wallet.balanceMsat.first())
        assertEquals(words, seeds.load("a"))
        assertEquals(null, seeds.load("b"))
    }

    private fun note(id: String, account: String) = whitewallet.core.model.Note(id, account, "mint.example", whitewallet.core.model.NoteKind.KeyPath, 0, 0, 1000, null,
        whitewallet.core.model.NoteState.Outstanding, 0)
}
