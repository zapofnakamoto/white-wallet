@file:OptIn(RevealsNoteSecret::class)

package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import whitewallet.lnurlcash.legacy.LegacyAddressProof
import whitewallet.lnurlcash.legacy.LegacyBranch
import whitewallet.lnurlcash.legacy.LegacyCertificates
import whitewallet.lnurlcash.legacy.LegacyCx1
import whitewallet.lnurlcash.legacy.LegacyPurpose
import whitewallet.testkit.Vectors
import whitewallet.testkit.arr
import whitewallet.testkit.int
import whitewallet.testkit.long
import whitewallet.testkit.obj
import whitewallet.testkit.objects
import whitewallet.testkit.str

/** lnurlcash-conformance `part2.json`: key-path notes, certificates, address proofs and encodings; and the legacy 2f17578 ones. */
class ConformancePart2Test {
    private val v = Vectors.conformance("part2")
    private val legacy = Vectors.legacyConformance("part2")

    /** Every share in the file, by its `cx1`, as derived here. */
    private val shares: Map<String, Share> by lazy {
        v.objects("branches")
            .flatMap { b ->
                val root = ServiceRoot.fromSeed(MnemonicCode.toSeed(b.str("mnemonic"), ""), b.str("host"))
                b.objects("accounts").flatMap { a -> a.objects("shares").map { root.account(a.long("account")).share(it.long("share")) } }
            }
            .associateBy { it.cx1() }
    }

    @Test
    fun branches() {
        for (b in v.objects("branches")) {
            val seed = MnemonicCode.toSeed(b.str("mnemonic"), "")
            assertEquals(b.str("seedHex"), Hex.encode(seed))
            val domain = b.str("domain")
            assertEquals(domain, spendDomain(b.str("host")))
            assertEquals(b.str("cashHashingKey"), ServiceRoot.hashingKey(seed).toHex())
            assertEquals(b.arr("domainIndices").map { it.jsonPrimitive.long }, ServiceRoot.domainIndices(seed, domain), b.str("host"))
            val root = ServiceRoot.fromSeed(seed, b.str("host"))
            for (a in b.objects("accounts")) {
                val account = root.account(a.long("account"))
                for (n in a.objects("wallet")) note(n, account.walletKey(n.long("index")), domain)
                for (n in a.objects("change")) note(n, account.changeKey(n.long("index")), domain)
                for (s in a.objects("shares")) {
                    val share = account.share(s.long("share"))
                    assertEquals(s.str("sharePrivateKey"), share.proofKey().value.toHex())
                    assertEquals(s.str("sharePubkey").substring(2), share.xOnly.toHex())
                    assertEquals(s.str("cx1"), share.cx1())
                    val watchOnly = assertNotNull(Share.fromCx1(s.str("cx1")))
                    for (n in s.objects("notes")) {
                        note(n, share.noteSecretKey(n.long("index")), domain)
                        assertEquals(n.str("notePubkey"), watchOnly.notePubkey(n.long("index")).toHex())
                    }
                }
            }
        }
    }

    private fun note(n: JsonObject, sk: PrivateKey, domain: String) {
        val q = sk.xOnlyPublicKey().value
        assertEquals(n.str("noteSecretKey"), sk.value.toHex(), n.str("path"))
        assertEquals(n.str("notePubkeyCompressed"), sk.publicKey().toHex())
        assertEquals(n.str("notePubkey"), q.toHex())
        assertEquals(n.str("cp1"), Cp1.encode(q))
        assertEquals(n.str("sighash"), CanonicalSpend.keyPathSighash(domain, q).toHex())
        val spend = Spend.keyPath(sk, domain)
        assertEquals(n.str("keyPathSignature"), spend.signature.toHex())
        assertEquals(n.str("ck1"), spend.k1)
        assertTrue(assertIs<Spend.KeyPath>(Spend.parse(n.str("ck1"))).verify(domain))
    }

    @Test
    fun certificates() {
        val mintKey = PrivateKey.fromHex(v.obj("mint").str("privateKey"))
        val mintPubkey = PublicKey.fromHex(v.obj("mint").str("mintPubkey"))
        for (c in v.objects("certificates")) {
            val q = ByteVector32(c.str("notePubkey"))
            val domain = c.str("domain")
            assertEquals(c.str("message"), Certificates.message(domain, c.long("amountMsat"), q))
            val cs1 = assertNotNull(Cs1.decode(c.str("cs1")))
            assertEquals(c.long("amountMsat"), cs1.amountMsat)
            assertTrue(Certificates.verify(cs1, domain, q, mintPubkey))
            assertEquals(c.str("cs1"), Certificates.sign(domain, cs1.amountMsat, q, mintKey).encode())
        }
        for (c in v.objects("legacyCertificates")) {
            val q = ByteVector32(c.str("notePubkey"))
            val cs1 = assertNotNull(Cs1.decode(c.str("cs1")))
            assertTrue(LegacyCertificates.verify(cs1, q, mintPubkey), "a superseded certificate still verifies as legacy")
        }
    }

