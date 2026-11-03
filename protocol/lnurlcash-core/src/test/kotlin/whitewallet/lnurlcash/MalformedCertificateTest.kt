package whitewallet.lnurlcash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A certificate comes from a link, or a mint's answer: whatever it holds, reading it gives one or none, never throws. */
class MalformedCertificateTest {
    private val signature = ByteArray(65) { 1 }

    @Test
    fun `an amount too large for any note is no certificate`() {
        for (amount in listOf("92233720368", "9223372036854775807m", "99999999999999999999999u")) {
            val value = Bech32m.encode("cs$amount", signature)
            assertNull(runCatching { Cs1.decode(value) }.getOrElse { throw AssertionError("cs$amount threw $it", it) }, amount)
        }
    }

    @Test
    fun `a certificate that isn't one is none`() {
        for (value in listOf("", "cs1", "xyz", Bech32m.encode("cs10n", ByteArray(64)), Bech32m.encode("cw10n", signature), Bech32m.encode("cs", signature))) {
            assertNull(runCatching { Cs1.decode(value) }.getOrElse { throw AssertionError("'$value' threw $it", it) }, value)
        }
    }

    @Test
    fun `the largest amount that fits is read`() {
        assertEquals(9_223_372_036_800_000_000, Cs1.decode(Bech32m.encode("cs92233720368m", signature))?.amountMsat)
    }
}
