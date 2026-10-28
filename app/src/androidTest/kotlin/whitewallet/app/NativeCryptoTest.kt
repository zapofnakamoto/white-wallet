package whitewallet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.acinq.bitcoin.MnemonicCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import whitewallet.core.wallet.RecoveryPhrase
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.Cx1
import whitewallet.lnurlcash.NotePurpose

/** The native secp256k1 the app ships (secp256k1-kmp-jni-android) signs and verifies like the JVM build in unit tests. */
@RunWith(AndroidJUnit4::class)
class NativeCryptoTest {
    private val seed = MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")

    /** The platform's PBKDF2-HMAC-SHA512 gives the seed bitcoin-kmp does, so restores find the same keys. */
    @Test
    fun recoveryPhraseSeedMatchesBip39() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        assertArrayEquals(seed, RecoveryPhrase.seed(words))
    }

    @Test
    fun spendsAreSignedAndVerifiedPerDomain() {
        val branch = CashBranch.fromSeed(seed, "mint.example")
        val spend = branch.spend(NotePurpose.Wallet, 0, "mint.example")
        assertEquals(branch.notePubkey(NotePurpose.Wallet, 0), spend.q)
        assertTrue(spend.verify("mint.example"))
        assertFalse(spend.verify("other.example"))
    }

    @Test
    fun aWatchOnlyBranchFindsTheSameNotes() {
        val branch = CashBranch.fromSeed(seed, "mint.example")
        val watchOnly = CashBranch.fromCx1(branch.cx1())!!
        assertEquals(branch.notePubkey(NotePurpose.LightningAddress, 7), watchOnly.notePubkey(NotePurpose.LightningAddress, 7))
        assertTrue(Cx1.decode(branch.cx1()) != null)
    }
}
