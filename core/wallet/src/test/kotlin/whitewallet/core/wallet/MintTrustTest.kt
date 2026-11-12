@file:OptIn(RevealsNoteSecret::class)

package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.testkit.MockMint

/** Trusting mints: by key, the home mint by default, the brand's with their keys, and the claim hint. */
class MintTrustTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val otherWords = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    private fun withMints(
        brand: (home: String, other: String, otherKey: String) -> List<MintKey> = { _, _, _ -> emptyList() },
        test: suspend (Wallet, InMemoryWalletStore, MockMint, MockMint) -> Unit,
    ) =
        runBlocking<Unit> {
            assumeTrue(MockMint.available, "no conformance suite; run dev/conformance.sh")
            MockMint.start().use { home ->
                MockMint.start().use { other ->
                    // Learn the other mint's key first, for a brand list.
                    val probe = InMemoryWalletStore()
                    val scout = Wallet(probe, InMemorySeedStore(), LnurlcashClient())
                    probe.addAccount("s", "Scout", null, other.host)
                    assertIs<ConnectResult.Connected>(scout.connectMint(other.host))
                    val otherKey = checkNotNull(probe.mint(other.host)).mintPubkey!!

                    val store = InMemoryWalletStore()
                    val wallet =
                        Wallet(
                            store,
                            InMemorySeedStore(mapOf("a" to words, "b" to otherWords)),
                            LnurlcashClient(),
                            policies = NotePolicies.InMemory(ManualStrategy.ID),
                            trust = MintTrustStore.InMemory(),
                            brandTrusted = brand(home.host, other.host, otherKey),
                        )
                    store.addAccount("a", "Main", PrimaryAddress("alice", home.host), host = home.host)
                    assertIs<ConnectResult.Connected>(wallet.connectMint(home.host))
                    test(wallet, store, home, other)
                }
            }
        }

    private suspend fun Wallet.info(host: String) = mints.first().single { it.host == host }

    @Test
    fun `the home mint is trusted by default, and can be untrusted`() = withMints { wallet, _, home, _ ->
        assertEquals(TrustDefault.Home, wallet.info(home.host).trustedByDefault)
        assertTrue(wallet.info(home.host).trusted)
        wallet.setMintTrusted(home.host, false)
        assertFalse(wallet.info(home.host).trusted)
        wallet.setMintTrusted(home.host, true)
        assertEquals(setOf(home.host), wallet.trustedHosts.first())
    }

    @Test
    fun `another mint starts untrusted, and trusting it shows in the claim preview`() = withMints { wallet, _, _, other ->
        val bearer = Spend.Bearer(ByteVector32(Hex.decode("66".repeat(32))))
        other.credit(bearer.h.toHex(), 5_000)
        val link = "lnurlw://${other.host}/w?k1=${bearer.k1}"
        assertFalse(assertIs<ClaimPreview.Ready>(wallet.previewClaim(link)).trusted)

        assertIs<ConnectResult.Connected>(wallet.addMint(other.host))
        assertEquals(null, wallet.info(other.host).trustedByDefault)
        assertFalse(wallet.info(other.host).trusted)
        wallet.setMintTrusted(other.host, true)
        assertTrue(wallet.info(other.host).trusted)
        assertTrue(assertIs<ClaimPreview.Ready>(wallet.previewClaim(link)).trusted)
    }

    @Test
    fun `a mint met by claiming a note doesn't become the home of an account that has none`() = withMints { wallet, store, _, other ->
        store.saveAccount(checkNotNull(store.account("a")).copy(homeHost = null))
        val bearer = Spend.Bearer(ByteVector32(Hex.decode("77".repeat(32))))
        other.credit(bearer.h.toHex(), 5_000)
        assertIs<ClaimResult.Claimed>(
            wallet.claim(assertIs<ClaimPreview.Ready>(wallet.previewClaim("lnurlw://${other.host}/w?k1=${bearer.k1}")))
        )
        assertNotNull(store.mint("a", other.host), "the wallet has the mint its note is at")
        assertEquals(null, store.account("a")?.homeHost)
        assertEquals(null, wallet.info(other.host).trustedByDefault)
        assertFalse(wallet.info(other.host).trusted)
    }

    @Test
    fun `trust names the key, so a changed key is no longer trusted`() = withMints { wallet, store, _, other ->
        wallet.addMint(other.host)
        wallet.setMintTrusted(other.host, true)
        val mint = checkNotNull(store.mint(other.host))
        store.saveMint(mint.copy(mintPubkey = "03" + "11".repeat(32)))
        assertFalse(wallet.info(other.host).trusted)
    }

    @Test
    fun `a brand's mint is trusted with the key it ships, not with another`() =
        withMints({ _, other, key -> listOf(MintKey(other, key)) }) { wallet, _, _, other ->
            wallet.addMint(other.host)
            assertEquals(TrustDefault.Brand, wallet.info(other.host).trustedByDefault)
            assertTrue(wallet.info(other.host).trusted)
        }

    @Test
    fun `a brand entry with a different key trusts nothing`() =
        withMints({ _, other, _ -> listOf(MintKey(other, "02" + "22".repeat(32))) }) { wallet, _, _, other ->
            wallet.addMint(other.host)
            assertFalse(wallet.info(other.host).trusted)
        }

    @Test
    fun `each wallet trusts and pins on its own`() = withMints { wallet, store, _, other ->
        wallet.addMint(other.host)
        wallet.setMintTrusted(other.host, true)
        val key = checkNotNull(store.mint("a", other.host)?.mintPubkey)

        // Another wallet on the same mints: its home is trusted by default, the other mint not, whatever the first wallet chose.
        store.addAccount("b", "Other", null, host = other.host)
        assertIs<ConnectResult.Connected>(wallet.addMint(other.host))
        assertEquals(TrustDefault.Home, wallet.info(other.host).trustedByDefault)
        wallet.setMintTrusted(other.host, false)
        assertFalse(wallet.info(other.host).trusted)
        wallet.switchAccount("a")
        assertTrue(wallet.info(other.host).trusted)

        // Both pinned a key the mint no longer presents: accepting its key in one wallet leaves the other's pin as it was.
        val old = "03" + "11".repeat(32)
        for (id in listOf("a", "b")) store.saveMint(checkNotNull(store.mint(id, other.host)).copy(mintPubkey = old))
        assertTrue(wallet.acceptMintKey(other.host, key))
        assertEquals(key, store.mint("a", other.host)?.mintPubkey)
        assertEquals(old, store.mint("b", other.host)?.mintPubkey)
    }

    @Test
    fun `a mint without a key can't be trusted`() = withMints { wallet, _, _, _ ->
        assertFailsWith<IllegalStateException> { wallet.setMintTrusted("unknown.example", true) }
    }

    @Test
    fun `the brand list parses host with port, and rejects anything else`() {
        val key = "02" + "ab".repeat(32)
        assertEquals(
            listOf(MintKey("localhost:8111", key), MintKey("mint.example", key)),
            TrustedMints.parse(" localhost:8111:$key, Mint.Example:${key.uppercase()} "),
        )
        assertEquals(emptyList(), TrustedMints.parse(""))
        assertFailsWith<IllegalArgumentException> { TrustedMints.parse("mint.example") }
        assertFailsWith<IllegalArgumentException> { TrustedMints.parse("mint.example:abcd") }
    }
}
