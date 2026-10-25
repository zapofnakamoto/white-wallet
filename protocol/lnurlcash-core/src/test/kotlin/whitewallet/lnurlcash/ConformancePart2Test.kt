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
import whitewallet.testkit.Vectors
import whitewallet.testkit.int
import whitewallet.testkit.long
import whitewallet.testkit.obj
import whitewallet.testkit.objects
import whitewallet.testkit.str

/** lnurlcash-conformance `part2.json`: key-path notes, certificates, address proofs and encodings. */
class ConformancePart2Test {
    private val v = Vectors.conformance("part2")

    @Test
    fun branches() {
        for (b in v.objects("branches")) {
            val seed = MnemonicCode.toSeed(b.str("mnemonic"), "")
            assertEquals(b.str("seedHex"), Hex.encode(seed))
            val branch = CashBranch.fromSeed(seed, b.str("host"))
            assertEquals(b.str("branchPubkey"), branch.pubkey.toHex(), b.str("host"))
            assertEquals(b.str("chainCode"), branch.chainCode.toHex())
            assertEquals(b.str("cx1"), branch.cx1())
            val domain = b.str("domain")
            assertEquals(domain, spendDomain(b.str("host")))
            for (n in b.objects("notes")) {
                val purpose = NotePurpose.entries.first { it.value == n.int("purpose") }
                val i = n.long("index")
                val q = branch.notePubkey(purpose, i)
                assertEquals(n.str("notePubkey"), q.toHex())
                assertEquals(n.str("cp1"), Cp1.encode(q))
                assertEquals(n.str("noteSecretKey"), branch.noteSecretKey(purpose, i).value.toHex())
                assertEquals(n.str("sighash"), CanonicalSpend.keyPathSighash(domain, q).toHex())
                val spend = branch.spend(purpose, i, domain)
                assertEquals(n.str("keyPathSignature"), spend.signature.toHex())
                assertEquals(n.str("ck1"), spend.k1)
                assertTrue(assertIs<Spend.KeyPath>(Spend.parse(n.str("ck1"))).verify(domain))
            }
        }
    }

    @Test
    fun certificates() {
        val mintKey = PrivateKey.fromHex(v.obj("mint").str("privateKey"))
        val mintPubkey = PublicKey.fromHex(v.obj("mint").str("mintPubkey"))
        for (c in v.objects("certificates")) {
            val q = ByteVector32(c.str("notePubkey"))
            val cs1 = assertNotNull(Cs1.decode(c.str("cs1")))
            assertEquals(c.long("amountMsat"), cs1.amountMsat)
            assertTrue(Certificates.verify(cs1, q, mintPubkey))
            assertEquals(c.str("cs1"), Certificates.sign(cs1.amountMsat, q, mintKey).encode())
        }
    }

    @Test
    fun `address proofs`() {
        val seeds = v.objects("branches").associate { (it.str("host") to it.str("mnemonic")) to it }
        for (p in v.objects("addressProofs")) {
            val sk = PrivateKey.fromHex(p.str("indexZeroSecretKey"))
            val entry = seeds.values.first { b -> b.objects("notes").any { it.str("noteSecretKey") == sk.value.toHex() } }
            val branch = CashBranch.fromSeed(MnemonicCode.toSeed(entry.str("mnemonic"), ""), entry.str("host"))
            val action = AddressProof.Action.entries.first { it.wire == p.str("action") }
            assertEquals(p.str("message"), AddressProof.message(action, p.str("domain"), p.str("username")))
            assertEquals(p.str("signature"), AddressProof.sign(branch, action, p.str("domain"), p.str("username")))
        }
    }

    @Test
    fun encodings() {
        for (c in v.objects("valid")) {
            assertEquals(c.str("bytes"), decode(c.str("type"), c.str("value")), c.str("why"))
        }
        for (c in v.objects("invalid")) {
            assertNull(decode(c.str("type"), c.str("value")), c.str("why"))
        }
    }

    private fun decode(type: String, value: String): String? = when (type) {
        "cp1" -> Cp1.decode(value)?.toHex()
        "ck1" -> Ck1.decode(value)?.let { (q, sig) -> q.toHex() + sig.toHex() }
        "cx1" -> Cx1.decode(value)?.let { (p, c) -> p.toHex() + c.toHex() }
        "cs1" -> Cs1.decode(value)?.signature?.toHex()
        else -> error("unknown type $type")
    }
}
