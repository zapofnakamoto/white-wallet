package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import whitewallet.lnurlcash.client.LnurlcashClient

/** Secrets are kept in as few copies, and for as short a time, as the JVM allows. */
class SecretsInMemoryTest {
    private val words = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    @Test
    fun `a stored phrase reads back as the wordlist's own strings`() {
        val bytes = RecoveryPhrase.toBytes(words)
        assertEquals(words.joinToString(" "), bytes.decodeToString())
        val read = RecoveryPhrase.fromBytes(bytes)
        assertEquals(words, read)
        read.forEach { word -> assertSame(MnemonicCode.englishWordlist.first { it == word }, word) }
    }

    @Test
    fun `the seed is BIP-39's, for every phrase length`() {
        val random = java.security.SecureRandom()
        for (bytes in listOf(16, 20, 24, 28, 32)) repeat(5) {
            val phrase = MnemonicCode.toMnemonics(ByteArray(bytes).also(random::nextBytes))
            assertContentEquals(MnemonicCode.toSeed(phrase, ""), RecoveryPhrase.seed(phrase))
        }
        // BIP-39's vector for all-zero entropy, without passphrase.
        val zero = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            RecoveryPhrase.seed(zero).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `the suggested name comes from the active wallet's own phrase, so each wallet has its own`() =
        runBlocking<Unit> {
            val wallet = Wallet(InMemoryWalletStore(), InMemorySeedStore(), LnurlcashClient())
            assertNull(wallet.defaultUsername("mint.example"), "no wallet yet")
            wallet.createWallet("One")
            val name = wallet.defaultUsername("mint.example")
            assertNotNull(name)
            assertEquals(name, wallet.defaultUsername("mint.example"), "the same every time")
            wallet.createWallet("Two")
            assertNotEquals(name, wallet.defaultUsername("mint.example"))
        }
}
