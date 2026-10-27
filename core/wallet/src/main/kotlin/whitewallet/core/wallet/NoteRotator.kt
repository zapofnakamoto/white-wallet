package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.protocol.Requests

sealed interface RotateResult {
    /** The note now lives at wallet key (purpose 0) [toIndex]. */
    data class Rotated(val fromIndex: Long, val toIndex: Long, val amountMsat: Long, val certified: Boolean) : RotateResult

    /** Nothing changed. [noAnswer] is true when the mint could not be reached at all. */
    data class Failed(val reason: String?, val noAnswer: Boolean) : RotateResult

    /** Sent, outcome unknown; reconciliation settles it on the next refresh. */
    data object Pending : RotateResult

    /** There is no outstanding note of this account at that key. Nothing was sent. */
    data class NotRotatable(val reason: String) : RotateResult
}

data class RotateAllResult(val rotated: Int, val failed: Int, val pending: Int, val totalMsat: Long, val stoppedEarly: String?)

/**
 * Rotates the wallet's own notes onto fresh wallet keys (LUD-26 purpose 0), renewing them under a new spend.
 * A rotate is free, so the balance does not change.
 */
internal class NoteRotator(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
    private val log = ActivityLog(wallet)

    /** [automatic]: done by a note policy rather than by the user (recorded in the history as such). */
    suspend fun rotate(accountId: String, host: String, purpose: Int, index: Long, automatic: Boolean = false): RotateResult {
        val note = outstandingNote(accountId, host, purpose, index)
            ?: return RotateResult.NotRotatable("no outstanding note at #$index")
        return rotate(accountId, note, automatic)
    }

    /** Every outstanding note of [purpose] on [host], one at a time. Stops if the mint stops answering. */
    suspend fun rotateAll(accountId: String, host: String, purpose: Int, automatic: Boolean = false): RotateAllResult {
        val notes = wallet.store.notes(accountId).first()
            .filter { it.host == host && it.purpose == purpose && it.isRotatable() }
            .sortedBy { it.index }
        var rotated = 0
        var failed = 0
        var pending = 0
        var total = 0L
        for (note in notes) {
            when (val r = rotate(accountId, note, automatic)) {
                is RotateResult.Rotated -> { rotated++; total += r.amountMsat }
                is RotateResult.Failed -> {
                    failed++
                    if (r.noAnswer) return RotateAllResult(rotated, failed, pending, total, r.reason ?: "no answer")
                }
                RotateResult.Pending -> pending++
                is RotateResult.NotRotatable -> Unit
            }
        }
        return RotateAllResult(rotated, failed, pending, total, null)
    }

    private suspend fun rotate(accountId: String, note: Note, automatic: Boolean): RotateResult {
        val result = mutator.mutate(accountId, note.host, listOf(note), OperationType.Rotate, listOf(OutputSpec.Own(accountId, NotePurpose.Wallet))) { callback, k1s, outs ->
            Requests.rotate(callback, k1s.single(), outs.single().key)
        }
        return when (result) {
            is MutationResult.Done -> {
                val out = result.outputs.single()
                val now = wallet.now()
                // Keep the developer key page current without a rescan.
                wallet.recordProbe(KeyId(accountId, note.host, note.purpose!!, note.index!!), KeyProbe.Spent(now))
                wallet.recordProbe(
                    KeyId(accountId, note.host, NotePurpose.Wallet.value, out.index!!),
                    KeyProbe.Outstanding(out.amountMsat, out.certificate != null, imported = false, now),
                )
                log.record("op:${result.journalId}", accountId, ActivityKind.Rotated, out.amountMsat.takeIf { it > 0 } ?: note.amountMsat, note.host,
                    automatic = automatic, journalId = result.journalId)
                RotateResult.Rotated(note.index!!, out.index!!, out.amountMsat, out.certificate != null)
            }
            is MutationResult.Refused -> RotateResult.Failed(result.reason ?: result.kind.name, noAnswer = false)
            is MutationResult.Unreachable -> RotateResult.Failed(result.reason, noAnswer = true)
            is MutationResult.Pending -> {
                log.record("op:${result.journalId}", accountId, ActivityKind.Rotated, note.amountMsat, note.host, ActivityStatus.Pending,
                    automatic = automatic, journalId = result.journalId)
                RotateResult.Pending
            }
        }
    }

    private suspend fun outstandingNote(accountId: String, host: String, purpose: Int, index: Long): Note? {
        val q = wallet.branchIfSeeded(accountId, host)?.notePubkey(NotePurpose.entries.first { it.value == purpose }, index) ?: return null
        return wallet.store.note(q.toHex())?.takeIf { it.accountId == accountId && it.isRotatable() }
    }

    private fun Note.isRotatable() = state == NoteState.Outstanding && kind == NoteKind.KeyPath && purpose != null && index != null
}
