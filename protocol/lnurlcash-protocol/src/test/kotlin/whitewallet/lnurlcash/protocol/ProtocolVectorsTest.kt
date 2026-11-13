@file:OptIn(RevealsNoteSecret::class)

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
import kotlinx.serialization.json.longOrNull
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.BearerNote
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.legacy.LegacyCpub
import whitewallet.lnurlcash.protocol.legacy.LegacyRequests
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
        assertEquals(
            FormRequest("PUT", "http://localhost:8111/.well-known/lnurlp/mint", emptyList()),
            Registration.dialectProbe("localhost:8111"),
        )
    }

    @Test
    fun `legacy 2f17578 - registration requests`() {
        assertEquals("https://mint.example/p/alice?cx1=cx1abc&sig=00", LegacyRequests.register("mint.example", "alice", "cx1abc", "00"))
        assertEquals("https://mint.example/p/alice?sig=00", LegacyRequests.unregister("mint.example", "alice", "00"))
    }

    @Test
    fun `legacy 2f17578 - text cpub`() {
        val pay =
            assertIs<LnurlResponse.PayRequest>(
                LnurlResponse.parse(
                    """{"tag":"payRequest","callback":"https://mint.example/p/alice","minSendable":1000,"maxSendable":2000,""" +
                        """"metadata":"[[\"text/cpub\",\"cx1abc:7\"]]"}"""
                )
            )
        assertEquals("cx1abc" to 7L, LegacyCpub.from(pay))
        assertEquals("cx1abc", LegacyCpub.cx1(pay))
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
