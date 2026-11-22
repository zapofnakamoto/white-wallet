package whitewallet.lnurlcash.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import whitewallet.lnurl.LnurlResponse

/** A service's ERROR where a request was expected is its refusal, in its own words; any other wrong answer is a plain violation. */
class ServiceRefusedTest {
    @Test
    fun `an error in place of a payRequest keeps the service's reason`() {
        val e = assertFailsWith<ServiceRefused> { MintingPayRequest.from(parse("""{"status":"ERROR","reason":"Closed"}""")) }
        assertEquals("Closed", e.reason)
    }

    @Test
    fun `an error in place of a withdrawRequest keeps the service's reason, or none`() {
        assertNull(assertFailsWith<ServiceRefused> { WithdrawDiscovery.from(parse("""{"status":"ERROR"}""")) }.reason)
    }

    @Test
    fun `another answer is no refusal`() {
        val e = assertFailsWith<ProtocolViolation> { MintingPayRequest.from(parse("""{"status":"OK"}""")) }
        assertFalse(e is ServiceRefused)
    }

    private fun parse(body: String): LnurlResponse = LnurlResponse.parse(Json.parseToJsonElement(body).jsonObject)
}
