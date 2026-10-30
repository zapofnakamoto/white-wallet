package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.core.model.Profile
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

class RestoreTest {
    private val phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val words = phrase.split(" ")

    @Test
    fun `a phrase is read the way people write it down`() {
        val numbered = words.mapIndexed { i, w -> "${i + 1}. ${w.uppercase()}" }.joinToString("\n")
        assertEquals(PhraseCheck.Valid(words), RecoveryPhrase.check(numbered))
        assertEquals(PhraseCheck.Valid(words), RecoveryPhrase.check(words.joinToString(", ")))
        assertEquals(PhraseCheck.Valid(words), RecoveryPhrase.check(words.mapIndexed { i, w -> "${i + 1}) $w" }.joinToString(" ")))
    }

    @Test
    fun `a wrong phrase says what is wrong`() {
        assertEquals(PhraseCheck.WrongCount(11), RecoveryPhrase.check(words.dropLast(1).joinToString(" ")))
        assertEquals(PhraseCheck.UnknownWords(listOf("wallett")), RecoveryPhrase.check(phrase.replace("wave", "wallett")))
        // Two valid words swapped: the checksum no longer matches.
        assertEquals(PhraseCheck.BadChecksum, RecoveryPhrase.check(phrase.replace("year wave", "wave year")))
    }

    @Test
    fun `while typing, only a word that can't become valid is called unknown`() {
        val eleven = words.dropLast(1).joinToString(" ")
        assertEquals(PhraseCheck.WrongCount(11), RecoveryPhrase.checkWhileTyping("$eleven yel"), "still typing \"yellow\"")
        assertEquals(PhraseCheck.UnknownWords(listOf("xyz")), RecoveryPhrase.checkWhileTyping("$eleven xyz"))
        assertEquals(PhraseCheck.UnknownWords(listOf("yel")), RecoveryPhrase.checkWhileTyping("$eleven yel "), "a space ends the word")
        assertEquals(PhraseCheck.Valid(words), RecoveryPhrase.checkWhileTyping(phrase))
    }

