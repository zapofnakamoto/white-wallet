@file:OptIn(RevealsNoteSecret::class)

package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import whitewallet.lnurlcash.legacy.LegacyAddressProof
import whitewallet.lnurlcash.legacy.LegacyBranch
import whitewallet.lnurlcash.legacy.LegacyCertificates
import whitewallet.lnurlcash.legacy.LegacyPurpose
import whitewallet.testkit.Vectors
import whitewallet.testkit.arr
import whitewallet.testkit.int
import whitewallet.testkit.long
import whitewallet.testkit.obj
import whitewallet.testkit.objects
import whitewallet.testkit.str

/** LUD-25/26 vectors published in the spec PR itself, and those of the legacy draft 2f17578. */
class SpecVectorsTest {
    private val lud25 = Vectors.spec("25-vectors.json")
    private val lud26 = Vectors.spec("26-vectors.json")
    private val legacy25 = Vectors.legacySpec("25-vectors.json")
    private val legacy26 = Vectors.legacySpec("26-vectors.json")

    @Test fun `key path spend`() = keyPathSpend(lud25)

    @Test fun `legacy 2f17578 - key path spend`() = keyPathSpend(legacy25)

    private fun keyPathSpend(vectors: JsonObject) {
        val v = vectors.obj("key_path_spend")
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
        val domain = v.str("domain")
        for (c in v.objects("cases")) {
            val amount = c.long("amount_msat")
            assertEquals(c.str("message"), Certificates.message(domain, amount, q))
            assertEquals(c.str("digest"), Hex.encode(Certificates.digest(c.str("message"))))
            val cs1 = assertNotNull(Cs1.decode(c.str("cs1")))
            assertEquals(amount, cs1.amountMsat)
            assertEquals(c.str("signature"), cs1.signature.toHex())
            assertTrue(Certificates.verify(cs1, domain, q, mintPubkey))
            assertFalse(Certificates.verify(cs1.copy(amountMsat = amount + 1), domain, q, mintPubkey))
            assertFalse(Certificates.verify(cs1, "other.example", q, mintPubkey), "a certificate names one mint")
            assertEquals(c.str("cs1"), Certificates.sign(domain, amount, q, mintKey).encode())
        }
    }

    @Test
    fun `legacy 2f17578 - certificates`() {
        val v = legacy25.obj("certificates")
        val mintKey = PrivateKey.fromHex(v.str("service_sk"))
        val mintPubkey = PublicKey.fromHex(v.str("mintPubkey"))
        val q = ByteVector32(v.str("Q"))
        for (c in v.objects("cases")) {
            val amount = c.long("amount_msat")
            assertEquals(c.str("message"), LegacyCertificates.message(amount, q))
            assertEquals(c.str("digest"), Hex.encode(Certificates.digest(c.str("message"))))
            val cs1 = assertNotNull(Cs1.decode(c.str("cs1")))
            assertTrue(LegacyCertificates.verify(cs1, q, mintPubkey))
            assertFalse(LegacyCertificates.verify(cs1.copy(amountMsat = amount + 1), q, mintPubkey))
            assertEquals(c.str("cs1"), LegacyCertificates.sign(amount, q, mintKey).encode())
        }
    }

    @Test
    fun `bearer note`() {
        val v = lud25.obj("bearer_note")
        bearerNote(v)
        val cert = v.obj("certificate")
        val cs1 = assertNotNull(Cs1.decode(cert.str("cs1")))
        val q = ByteVector32(v.str("Q"))
        val domain = lud25.obj("certificates").str("domain")
        assertEquals(cert.str("message"), Certificates.message(domain, cs1.amountMsat, q))
        val mintPubkey = PublicKey.fromHex(lud25.obj("certificates").str("mintPubkey"))
        assertTrue(Certificates.verify(cs1, domain, q, mintPubkey))
    }

    @Test
    fun `legacy 2f17578 - bearer note`() {
        val v = legacy25.obj("bearer_note")
        bearerNote(v)
        val cert = v.obj("certificate")
        val cs1 = assertNotNull(Cs1.decode(cert.str("cs1")))
        val q = ByteVector32(v.str("Q"))
        assertEquals(cert.str("message"), LegacyCertificates.message(cs1.amountMsat, q))
        val mintPubkey = PublicKey.fromHex(legacy25.obj("certificates").str("mintPubkey"))
        assertTrue(LegacyCertificates.verify(cs1, q, mintPubkey))
    }

    private fun bearerNote(v: JsonObject) {
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
    }

