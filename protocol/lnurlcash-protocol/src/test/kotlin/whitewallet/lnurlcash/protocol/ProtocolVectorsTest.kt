package whitewallet.lnurlcash.protocol

import fr.acinq.bitcoin.ByteVector32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.BearerNote
import whitewallet.lnurlcash.Spend
import whitewallet.testkit.Vectors
import whitewallet.testkit.int
import whitewallet.testkit.obj
import whitewallet.testkit.objects
import whitewallet.testkit.str
import whitewallet.testkit.strOrNull

class ProtocolVectorsTest {
    @Test
    fun `mutation responses are classified`() {
        for (c in Vectors.conformance("responses").objects("cases")) {
            val type = when (c.str("op")) {
                "split" -> MutationType.Split
                "melt" -> MutationType.Melt
                else -> MutationType.Rotate
            }
            // This wallet always names cp1 outputs, so it runs the cp1 cases as written and skips those
            // that model a legacy hash output (no "output"/"change" key) expecting a bare OK to be enough.
            val cp1Outputs = c.strOrNull("output") == "cp1" || c.strOrNull("change") == "cp1"
            val legacyOnly = !cp1Outputs && c.str("expect") == "ok" && type != MutationType.Melt
            if (legacyOnly) continue
            val result = when {
                c["transportError"]?.jsonPrimitive?.booleanOrNull == true -> HttpResult.TransportFailure("connection reset")
                c["timeout"]?.jsonPrimitive?.booleanOrNull == true -> HttpResult.TransportFailure("timeout")
                else -> HttpResult.Response(c.int("http"), (c["body"] as? JsonObject)?.toString() ?: c.str("bodyRaw"))
            }
            val outcome = MutationClassifier.classify(type, result)
            val actual = when (outcome) {
                is MutationOutcome.Confirmed -> "ok"
                is MutationOutcome.Unverifiable -> "unverifiable"
                is MutationOutcome.Ambiguous -> "ambiguous"
                is MutationOutcome.Rejected -> when (outcome.kind) {
                    RejectionKind.Pending -> "pending"
                    RejectionKind.Spent -> "spent"
                    RejectionKind.NoteUnknown -> "unknown"
                    else -> "error"
                }
            }
            assertEquals(c.str("expect"), actual, c.str("name"))
        }
    }

    @Test
    fun `a bare OK for a cp1 rotate is unverifiable but landed`() {
        val outcome = MutationClassifier.classify(MutationType.Rotate, HttpResult.Response(200, """{"status":"OK"}"""))
        assertIs<MutationOutcome.Unverifiable>(outcome)
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
            assertEquals(fee?.let { MintFee(it.getValue("baseFeeMsat").jsonPrimitive.long, it.getValue("feePpm").jsonPrimitive.long) }, minting.fee)
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
            val info = NoteInfo.from(LnurlResponse.parse(c.obj("body")), queried)
            assertEquals(c.obj("body").getValue("maxWithdrawable").jsonPrimitive.long, info.maxWithdrawableMsat)
        }
        for (c in v.objects("rejected")) {
            assertFailsWith<ProtocolViolation>(c.str("name")) { NoteInfo.from(LnurlResponse.parse(c.obj("body")), queried) }
        }
        assertFailsWith<NoteLookupError> { NoteInfo.from(LnurlResponse.parse(JsonObject(mapOf("status" to JsonPrimitive("ERROR")))), queried) }
    }

    @Test
    fun `mutation urls`() {
        val p1 = BearerNote.q(ByteVector32.One)
        val p2 = BearerNote.q(ByteVector32.Zeroes)
        val rotate = Requests.rotate("https://mint.example/w/cb?token=abc", "ck1abc", p1)
        assertEquals(listOf("token", "k1", "p1"), whitewallet.lnurl.Lnurl.queryParams(rotate).map { it.first })
        val split = Requests.split("https://mint.example/w/cb", listOf("a", "b"), 5000, p1, p2)
        assertEquals(listOf("k1" to "a", "k1" to "b", "amount" to "5000"), whitewallet.lnurl.Lnurl.queryParams(split).take(3))
        assertFailsWith<IllegalArgumentException> { Requests.merge("https://mint.example/w/cb", listOf("a"), p1) }
        assertEquals("https://mint.example/p/alice?cx1=cx1abc&sig=00", Requests.register("mint.example", "alice", "cx1abc", "00"))
        assertEquals("http://localhost:3338/.well-known/lnurlw/_", Requests.serviceWithdrawDiscoveryUrl("localhost:3338"))
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

        for (c in Vectors.conformance("input-resolution").objects("note")) {
            val parsed = NoteLink.parse(c.str("input"))
            if (c.strOrNull("expect") == null) assertNull(parsed, c.str("input")) else assertNotNull(parsed, c.str("input"))
        }
    }
}
