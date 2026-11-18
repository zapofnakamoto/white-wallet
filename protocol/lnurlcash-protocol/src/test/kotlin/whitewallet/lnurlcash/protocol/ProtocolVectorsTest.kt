@file:OptIn(RevealsNoteSecret::class)

package whitewallet.lnurlcash.protocol

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.BearerNote
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.testkit.Vectors
import whitewallet.testkit.int
import whitewallet.testkit.long
import whitewallet.testkit.obj
import whitewallet.testkit.objects
import whitewallet.testkit.str
import whitewallet.testkit.strOrNull

class ProtocolVectorsTest {
    @Test
    fun `mutation responses are classified`() {
        for (c in Vectors.conformance("responses").objects("cases")) {
            val type =
                when (c.str("op")) {
                    "split" -> MutationType.Split
                    "melt" -> MutationType.Melt
                    else -> MutationType.Rotate
                }
            // This wallet always names cp1 outputs, so it runs the cp1 cases as written and skips those
            // that model a legacy hash output (no "output"/"change" key) expecting a bare OK to be enough.
            val cp1Outputs = c.strOrNull("output") == "cp1" || c.strOrNull("change") == "cp1"
            val legacyOnly = !cp1Outputs && c.str("expect") == "ok" && type != MutationType.Melt
            if (legacyOnly) continue
            // Their `c` is a placeholder that no cs1 decodes as (an hrp with no amount, a recovery id of 0xab), and a certificate owed
            // that doesn't decode is as good as missing: unverifiable here, where the vectors say ok.
            val expected =
                if (c.str("name") in PLACEHOLDER_CERTIFICATE) {
                    assertEquals("ok", c.str("expect"), c.str("name"))
                    "unverifiable"
                } else {
                    c.str("expect")
                }
            val result =
                when {
                    c["transportError"]?.jsonPrimitive?.booleanOrNull == true -> HttpResult.TransportFailure("connection reset")
                    c["timeout"]?.jsonPrimitive?.booleanOrNull == true -> HttpResult.TransportFailure("timeout")
                    else -> HttpResult.Response(c.int("http"), (c["body"] as? JsonObject)?.toString() ?: c.str("bodyRaw"))
                }
            val outcome = MutationClassifier.classify(type, result)
            val actual =
                when (outcome) {
                    is MutationOutcome.Confirmed -> "ok"
                    is MutationOutcome.Unverifiable -> "unverifiable"
                    is MutationOutcome.Ambiguous -> "ambiguous"
                    is MutationOutcome.Rejected ->
                        when (outcome.kind) {
                            RejectionKind.Pending -> "pending"
                            RejectionKind.Spent -> "spent"
                            RejectionKind.NoteUnknown -> "unknown"
                            else -> "error"
                        }
                }
            assertEquals(expected, actual, c.str("name"))
        }
    }

    @Test
    fun `a bare OK for a cp1 rotate is unverifiable but landed`() {
        val outcome = MutationClassifier.classify(MutationType.Rotate, HttpResult.Response(200, """{"status":"OK"}"""))
        assertIs<MutationOutcome.Unverifiable>(outcome)
    }

    @Test
    fun `an answer outside 2xx is ambiguous whatever its body says`() {
        val c = certificate()
        val answers =
            listOf(
                502 to """{"status":"ERROR","reason":"Bad Gateway"}""",
                504 to """{"status":"ERROR","reason":"upstream request timeout"}""",
                500 to """{"status":"OK"}""",
                302 to """{"status":"OK","c":"$c"}""",
                199 to """{"status":"OK","c":"$c"}""",
            )
        for ((status, body) in answers) {
            for (type in MutationType.entries) {
                val outcome = MutationClassifier.classify(type, HttpResult.Response(status, body))
                assertEquals(MutationOutcome.Ambiguous("HTTP $status"), outcome, "$type $status $body")
            }
        }
    }

    @Test
    fun `a status spelled otherwise than LUD-01 is ambiguous`() {
        val c = certificate()
        for (body in listOf("""{"status":"ok","c":"$c"}""", """{"status":"Ok","c":"$c"}""", """{"status":"Error","reason":"pending"}""")) {
            for (type in MutationType.entries) {
                assertIs<MutationOutcome.Ambiguous>(MutationClassifier.classify(type, HttpResult.Response(200, body)), "$type $body")
            }
        }
    }