    @Test
    fun `restore finds the notes of every purpose and sets the indexes past them`() = withMint { mint, store, wallet ->
        val branch = CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)
        // Wallet keys 0 and 1, change key 0, and two address payments with a gap between them.
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.Wallet, 0)), 1_000)
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.Wallet, 1)), 2_000)
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.Change, 0)), 3_000)
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 0)), 4_000)
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.LightningAddress, 5)), 5_000)

        val restored = assertIs<RestoreResult.Restored>(wallet.restore(words.joinToString(" "), mint.host, "Restored"))
        assertEquals(5, restored.notes)
        assertEquals(15_000, restored.balanceMsat)
        assertEquals(15_000, wallet.balanceMsat.first())
        assertEquals(restored.accountId, store.currentAccountId())
        assertEquals(2, store.currentIndex(restored.accountId, mint.host, NotePurpose.Wallet.value), "never hands out a used key")
        assertEquals(1, store.currentIndex(restored.accountId, mint.host, NotePurpose.Change.value))
        assertEquals(6, store.addressScanFrom(restored.accountId, mint.host))
        assertTrue(wallet.backupVerified.first(), "the user already has the phrase written down")
        assertTrue(wallet.needsAddress(), "addresses can't be recovered from the seed")
        assertTrue(wallet.activity.first().isEmpty(), "no history is invented")
    }

    @Test
    fun `aborting a restore before the address step removes the account and goes back to the one before`() = withMint { mint, store, wallet ->
        store.addAccount("a", "Main", Profile("alice", mint.host))
        seeds.save("a", "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "))
        val branch = CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), mint.host)
        mint.credit(Cp1.encode(branch.notePubkey(NotePurpose.Wallet, 0)), 1_000)
        assertFailsWith<IllegalStateException>("an account with an address is never removed this way") { wallet.abortRestore() }

        val restored = assertIs<RestoreResult.Restored>(wallet.restore(phrase, mint.host, "Restored"))
        assertEquals(1_000, restored.balanceMsat)
        assertTrue(wallet.abortRestore(), "the earlier account remains")
        assertEquals("a", store.currentAccountId())
        assertEquals(listOf("a"), store.accounts.first().map { it.account.id })
        assertEquals(setOf("a"), seeds.accountIds())
        assertTrue(store.notes(restored.accountId).first().isEmpty())

        // The money is still there: restoring again finds it.
        assertEquals(1_000, assertIs<RestoreResult.Restored>(wallet.restore(phrase, mint.host, "Restored")).balanceMsat)
    }

    @Test
    fun `restoring a phrase that is already in the wallet is refused`() = withMint { mint, store, wallet ->
        store.addAccount("a", "Main", Profile("alice", mint.host))
        seeds.save("a", words)
        assertEquals(RestoreResult.AlreadyInWallet("Main"), wallet.restore(phrase, mint.host, "Restored"))
        assertEquals(1, store.accounts.first().size)
    }

    @Test
    fun `an invalid phrase or an unreachable mint leaves nothing behind`() = withMint { _, store, wallet ->
        assertIs<RestoreResult.InvalidPhrase>(wallet.restore("abandon ability", "localhost:1", "Restored"))
        assertIs<RestoreResult.NetworkError>(wallet.restore(phrase, "localhost:1", "Restored"))
        assertTrue(store.accounts.first().isEmpty())
        assertTrue(seeds.accountIds().isEmpty())
        assertFalse(wallet.needsAddress())
    }

    @Test
    fun `a note being paid out counts as used, so the keys after it are looked up too`() =
        withMint(answering = { host, p -> "pending".takeIf { p == key(host, NotePurpose.Wallet, 0) } }) { mint, store, wallet ->
            // Key 0 is mid-melt, and 20 is the first key after a gap of 19: found only if key 0 counts as used.
            mint.credit(key(mint.host, NotePurpose.Wallet, 20), 1_000)
            val restored = assertIs<RestoreResult.Restored>(wallet.restore(phrase, mint.host, "Restored"))
            assertEquals(1_000, restored.balanceMsat)
            assertEquals(21, store.currentIndex(restored.accountId, mint.host, NotePurpose.Wallet.value))
        }

    @Test
    fun `a lookup the mint refuses ends the restore and leaves nothing behind`() =
        withMint(answering = { host, p -> "internal error".takeIf { p == key(host, NotePurpose.Wallet, 1) } }) { mint, store, wallet ->
            // Counted as unused, the refusal would end in a restore that misses key 2's note.
            mint.credit(key(mint.host, NotePurpose.Wallet, 0), 1_000)
            mint.credit(key(mint.host, NotePurpose.Wallet, 2), 2_000)
            assertIs<RestoreResult.MintInvalid>(wallet.restore(phrase, mint.host, "Restored"))
            assertTrue(store.accounts.first().isEmpty())
            assertTrue(seeds.accountIds().isEmpty())
        }

    private val seeds = InMemorySeedStore()

    /** The `cp1` of the phrase's key [index] of [purpose] on [host]. */
    private fun key(host: String, purpose: NotePurpose, index: Long) =
        Cp1.encode(CashBranch.fromSeed(MnemonicCode.toSeed(words, ""), host).notePubkey(purpose, index))

    /** [answering] gives the mint's refusal reason for a lookup of key `p` on a host, or null to let the mint answer. */
    private fun withMint(
        answering: (host: String, p: String) -> String? = { _, _ -> null },
        test: suspend (MockMint, InMemoryWalletStore, Wallet) -> Unit,
    ) = runBlocking<Unit> {
        assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
        MockMint.start().use { mint ->
            val store = InMemoryWalletStore()
            val http = LnurlcashClient.defaultHttpClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val reason = request.url.queryParameter("p")?.let { answering(mint.host, it) } ?: return@addInterceptor chain.proceed(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"status":"ERROR","reason":"$reason"}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            test(mint, store, Wallet(store, seeds, LnurlcashClient(http = http)))
        }
    }
}
