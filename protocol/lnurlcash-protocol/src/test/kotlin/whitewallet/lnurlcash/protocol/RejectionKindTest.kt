package whitewallet.lnurlcash.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A mint's reason is matched exactly, never by a word in it: free text that names a timelock or an error isn't a burned note. */
class RejectionKindTest {
    @Test
    fun `the spec's reasons and lnurl-mint's sentences are matched exactly`() {
        val kinds =
            mapOf(
                "already in use" to RejectionKind.AlreadyInUse,
                "pending" to RejectionKind.Pending,
                "rate limited" to RejectionKind.RateLimited,
                "insufficient value" to RejectionKind.InsufficientValue,
                "too many k1" to RejectionKind.TooManyK1,
                "Note already spent." to RejectionKind.Spent,
                "Invalid or already spent k1." to RejectionKind.Spent,
                "Unknown note." to RejectionKind.NoteUnknown,
                "k1 not found" to RejectionKind.NoteUnknown,
                "Cannot melt into an invoice this mint issued itself." to RejectionKind.OwnInvoice,
                "Invoice already used by an earlier melt - use a fresh one." to RejectionKind.InvoiceUsed,
                " already in use\n" to RejectionKind.AlreadyInUse,
            )
        for ((reason, kind) in kinds) assertEquals(kind, RejectionKind.fromReason(reason), reason)
    }

    @Test
    fun `any other reason is another refusal, whatever words it holds`() {
        val others =
            listOf(
                null,
                "",
                "locktime not reached: the note cannot be spent before 2027-01-01",
                "note is unspent but locked",
                "being spent by a melt in flight",
                "unknown error, try again later",
                "internal error: upstream not found",
                "Already in use",
                "ALREADY IN USE",
                "already in use: p1",
                "Pending",
                "pending melt",
                "Rate limited",
                "rate limited, slow down",
                "Too many k1s (max 100).",
                "note already spent",
                "Unknown note",
                "unknown note.",
                "This mint is sunsetting - splits are disabled.",
            )
        for (reason in others) assertEquals(RejectionKind.Other, RejectionKind.fromReason(reason), reason.toString())
    }

    @Test
    fun `only a lookup answered already in use shows a note burned`() {
        assertTrue(NoteLookupError("already in use").burned)
        assertEquals(RejectionKind.AlreadyInUse, NoteLookupError("already in use").kind)
        for (reason in listOf("Note already spent.", "Invalid or already spent k1.", "Unknown note.", "k1 not found", "pending", null)) {
            assertFalse(NoteLookupError(reason).burned, reason.toString())
        }
        assertEquals(RejectionKind.Spent, NoteLookupError("Note already spent.").kind, "a hint, kept as such")
        assertEquals(RejectionKind.NoteUnknown, NoteLookupError("Unknown note.").kind)
    }
}