    @Test
    fun `a certificate owed that doesn't decode as a cs1 is no certificate`() {
        val c = certificate()
        fun classify(type: MutationType, body: String) = MutationClassifier.classify(type, HttpResult.Response(200, body))
        assertIs<MutationOutcome.Confirmed>(classify(MutationType.Rotate, """{"status":"OK","c":"$c"}"""))
        assertIs<MutationOutcome.Confirmed>(classify(MutationType.Split, """{"status":"OK","c":"$c","c2":"$c"}"""))
        for (bad in listOf("not a certificate", "cs1x", "ab".repeat(65), c.dropLast(1) + if (c.last() == 'q') "p" else "q")) {
            assertIs<MutationOutcome.Unverifiable>(classify(MutationType.Rotate, """{"status":"OK","c":"$bad"}"""), bad)
            assertIs<MutationOutcome.Unverifiable>(classify(MutationType.Merge, """{"status":"OK","sig":"$bad"}"""), bad)
            assertIs<MutationOutcome.Unverifiable>(classify(MutationType.Split, """{"status":"OK","c":"$bad","c2":"$c"}"""), bad)
            assertIs<MutationOutcome.Unverifiable>(classify(MutationType.Split, """{"status":"OK","c":"$c","c2":"$bad"}"""), bad)
            // Nothing is owed for a melt or a transfer: whatever comes along is not looked at.
            assertIs<MutationOutcome.Confirmed>(classify(MutationType.Melt, """{"status":"OK","c":"$bad"}"""), bad)
            assertIs<MutationOutcome.Confirmed>(classify(MutationType.Transfer, """{"status":"OK","c":"$bad"}"""), bad)
        }
    }

    private fun certificate(): String = Certificates.sign("mint.example", 21_000, ByteVector32.One, PrivateKey(ByteVector32.One)).encode()

    private companion object {
        /** The cases of `responses.json` whose certificate is a placeholder no cs1 decodes as. */
        val PLACEHOLDER_CERTIFICATE = setOf("cp1 output certified", "cp1 output certified under both names")
    }

    @Test
    fun `minting pay requests`() {
        val v = Vectors.conformance("pay-request")
        for (c in v.objects("accepted")) {
            val response = LnurlResponse.parse(c.obj("body"))
            val expectedLink = c.strOrNull("withdrawLink")
            if (expectedLink == null) {
                assertFailsWith<ProtocolViolation>(c.str("name")) { MintingPayRequest.from(response) }
                continue
            }
            val minting = MintingPayRequest.from(response)
            assertEquals(whitewallet.lnurl.Lnurl.resolve(expectedLink), minting.withdrawUrl, c.str("name"))
            val fee = c["mintFee"] as? JsonObject
            assertEquals(
                fee?.let { MintFee(it.getValue("baseFeeMsat").jsonPrimitive.long, it.getValue("feePpm").jsonPrimitive.long) }
                    ?.takeUnless { it.baseMsat == 0L && it.ppm == 0L },
                minting.fee,
            )
        }
        for (c in v.objects("rejected")) {
            assertFailsWith<ProtocolViolation>(c.str("name")) { MintingPayRequest.from(LnurlResponse.parse(c.obj("body"))) }
        }
    }

    @Test
    fun `note info`() {
        val v = Vectors.conformance("withdraw-info")
        val queried = "a".repeat(64)
        for (c in v.objects("accepted")) {
            // The draft after the pinned one answers a burned or pending note with 0 (see protocol/SPEC.md): never a note worth 0.
            if (c.str("name") == "zero value note") {
                val e = assertFailsWith<NoteLookupError> { NoteInfo.from(LnurlResponse.parse(c.obj("body")), queried) }
                assertTrue(e.burnedOrPending)
                continue
            }
            val info = NoteInfo.from(LnurlResponse.parse(c.obj("body")), queried)
            assertEquals(c.obj("body").getValue("maxWithdrawable").jsonPrimitive.long, info.maxWithdrawableMsat)
        }
        for (c in v.objects("rejected")) {
            // LUD-25 only says a mint SHOULD publish its key; the vectors reject a response without one (see protocol/SPEC.md).
            if (c.str("name") == "no mintPubkey") {
                assertNull(NoteInfo.from(LnurlResponse.parse(c.obj("body")), queried).mintPubkey)
                continue
            }
            // A key that isn't one is its own violation, which a wallet with the mint's key pinned reads as a changed key.
            if (c.str("name") == "mintPubkey that is not a compressed secp256k1 key") {
                assertFailsWith<InvalidMintPubkey> { NoteInfo.from(LnurlResponse.parse(c.obj("body")), queried) }
                continue
            }
            assertFailsWith<ProtocolViolation>(c.str("name")) { NoteInfo.from(LnurlResponse.parse(c.obj("body")), queried) }
        }
        assertFailsWith<NoteLookupError> {
            NoteInfo.from(LnurlResponse.parse(JsonObject(mapOf("status" to JsonPrimitive("ERROR")))), queried)
        }
    }

    @Test
    fun `a zero value answer is burned or pending, at k1 and at p`() {
        val k1 = "a".repeat(64)
        val atK1 = zero(""""k1":"$k1",""")
        assertTrue(assertFailsWith<NoteLookupError> { NoteInfo.from(atK1, k1) }.burnedOrPending)
        val e = assertFailsWith<NoteLookupError> { NoteInfo.from(zero(""), null) }
        assertTrue(e.burnedOrPending)
        assertFalse(e.burned)
        assertNull(e.reason)
    }

    @Test
    fun `a zero value answer with a certificate is still burned or pending`() {
        val e = assertFailsWith<NoteLookupError> { NoteInfo.from(zero(""""c":"${certificate()}","""), null) }
        assertTrue(e.burnedOrPending)
    }

    @Test
    fun `a zero value answer echoing another k1 is a protocol violation`() {
        assertFailsWith<ProtocolViolation> { NoteInfo.from(zero(""""k1":"${"b".repeat(64)}","""), "a".repeat(64)) }
    }

