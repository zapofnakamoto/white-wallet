package whitewallet.lnurlcash.protocol

import fr.acinq.bitcoin.ByteVector32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import whitewallet.lnurl.LnurlResponse

/** LUD-21 `verify`, as a melt's success answer hands it out: how the wallet follows a melt whose note a lookup answers 0. */
class VerifyAnswerTest {
    private val preimage = "11".repeat(32)

    @Test
    fun `settled with a preimage`() {
        val answer = VerifyAnswer.from(parse("""{"status":"OK","settled":true,"preimage":"$preimage","pr":"lnbc1"}"""))
        assertTrue(answer.settled)
        assertEquals(ByteVector32(preimage), answer.preimage)
    }

    @Test
    fun `not settled`() {
        val answer = VerifyAnswer.from(parse("""{"status":"OK","settled":false,"preimage":null,"pr":"lnbc1"}"""))
        assertFalse(answer.settled)
        assertNull(answer.preimage)
    }

    @Test
    fun `not found is a refusal`() {
        val e = assertFailsWith<NoteLookupError> { VerifyAnswer.from(parse("""{"status":"ERROR","reason":"Not found"}""")) }
        assertEquals("Not found", e.reason)
    }

    @Test
    fun `no settled is a protocol violation`() {
        assertFailsWith<ProtocolViolation> { VerifyAnswer.from(parse("""{"status":"OK","preimage":null,"pr":"lnbc1"}""")) }
        assertFailsWith<ProtocolViolation> { VerifyAnswer.from(parse("""{"status":"OK","settled":"yes","pr":"lnbc1"}""")) }
    }

    @Test
    fun `a preimage that isn't 32 bytes is a protocol violation`() {
        assertFailsWith<ProtocolViolation> { VerifyAnswer.from(parse("""{"status":"OK","settled":true,"preimage":"abcd"}""")) }
    }

    @Test
    fun `a melt answer's verify url`() {
        val raw = json("""{"status":"OK","verify":"https://mint.example/verify/x"}""")
        assertEquals("https://mint.example/verify/x", MutationOutcome.Confirmed(null, null, raw).verifyUrl())
        assertEquals("https://mint.example/verify/x", MutationOutcome.Unverifiable(null, null, raw).verifyUrl())
        assertNull(MutationOutcome.Confirmed(null, null, json("""{"status":"OK"}""")).verifyUrl())
        assertNull(MutationOutcome.Ambiguous("connection dropped").verifyUrl())
    }

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun parse(body: String): LnurlResponse = LnurlResponse.parse(json(body))
}
