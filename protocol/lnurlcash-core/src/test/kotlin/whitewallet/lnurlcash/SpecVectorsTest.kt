package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import whitewallet.testkit.Vectors
import whitewallet.testkit.int
import whitewallet.testkit.long
import whitewallet.testkit.obj
import whitewallet.testkit.objects
import whitewallet.testkit.str

/** LUD-25/26 vectors published in the spec PR itself. */
class SpecVectorsTest {
    private val lud25 = Vectors.spec("25-vectors.json")
    private val lud26 = Vectors.spec("26-vectors.json")

    @Test
    fun `key path spend`() {
        val v = lud25.obj("key_path_spend")
        val sk = PrivateKey.fromHex(v.str("sk"))
        val q = ByteVector32(v.str("Q"))
        assertEquals(q, sk.xOnlyPublicKey().value)
        assertEquals(v.str("cp1"), Cp1.encode(q))
        assertEquals(v.str("prevout_txid"), CanonicalSpend.prevoutHash(v.str("domain")).toHex())
        assertEquals(v.str("spent_script_pubkey"), CanonicalSpend.spentOutput(q).publicKeyScript.toHex())
        assertEquals(v.str("sighash"), CanonicalSpend.keyPathSighash(v.str("domain"), q).toHex())

        val spend = Spend.keyPath(sk, v.str("domain"))
        assertEquals(v.str("sig"), spend.signature.toHex())
        assertEquals(v.str("ck1"), spend.k1)

        val parsed = assertIs<Spend.KeyPath>(Spend.parse(v.str("ck1")))
        assertTrue(parsed.verify("mint.example"))
        assertFalse(parsed.verify("other.example"), "a ck1 must fail at any other domain")
    }

    @Test
    fun certificates() {
        val v = lud25.obj("certificates")
        val mintKey = PrivateKey.fromHex(v.str("service_sk"))
        val mintPubkey = PublicKey.fromHex(v.str("mintPubkey"))
        assertEquals(mintPubkey, mintKey.publicKey())
        val q = ByteVector32(v.str("Q"))
        for (c in v.objects("cases")) {
            val amount = c.long("amount_msat")
            assertEquals(c.str("message"), Certificates.message(amount, q))
            assertEquals(c.str("digest"), Hex.encode(Certificates.digest(c.str("message"))))
            val cs1 = assertNotNull(Cs1.decode(c.str("cs1")))
            assertEquals(amount, cs1.amountMsat)
            assertEquals(c.str("signature"), cs1.signature.toHex())
            assertTrue(Certificates.verify(cs1, q, mintPubkey))
            assertFalse(Certificates.verify(cs1.copy(amountMsat = amount + 1), q, mintPubkey))
            assertEquals(c.str("cs1"), Certificates.sign(amount, q, mintKey).encode())
        }
    }

    @Test
    fun `bearer note`() {
        val v = lud25.obj("bearer_note")
        val preimage = ByteVector32(v.str("preimage"))
        val h = ByteVector32(v.str("h"))
        assertEquals(h, BearerNote.hash(preimage))
        assertEquals(v.str("leaf"), BearerNote.leaf(h).toHex())
        assertEquals(v.str("tapleaf_hash"), BearerNote.tapleafHash(BearerNote.leaf(h)).toHex())
        assertEquals(v.str("H"), BearerNote.NUMS.toHex())
        val q = ByteVector32(v.str("Q"))
        assertEquals(q, BearerNote.q(h))
        assertEquals(v.str("control_block"), BearerNote.controlBlock(h).toHex())
        assertEquals(v.str("cp1"), Cp1.encode(q))
        assertEquals(q, BearerNote.parseOutput(v.str("h")), "cp1 short form")
        assertEquals(q, BearerNote.parseOutput(v.str("cp1")))
        assertEquals(v.str("cw1"), BearerNote.cw1(preimage).encode())

        val short = assertIs<Spend.Bearer>(Spend.parse(v.str("preimage")))
        assertEquals(q, short.q)
        val full = assertIs<Spend.Bearer>(Spend.parse(v.str("cw1")))
        assertEquals(preimage, full.preimage)
        assertEquals(v.str("cw1"), full.k1)

        val cert = v.obj("certificate")
        val cs1 = assertNotNull(Cs1.decode(cert.str("cs1")))
        assertEquals(cert.str("message"), Certificates.message(cs1.amountMsat, q))
        val mintPubkey = PublicKey.fromHex(lud25.obj("certificates").str("mintPubkey"))
        assertTrue(Certificates.verify(cs1, q, mintPubkey))
    }