    @Test
    fun `address proofs`() {
        for (p in v.objects("addressProofs")) {
            val signer = assertNotNull(shares[p.str("signerCx1")], "signer ${p.str("signerCx1")}")
            assertEquals(p.str("signerPubkey"), signer.xOnly.toHex())
            val (domain, username) = p.str("domain") to p.str("username")
            val message =
                when (p.str("action")) {
                    "register" -> AddressProof.registerMessage(domain, username, p.str("cx1"))
                    "update" -> AddressProof.updateMessage(domain, username, p.str("cx1"))
                    "unregister" -> AddressProof.unregisterMessage(domain, username)
                    else -> error("unknown action ${p.str("action")}")
                }
            assertEquals(p.str("message"), message)
            assertEquals(p.str("signature"), AddressProof.sign(message, signer.proofKey()))
        }
    }

    @Test
    fun encodings() =
        encodings(v) { type, value -> if (type == "cx1") Cx1.decode(value)?.let { (c, p) -> c.toHex() + p.toHex() } else null }

    @Test
    fun `legacy 2f17578 - branches`() {
        for (b in legacy.objects("branches")) {
            val seed = MnemonicCode.toSeed(b.str("mnemonic"), "")
            val branch = LegacyBranch.fromSeed(seed, b.str("host"))
            assertEquals(b.str("branchPubkey"), branch.pubkey.toHex(), b.str("host"))
            assertEquals(b.str("chainCode"), branch.chainCode.toHex())
            assertEquals(b.str("cx1"), branch.cx1())
            val domain = b.str("domain")
            for (n in b.objects("notes")) {
                val purpose = LegacyPurpose.entries.first { it.value == n.int("purpose") }
                val i = n.long("index")
                val q = branch.notePubkey(purpose, i)
                assertEquals(n.str("notePubkey"), q.toHex())
                assertEquals(n.str("noteSecretKey"), branch.noteSecretKey(purpose, i).value.toHex())
                val spend = branch.spend(purpose, i, domain)
                assertEquals(n.str("ck1"), spend.k1)
            }
        }
    }

    @Test
    fun `legacy 2f17578 - certificates`() {
        val mintKey = PrivateKey.fromHex(legacy.obj("mint").str("privateKey"))
        val mintPubkey = PublicKey.fromHex(legacy.obj("mint").str("mintPubkey"))
        for (c in legacy.objects("certificates")) {
            val q = ByteVector32(c.str("notePubkey"))
            val cs1 = assertNotNull(Cs1.decode(c.str("cs1")))
            assertTrue(LegacyCertificates.verify(cs1, q, mintPubkey))
            assertEquals(c.str("cs1"), LegacyCertificates.sign(cs1.amountMsat, q, mintKey).encode())
        }
    }

    @Test
    fun `legacy 2f17578 - address proofs`() {
        val seeds = legacy.objects("branches").associate { (it.str("host") to it.str("mnemonic")) to it }
        for (p in legacy.objects("addressProofs")) {
            val sk = PrivateKey.fromHex(p.str("indexZeroSecretKey"))
            val entry = seeds.values.first { b -> b.objects("notes").any { it.str("noteSecretKey") == sk.value.toHex() } }
            val branch = LegacyBranch.fromSeed(MnemonicCode.toSeed(entry.str("mnemonic"), ""), entry.str("host"))
            val action = LegacyAddressProof.Action.entries.first { it.wire == p.str("action") }
            assertEquals(p.str("message"), LegacyAddressProof.message(action, p.str("domain"), p.str("username")))
            assertEquals(p.str("signature"), LegacyAddressProof.sign(branch, action, p.str("domain"), p.str("username")))
        }
    }

    @Test
    fun `legacy 2f17578 - encodings`() =
        encodings(legacy) { type, value -> if (type == "cx1") LegacyCx1.decode(value)?.let { (p, c) -> p.toHex() + c.toHex() } else null }

    private fun encodings(vectors: JsonObject, cx1: (String, String) -> String?) {
        for (c in vectors.objects("valid")) {
            assertEquals(c.str("bytes"), decode(c.str("type"), c.str("value"), cx1), c.str("why"))
        }
        for (c in vectors.objects("invalid")) {
            assertNull(decode(c.str("type"), c.str("value"), cx1), c.str("why"))
        }
    }

    private fun decode(type: String, value: String, cx1: (String, String) -> String?): String? =
        when (type) {
            "cp1" -> Cp1.decode(value)?.toHex()
            "ck1" -> Ck1.decode(value)?.let { (q, sig) -> q.toHex() + sig.toHex() }
            "cx1" -> cx1(type, value)
            "cs1" -> Cs1.decode(value)?.signature?.toHex()
            else -> error("unknown type $type")
        }
}