    @Test
    fun derivation() {
        for (d in lud26.objects("derivation")) {
            val seed = Hex.decode(d.str("seed"))
            val domain = d.str("domain")
            assertEquals(d.str("cashHashingKey"), ServiceRoot.hashingKey(seed).toHex())
            assertEquals(d.arr("d").map { it.jsonPrimitive.long }, ServiceRoot.domainIndices(seed, domain))
            val root = ServiceRoot.fromSeed(seed, domain)
            for (a in d.objects("accounts")) {
                val account = root.account(a.long("account"))
                for (n in a.obj("wallet").objects("notes")) {
                    note(n, account.walletKey(n.long("i")))
                }
                for (n in a.obj("change").objects("notes")) {
                    note(n, account.changeKey(n.long("i")))
                }
                for (s in a.objects("shareable")) {
                    val share = account.share(s.long("share"))
                    assertEquals(s.str("p"), share.proofKey().value.toHex())
                    assertEquals(s.str("P").substring(2), share.xOnly.toHex())
                    assertEquals(s.str("cx1"), share.cx1())
                    val watchOnly = assertNotNull(Share.fromCx1(s.str("cx1")))
                    assertTrue(watchOnly.isWatchOnly)
                    assertEquals(s.str("cx1"), watchOnly.cx1())
                    for (n in s.objects("notes")) {
                        val i = n.long("i")
                        note(n, share.noteSecretKey(i))
                        assertEquals(n.str("Q"), share.notePubkey(i).toHex())
                        assertEquals(n.str("Q"), watchOnly.notePubkey(i).toHex(), "a cx1 derives its notes")
                    }
                    assertFailsWith<IllegalStateException> { watchOnly.noteSecretKey(0) }
                }
            }
        }
    }

    /** One listed note: its secret key, compressed `pk` (either parity), `Q` and `cp1`. */
    private fun note(n: JsonObject, sk: PrivateKey) {
        assertEquals(n.str("sk"), sk.value.toHex(), n.str("path"))
        assertEquals(n.str("pk"), sk.publicKey().toHex())
        assertEquals(n.str("Q"), sk.xOnlyPublicKey().value.toHex())
        assertEquals(n.str("cp1"), Cp1.encode(sk.xOnlyPublicKey().value))
    }

    @Test
    fun `a key path spend of a note whose key has odd y`() {
        val v = lud26.obj("key_path_spend")
        val d = lud26.objects("derivation")[0]
        val sk = ServiceRoot.fromSeed(Hex.decode(d.str("seed")), d.str("domain")).account(0).walletKey(0)
        assertEquals(v.str("sk"), sk.value.toHex())
        assertEquals(v.str("pk"), sk.publicKey().toHex())
        assertFalse(sk.publicKey().isEven())
        val spend = Spend.keyPath(sk, v.str("domain"))
        assertEquals(v.str("sig"), spend.signature.toHex())
        assertEquals(v.str("ck1"), spend.k1)
        assertTrue(assertIs<Spend.KeyPath>(Spend.parse(v.str("ck1"))).verify(v.str("domain")))
    }

    @Test
    fun `registration proofs`() {
        val r = lud26.obj("registration")
        val d = lud26.objects("derivation")[1]
        val account = ServiceRoot.fromSeed(Hex.decode(d.str("seed")), d.str("domain")).account(0)
        val (domain, username) = r.str("domain") to r.str("username")
        val (share0, share1) = account.share(0) to account.share(1)
        assertEquals(r.str("cx1"), share0.cx1())
        assertEquals(r.str("new_cx1"), share1.cx1())

        val register = r.obj("register")
        assertEquals(register.str("message"), AddressProof.registerMessage(domain, username, share0.cx1()))
        assertEquals(register.str("digest"), Hex.encode(Crypto.sha256(register.str("message").encodeToByteArray())))
        assertEquals(register.str("sig"), AddressProof.register(share0, domain, username))

        val (sig, newsig) = AddressProof.update(share0.proofKey(), share1, domain, username)
        val update = r.obj("update")
        assertEquals(update.obj("sig").str("message"), AddressProof.updateMessage(domain, username, share1.cx1()))
        assertEquals(update.obj("sig").str("sig"), sig)
        assertEquals(update.obj("newsig").str("message"), AddressProof.registerMessage(domain, username, share1.cx1()))
        assertEquals(update.obj("newsig").str("sig"), newsig)

        val unregister = r.obj("unregister")
        assertEquals(unregister.str("message"), AddressProof.unregisterMessage(domain, username))
        assertEquals(unregister.str("sig"), AddressProof.unregister(share1, domain, username))
    }

    @Test
    fun `legacy 2f17578 - derivation`() {
        for (d in legacy26.objects("derivation")) {
            val branch = LegacyBranch.fromSeed(Hex.decode(d.str("seed")), d.str("domain"))
            assertEquals(d.str("P").substring(2), branch.pubkey.toHex())
            assertEquals(d.str("chaincode"), branch.chainCode.toHex())
            assertEquals(d.str("cx1"), branch.cx1())
            assertEquals(d.str("cx1"), NameSeed.of(Hex.decode(d.str("seed")), d.str("domain")), "the name seed is the legacy cx1")
            val watchOnly = assertNotNull(LegacyBranch.fromCx1(d.str("cx1")))
            for (n in d.objects("notes")) {
                val purpose = LegacyPurpose.entries.first { it.value == n.int("purpose") }
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
    fun `legacy 2f17578 - registration proofs`() {
        val r = legacy26.obj("registration")
        val d = legacy26.objects("derivation")[1]
        val branch = LegacyBranch.fromSeed(Hex.decode(d.str("seed")), d.str("domain"))
        for ((action, key) in
            listOf(LegacyAddressProof.Action.Register to "register", LegacyAddressProof.Action.Unregister to "unregister")) {
            val v = r.obj(key)
            assertEquals(v.str("message"), LegacyAddressProof.message(action, r.str("domain"), r.str("username")))
            assertEquals(v.str("sig"), LegacyAddressProof.sign(branch, action, r.str("domain"), r.str("username")))
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