    /** A lookup's answer worth 0, with [extra] fields (each ending in a comma). */
    private fun zero(extra: String): LnurlResponse =
        LnurlResponse.parse(
            """{"tag":"withdrawRequest","callback":"https://mint.example/w/cb",$extra"minWithdrawable":0,"maxWithdrawable":0,""" +
                """"mintPubkey":"034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"}"""
        )!!

    @Test
    fun `mutation urls`() {
        val p1 = BearerNote.q(ByteVector32.One)
        val p2 = BearerNote.q(ByteVector32.Zeroes)
        val rotate = Requests.rotate("https://mint.example/w/cb?token=abc", "ck1abc", p1)
        assertEquals(listOf("token", "k1", "p1"), whitewallet.lnurl.Lnurl.queryParams(rotate).map { it.first })
        val split = Requests.split("https://mint.example/w/cb", listOf("a", "b"), 5000, p1, p2)
        assertEquals(listOf("k1" to "a", "k1" to "b", "amount" to "5000"), whitewallet.lnurl.Lnurl.queryParams(split).take(3))
        assertFailsWith<IllegalArgumentException> { Requests.merge("https://mint.example/w/cb", listOf("a"), p1) }
        assertEquals("http://localhost:3338/.well-known/lnurlw/mint", Requests.serviceWithdrawDiscoveryUrl("localhost:3338"))
        val transfer = Requests.transfer("https://mint.example/p/bob", 21_000, "ck1abc")
        assertEquals("https://mint.example/p/bob?amount=21000&k1=ck1abc", transfer)
    }

    @Test
    fun `registration requests`() {
        assertEquals(
            FormRequest("POST", "https://mint.example/.well-known/lnurlp/alice", listOf("cx1" to "cx1abc", "sig" to "00")),
            Registration.register("mint.example", "Alice", "cx1abc", "00"),
        )
        assertEquals(
            FormRequest("PUT", "https://mint.example/.well-known/lnurlp/alice", listOf("cx1" to "cx1new", "sig" to "01", "newsig" to "02")),
            Registration.update("mint.example", "alice", "cx1new", "01", "02"),
        )
        assertEquals(
            FormRequest("DELETE", "https://mint.example/.well-known/lnurlp/alice", listOf("sig" to "03")),
            Registration.unregister("mint.example", "alice", "03"),
        )
    }

    @Test
    fun `mint fees`() {
        val v = Vectors.conformance("fees")
        for (c in v.objects("parse")) {
            val metadata = c.str("metadata")
            val pay =
                LnurlResponse.PayRequest(
                    callback = "https://mint.example/p/cb",
                    minSendable = 1000,
                    maxSendable = 2000,
                    metadata = metadata,
                    commentAllowed = null,
                    raw = JsonObject(emptyMap()),
                )
            val expect = c["expect"] as? JsonObject
            assertEquals(
                expect?.let {
                    MintFee(
                        it.getValue("baseFeeMsat").jsonPrimitive.long,
                        it.getValue("feePpm").jsonPrimitive.long,
                        it.getValue("addressFeeMsat").jsonPrimitive.longOrNull,
                    )
                },
                MintFee.fromMetadata(pay.metadataEntries),
                c.strOrNull("why") ?: metadata,
            )
        }
        for (c in v.objects("apply")) {
            val fee = c.obj("fee")
            assertEquals(
                c.long("expect"),
                MintFee(fee.long("baseFeeMsat"), fee.long("feePpm")).noteValue(c.long("grossMsat")),
                c.strOrNull("why"),
            )
        }
    }

    @Test
    fun `note links`() {
        val bearer = Vectors.spec("25-vectors.json").obj("bearer_note")
        val link = assertNotNull(NoteLink.parse(bearer.str("note_url")))
        assertEquals("https://mint.example/w", link.withdrawUrl)
        assertIs<Spend.Bearer>(link.spend)
        assertEquals(bearer.obj("certificate").str("cs1"), link.certificate)
        assertEquals(bearer.str("note_url"), link.encode())
        assertEquals("mint.example", link.host)

        val ck1 = Vectors.spec("25-vectors.json").obj("key_path_spend").str("ck1")
        assertIs<Spend.KeyPath>(assertNotNull(NoteLink.parse("lightning:lnurlw://localhost:3338/w?k1=$ck1")).spend)
        // A withdraw link that already has a query takes the note's parameters after `&` (LUD-25 `<?|&>k1=`).
        val withQuery = assertNotNull(NoteLink.parse("lnurlw://mint.example/w?mint=a&k1=$ck1"))
        assertEquals("https://mint.example/w?mint=a", withQuery.withdrawUrl)
        assertEquals("lnurlw://mint.example/w?mint=a&k1=$ck1", withQuery.encode())

        for (c in Vectors.conformance("input-resolution").objects("note")) {
            val parsed = NoteLink.parse(c.str("input"))
            if (c.strOrNull("expect") == null) assertNull(parsed, c.str("input")) else assertNotNull(parsed, c.str("input"))
        }
    }
}
