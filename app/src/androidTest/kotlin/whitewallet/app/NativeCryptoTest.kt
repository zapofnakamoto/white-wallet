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
import whitewallet.lnurlcash.Cx1
import whitewallet.lnurlcash.ServiceRoot
import whitewallet.lnurlcash.Share
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.legacy.LegacyBranch
import whitewallet.lnurlcash.legacy.LegacyCx1
import whitewallet.lnurlcash.legacy.LegacyPurpose

/** The native secp256k1 the app ships (secp256k1-kmp-jni-android) signs and verifies like the JVM build in unit tests. */
@RunWith(AndroidJUnit4::class)
class NativeCryptoTest {
    private val seed =
        MnemonicCode.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")

    /** The platform's PBKDF2-HMAC-SHA512 gives the seed bitcoin-kmp does, so restores find the same keys. */
    @Test
    fun recoveryPhraseSeedMatchesBip39() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        assertArrayEquals(seed, RecoveryPhrase.seed(words))
    }

    /** Values from lnurlcash-conformance `part2.json` (v0.16.2) for this mnemonic at mint.example. */
    @Test
    fun notesAndSharesDeriveAsInTheConformanceSuite() {
        val account = ServiceRoot.fromSeed(seed, "mint.example").account(0)
        assertEquals(
            "9820be6eb2fd00e6dd739f029e58f61f4ae19a8ded723fc670db052285be2f80",
            account.walletKey(0).xOnlyPublicKey().value.toHex(),
        )
        val cx1 = "cx1ee6n2jd2ed5d9ygsx5drsf966fs7jvwj35n4p6ur583ae8l4e3gq9c9wy0c8q8jzf0c9ld6llpv7gs8au2upca66gqnju2jcr4cwvrlnng987r"
        assertEquals(cx1, account.share(0).cx1())
        assertTrue(Cx1.decode(cx1) != null)
        assertEquals(account.share(0).notePubkey(3), Share.fromCx1(cx1)!!.notePubkey(3))
    }

    @Test
    fun spendsAreSignedAndVerifiedPerDomain() {
        val spend = Spend.keyPath(ServiceRoot.fromSeed(seed, "mint.example").account(0).walletKey(0), "mint.example")
        assertTrue(spend.verify("mint.example"))
        assertFalse(spend.verify("other.example"))
    }

    @Test
    fun legacy2f17578SpendsAreSignedAndVerifiedPerDomain() {
        val branch = LegacyBranch.fromSeed(seed, "mint.example")
        val spend = branch.spend(LegacyPurpose.Wallet, 0, "mint.example")
        assertEquals(branch.notePubkey(LegacyPurpose.Wallet, 0), spend.q)
        assertTrue(spend.verify("mint.example"))
        assertFalse(spend.verify("other.example"))
    }

    @Test
    fun legacy2f17578AWatchOnlyBranchFindsTheSameNotes() {
        val branch = LegacyBranch.fromSeed(seed, "mint.example")
        val watchOnly = LegacyBranch.fromCx1(branch.cx1())!!
        assertEquals(branch.notePubkey(LegacyPurpose.LightningAddress, 7), watchOnly.notePubkey(LegacyPurpose.LightningAddress, 7))
        assertTrue(LegacyCx1.decode(branch.cx1()) != null)
    }
}
