package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests

sealed interface MoveResult {
    data class Moved(val amountMsat: Long) : MoveResult

    /** Nothing changed. */
    data class Failed(val reason: MoveFailure, val detail: String? = null) : MoveResult

    /** At least one step's outcome is unknown; reconciliation settles it on the next refresh. */
    data object Pending : MoveResult
}

enum class MoveFailure {
    NothingToMove,
    InsufficientFunds,

    /** The funds are spread over several services and no single one holds the amount. */
    SplitAcrossServices,
    SameAccount,
    UnknownAccount,
    Refused,
}

/**
 * Moves notes from one account to another. Notes never leave their service: the source spends them into keys
 * derived from the target account's own recovery phrase (a rotate, merge or split), so the service sees an
 * ordinary mutation and only split fees apply.
 */
internal class FundsMover(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
    private val log = ActivityLog(wallet)

    suspend fun move(fromId: String, toId: String, amountMsat: Long?): MoveResult {
        if (fromId == toId) return MoveResult.Failed(MoveFailure.SameAccount)
        if (wallet.store.account(toId) == null) return MoveResult.Failed(MoveFailure.UnknownAccount)
        val byHost = outstanding(fromId).groupBy { it.host }
        if (byHost.isEmpty()) return MoveResult.Failed(MoveFailure.NothingToMove)

        if (amountMsat == null) {
            var moved = 0L
            for ((host, notes) in byHost) {
                when (val r = moveAll(fromId, toId, host, notes)) {
                    is MoveResult.Moved -> moved += r.amountMsat
                    else -> return if (moved > 0 && r is MoveResult.Failed) MoveResult.Moved(moved) else r
                }
            }
            return MoveResult.Moved(moved)
        }

        require(amountMsat > 0) { "amount must be positive" }
        if (byHost.values.sumOf { notes -> notes.sumOf { it.amountMsat } } < amountMsat) return MoveResult.Failed(MoveFailure.InsufficientFunds)
        val (host, notes) = byHost.entries.firstOrNull { (_, n) -> n.sumOf { it.amountMsat } >= amountMsat }?.toPair()
            ?: return MoveResult.Failed(MoveFailure.SplitAcrossServices)
        return movePart(fromId, toId, host, notes, amountMsat)
    }

    private suspend fun moveAll(fromId: String, toId: String, host: String, notes: List<Note>): MoveResult {
        val type = if (notes.size == 1) OperationType.Rotate else OperationType.Merge
        val inputs = notes.sumOf { it.amountMsat }
        val result = mutator.mutate(fromId, host, notes, type, listOf(OutputSpec.Own(toId, NotePurpose.Wallet))) { callback, k1s, outs ->
            if (notes.size == 1) Requests.rotate(callback, k1s.single(), outs.single().key) else Requests.merge(callback, k1s, outs.single().key)
        }
        when (result) {
            is MutationResult.Done -> {
                val out = result.outputs.single().amountMsat
                log.move(fromId, toId, host, out.takeIf { it > 0 } ?: inputs, ActivityLog.fee(inputs, result.outputs), ActivityStatus.Done, result.journalId)
            }
            is MutationResult.Pending -> log.move(fromId, toId, host, inputs, null, ActivityStatus.Pending, result.journalId)
            else -> Unit
        }
        return result.toMove()
    }

    private suspend fun movePart(fromId: String, toId: String, host: String, notes: List<Note>, amountMsat: Long): MoveResult {
        // Largest notes first, so as few notes as possible are touched.
        val sorted = notes.sortedByDescending { it.amountMsat }
        sorted.firstOrNull { it.amountMsat == amountMsat }?.let { return moveAll(fromId, toId, host, listOf(it)) }
        var inputs = sorted.takeWhileCovering(amountMsat)
        if (inputs.size > 1) {
            // Merge into one of our own notes first, then split that.
            val merged = mutator.mutate(fromId, host, inputs, OperationType.Merge, listOf(OutputSpec.Own(fromId, NotePurpose.Wallet))) { callback, k1s, outs ->
                Requests.merge(callback, k1s, outs.single().key)
            }
            inputs = (merged as? MutationResult.Done)?.outputs ?: return merged.toMove()
            if (inputs.single().amountMsat == amountMsat) return moveAll(fromId, toId, host, inputs)
        }
        val outputs = listOf(OutputSpec.Own(toId, NotePurpose.Wallet), OutputSpec.Own(fromId, NotePurpose.Change))
        val result = mutator.mutate(fromId, host, inputs, OperationType.Split, outputs) { callback, k1s, outs ->
            Requests.split(callback, k1s, amountMsat, outs[0].key, outs[1].key)
        }
        when (result) {
            is MutationResult.Done -> {
                log.move(fromId, toId, host, amountMsat, ActivityLog.fee(inputs.sumOf { it.amountMsat }, result.outputs), ActivityStatus.Done, result.journalId)
            }
            is MutationResult.Pending -> log.move(fromId, toId, host, amountMsat, null, ActivityStatus.Pending, result.journalId)
            else -> Unit
        }
        val r = result.toMove()
        return if (r is MoveResult.Moved) MoveResult.Moved(amountMsat) else r
    }

    private fun MutationResult.toMove(): MoveResult = when (this) {
        is MutationResult.Done -> MoveResult.Moved(outputs.firstOrNull()?.amountMsat ?: 0)
        is MutationResult.Refused ->
            MoveResult.Failed(if (kind == RejectionKind.InsufficientValue) MoveFailure.InsufficientFunds else MoveFailure.Refused, reason)
        is MutationResult.Unreachable -> MoveResult.Failed(MoveFailure.Refused, reason)
        is MutationResult.Pending -> MoveResult.Pending
    }

    private suspend fun outstanding(accountId: String): List<Note> =
        wallet.store.notes(accountId).first().filter { it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.index != null }


    private fun List<Note>.takeWhileCovering(amountMsat: Long): List<Note> {
        var sum = 0L
        val taken = mutableListOf<Note>()
        for (note in this) {
            if (sum >= amountMsat) break
            taken += note
            sum += note.amountMsat
        }
        return taken
    }
}