    @Test
    fun derivation() {
        for (d in lud26.objects("derivation")) {
            val branch = CashBranch.fromSeed(Hex.decode(d.str("seed")), d.str("domain"))
            assertEquals(d.str("P").substring(2), branch.pubkey.toHex())
            assertEquals(d.str("chaincode"), branch.chainCode.toHex())
            assertEquals(d.str("cx1"), branch.cx1())
            val watchOnly = assertNotNull(CashBranch.fromCx1(d.str("cx1")))
            for (n in d.objects("notes")) {
                val purpose = NotePurpose.entries.first { it.value == n.int("purpose") }
                val i = n.long("i")
                assertEquals(n.str("t"), branch.tweak(purpose, i).value.toHex())
                assertEquals(n.str("Q").substring(2), branch.notePubkey(purpose, i).toHex())
                assertEquals(n.str("Q").substring(2), watchOnly.notePubkey(purpose, i).toHex())
                assertEquals(n.str("sk"), branch.noteSecretKey(purpose, i).value.toHex())
                assertEquals(n.str("cp1"), Cp1.encode(branch.notePubkey(purpose, i)))
            }
        }
    }

    @Test
    fun `registration proofs`() {
        val r = lud26.obj("registration")
        val d = lud26.objects("derivation")[1]
        val branch = CashBranch.fromSeed(Hex.decode(d.str("seed")), d.str("domain"))
        for ((action, key) in listOf(AddressProof.Action.Register to "register", AddressProof.Action.Unregister to "unregister")) {
            val v = r.obj(key)
            assertEquals(v.str("message"), AddressProof.message(action, r.str("domain"), r.str("username")))
            assertEquals(v.str("sig"), AddressProof.sign(branch, action, r.str("domain"), r.str("username")))
        }
    }

    @Test
    fun `bolt11 amounts`() {
        assertEquals("10n", Bolt11Amount.encode(1000))
        assertEquals("210u", Bolt11Amount.encode(21_000_000))
        assertEquals("1", Bolt11Amount.encode(100_000_000_000))
        assertEquals("15n", Bolt11Amount.encode(1500))
        assertEquals("10p", Bolt11Amount.encode(1))
        assertEquals(1500L, Bolt11Amount.decode("15n"))
        assertEquals(1L, Bolt11Amount.decode("10p"))
        assertEquals(null, Bolt11Amount.decode("15p"), "not a whole msat")
        assertEquals(null, Bolt11Amount.decode("010n"))
        assertEquals(null, Bolt11Amount.decode(""))
    }

    @Test
    fun `unknown script spends are identified but not treated as bearer notes`() {
        val preimage = ByteVector32(lud25.obj("bearer_note").str("preimage"))
        val cw1 = BearerNote.cw1(preimage)
        val timelocked = cw1.copy(lockTime = 1_700_000_000)
        val spend = assertIs<Spend.Script>(Spend.parse(timelocked.encode()))
        assertEquals(BearerNote.q(BearerNote.hash(preimage)), spend.q)
        val wrongWitness = cw1.copy(witness = listOf(ByteVector(ByteArray(32))))
        assertIs<Spend.Script>(Spend.parse(wrongWitness.encode()))
    }
}
