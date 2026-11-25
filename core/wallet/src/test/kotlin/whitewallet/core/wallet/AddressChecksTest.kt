package whitewallet.core.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An address's check is saved as one line, and read back the same; a check that failed isn't saved. */
class AddressChecksTest {
    @Test
    fun `every answer is read back as saved`() {
        val answers =
            listOf(AddressStatus.Ours, AddressStatus.Unconfirmed, AddressStatus.Available, AddressStatus.Taken) +
                AddressProblem.entries.map(AddressStatus::Invalid)
        for (status in answers) {
            val check = AddressCheckRecord(1_700_000_000_000, status)
            assertEquals("alice@mint.example" to check, AddressChecks.decode(assertNotNullEntry("alice@mint.example", check)))
        }
    }

    @Test
    fun `a failed check isn't saved`() =
        assertNull(AddressChecks.encode("alice@mint.example", AddressCheckRecord(1, AddressStatus.CheckFailed("no answer"))))

    @Test
    fun `a line this version can't read is dropped`() {
        assertNull(AddressChecks.decode("alice@mint.example 1 someday"))
        assertNull(AddressChecks.decode("alice@mint.example ours"))
        assertNull(AddressChecks.decode("alice@mint.example x ours"))
    }

    private fun assertNotNullEntry(address: String, check: AddressCheckRecord) =
        kotlin.test.assertNotNull(AddressChecks.encode(address, check))
}
