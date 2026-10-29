package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.brand.MintNotes
import whitewallet.brand.NoteStep
import whitewallet.brand.NoteStrategy
import whitewallet.brand.StrategyNote
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.protocol.Requests

/** What one application of an account's note policies did. */
data class PolicyRun(
    val rotated: Int,
    val merged: Int,
    val failed: Int,
    val stoppedEarly: String?,
    val atMillis: Long,
    val split: Int = 0,
    /** Notes moved from other mints to the home mint ("collect at home mint"), one entry per mint. */
    val collected: List<Collected> = emptyList(),
    /** Mints whose notes collecting left where they are, and why. */
    val notCollected: List<NotCollected> = emptyList(),
) {
    val didSomething: Boolean get() = rotated > 0 || merged > 0 || split > 0 || failed > 0 || stoppedEarly != null || collected.isNotEmpty() || notCollected.isNotEmpty()
}

/** A move to the home mint: [arrivedMsat] (expected, if still [pending]) for [feeMsat]. */
data class Collected(val host: String, val arrivedMsat: Long, val feeMsat: Long, val pending: Boolean = false)

/** Notes worth [amountMsat] on [host] that weren't moved to the home mint. */
data class NotCollected(val host: String, val amountMsat: Long, val reason: Reason) {
    sealed interface Reason {
        /** Moving would cost [feeMsat], more than the account's cap of [capPercent] %. */
        data class FeeTooHigh(val feeMsat: Long, val capPercent: Int) : Reason

        /** Worth less than the home mint takes ([minMsat]) or than its fees. */
        data class TooSmall(val minMsat: Long?) : Reason

        /** The mint didn't answer, refused, or the payment failed ([problem]); tried again later. */
        data class Failed(val problem: NoteProblem) : Reason
    }
}

/** Collecting at the home mint as the brand configures it (brand `notes.collect.atHomeMint`, `notes.collect.feeCap`). */
data class CollectDefaults(
    /** Users see the option at all. */
    val offered: Boolean = true,
    /** It starts on for a new account. */
    val enabled: Boolean = false,
    /** The default fee cap, in percent of the moved amount. */
    val feeCapPercent: Int = 2,
    /** Every account collects, and users have no switch for it ([offered] is false). */
    val always: Boolean = false,
) {
    init {
        require(feeCapPercent in 0..100) { "notes.collect.feeCap must be 0 to 100 (percent), not $feeCapPercent" }
        require(!(always && offered)) { "collecting always leaves nothing to offer" }
    }

    /** Whether an account collects, given its own choice (null if it never made one). */
    fun active(choice: Boolean?): Boolean = always || offered && (choice ?: enabled)

    companion object {
        /** The fee caps a user can pick from, in percent. */
        val FEE_CAPS = listOf(1, 2, 5, 10)

        /** `notes.collect.atHomeMint` = `hidden`, `off`, `on` or `always`. */
        fun fromConfig(value: String, feeCapPercent: Int): CollectDefaults = when (value.trim().lowercase()) {
            "hidden" -> CollectDefaults(offered = false, enabled = false, feeCapPercent = feeCapPercent)
            "off" -> CollectDefaults(offered = true, enabled = false, feeCapPercent = feeCapPercent)
            "on" -> CollectDefaults(offered = true, enabled = true, feeCapPercent = feeCapPercent)
            "always" -> CollectDefaults(offered = false, enabled = true, feeCapPercent = feeCapPercent, always = true)
            else -> error("notes.collect.atHomeMint must be hidden, off, on or always, not '$value'")
        }
    }
}

/** How [NotePolicyEngine] collects at the home mint for one run; null when it doesn't. */
internal data class CollectPolicy(val homeHost: String, val feeCapPercent: Int)

/**
 * Brings an account to the state its note policies describe, per mint:
 * - the note strategy (see [NoteStrategy]) proposes steps, which run here with the usual safeguards, round after round
 *   until it proposes none. Steps that don't apply are skipped; notes the mint refused are left out of later
 *   rounds; a mint that doesn't answer (or an outcome still pending) ends the run;
 * - auto-rotate: no outstanding note on an address key (purpose 2); each is rotated onto the next wallet key. Part of
 *   strategies that rotate themselves.
 * Before that, with "collect at home mint", every other mint's notes are moved to the home mint (see [collect]), so
 * the strategy then shapes them there.
 * Idempotent: anything that failed is simply retried the next time it runs.
 */
internal class NotePolicyEngine(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
    private val rotator = NoteRotator(wallet)
    private val log = ActivityLog(wallet)

    suspend fun apply(accountId: String, strategy: NoteStrategy, autoRotate: Boolean, collect: CollectPolicy? = null): PolicyRun {
        var run = PolicyRun(0, 0, 0, null, wallet.now())
        if (collect != null) run = collect(accountId, collect, run)
        for (host in outstanding(accountId).map { it.host }.distinct()) {
            run = follow(accountId, host, strategy, run)
            if (run.stoppedEarly == null && autoRotate && !strategy.rotatesAddressNotes) run = rotateAddressNotes(accountId, host, run)
            if (run.stoppedEarly != null) break
        }
        return run
    }

    private suspend fun follow(accountId: String, host: String, strategy: NoteStrategy, start: PolicyRun): PolicyRun {
        var run = start
        val skipped = mutableSetOf<String>()
        val fee = wallet.store.mint(host)?.feeBaseMsat ?: 0
        repeat(MAX_ROUNDS) {
            val notes = outstanding(accountId).filter { it.host == host && it.id !in skipped }.associateBy { it.id }
            val steps = strategy.plan(MintNotes(host, notes.values.map { it.forStrategy() }, fee))
            // One step per round: the first that applies. Later steps may name notes it spends, so the strategy plans
            // again with the notes as they are then. Refused notes are left out, so every round changes something.
            val outcome = steps.firstNotNullOfOrNull { step ->
                when (step) {
                    is NoteStep.Combine -> step.noteIds.distinct().mapNotNull { notes[it] }.takeIf { it.size >= 2 }?.let { combine(accountId, host, it.take(MAX_MERGE)) }
                    is NoteStep.Split -> notes[step.noteId]?.takeIf { step.firstMsat in 1..it.amountMsat - fee - 1 }?.let { split(accountId, it, step.firstMsat, fee) }
                    is NoteStep.Rotate -> notes[step.noteId]?.takeIf { it.purpose == NotePurpose.LightningAddress.value }?.let { rotate(accountId, it) }
                }
            } ?: return run
            run = outcome.addTo(run)
            when (outcome) {
                is Outcome.Done -> Unit
                is Outcome.Refused -> skipped += outcome.noteIds
                is Outcome.Stop -> return run
            }
        }
        return run
    }

    private sealed interface Outcome {
        fun addTo(run: PolicyRun): PolicyRun

        data class Done(val merged: Int = 0, val split: Int = 0, val rotated: Int = 0) : Outcome {
            override fun addTo(run: PolicyRun) = run.copy(merged = run.merged + merged, split = run.split + split, rotated = run.rotated + rotated)
        }

        data class Refused(val noteIds: List<String>) : Outcome {
            override fun addTo(run: PolicyRun) = run.copy(failed = run.failed + 1)
        }

        data class Stop(val reason: String, val failed: Boolean) : Outcome {
            override fun addTo(run: PolicyRun) = run.copy(failed = run.failed + if (failed) 1 else 0, stoppedEarly = reason)
        }
    }

    private suspend fun combine(accountId: String, host: String, batch: List<Note>): Outcome {
        val inputs = batch.sumOf { it.amountMsat }
        val target = OutputSpec.Own(accountId, NotePurpose.Wallet)
        return when (val r = mutator.mutate(accountId, host, batch, OperationType.Merge, listOf(target)) { callback, k1s, outs -> Requests.merge(callback, k1s, outs.single().key) }) {
            is MutationResult.Done -> {
                val out = r.outputs.single()
                log.record("op:${r.journalId}", accountId, ActivityKind.Combined, out.amountMsat.takeIf { it > 0 } ?: inputs, host,
                    feeMsat = ActivityLog.fee(inputs, r.outputs), noteCount = batch.size, automatic = true, journalId = r.journalId)
                Outcome.Done(merged = batch.size)
            }
            is MutationResult.Refused -> Outcome.Refused(batch.map { it.id })
            is MutationResult.Unreachable -> Outcome.Stop(r.reason ?: "no answer", failed = true)
            is MutationResult.Pending -> {
                log.record("op:${r.journalId}", accountId, ActivityKind.Combined, inputs, host, ActivityStatus.Pending,
                    noteCount = batch.size, automatic = true, journalId = r.journalId)
                Outcome.Stop("waiting for confirmation", failed = false)
            }
        }
    }

    private suspend fun split(accountId: String, note: Note, firstMsat: Long, fee: Long): Outcome {
        val outputs = listOf(OutputSpec.Own(accountId, NotePurpose.Wallet), OutputSpec.Own(accountId, NotePurpose.Change))
        return when (val r = mutator.mutate(accountId, note.host, listOf(note), OperationType.Split, outputs) { callback, k1s, outs ->
            Requests.split(callback, k1s, firstMsat, outs[0].key, outs[1].key)
        }) {
            is MutationResult.Done -> {
                log.record("op:${r.journalId}", accountId, ActivityKind.Split, note.amountMsat, note.host,
                    feeMsat = fee.takeIf { it > 0 }, noteCount = 2, automatic = true, journalId = r.journalId)
                Outcome.Done(split = 1)
            }
            is MutationResult.Refused -> Outcome.Refused(listOf(note.id))
            is MutationResult.Unreachable -> Outcome.Stop(r.reason ?: "no answer", failed = true)
            is MutationResult.Pending -> {
                log.record("op:${r.journalId}", accountId, ActivityKind.Split, note.amountMsat, note.host, ActivityStatus.Pending,
                    noteCount = 2, automatic = true, journalId = r.journalId)
                Outcome.Stop("waiting for confirmation", failed = false)
            }
        }
    }

    private suspend fun rotate(accountId: String, note: Note): Outcome =
        when (val r = rotator.rotate(accountId, note.host, note.purpose!!, note.index!!, automatic = true)) {
            is RotateResult.Rotated -> Outcome.Done(rotated = 1)
            is RotateResult.Failed -> if (r.noAnswer) Outcome.Stop(r.reason ?: "no answer", failed = true) else Outcome.Refused(listOf(note.id))
            RotateResult.Pending -> Outcome.Stop("waiting for confirmation", failed = false)
            is RotateResult.NotRotatable -> Outcome.Refused(listOf(note.id))
        }

    private suspend fun rotateAddressNotes(accountId: String, host: String, run: PolicyRun): PolicyRun {
        val r = rotator.rotateAll(accountId, host, NotePurpose.LightningAddress.value, automatic = true)
        return run.copy(rotated = run.rotated + r.rotated, failed = run.failed + r.failed, stoppedEarly = r.stoppedEarly)
    }

    /**
     * Moves each other mint's outstanding notes to the home mint when the move costs at most the fee cap (what leaves
     * that mint minus what arrives at home, against what leaves). Skipped, without sending anything: a mint with a
     * payment of the account still unsettled (a melt there, or any move's mint at home), and one whose last move
     * failed less than [COLLECT_COOLDOWN_MILLIS] ago (never a retried payment in a loop).
     */
    private suspend fun collect(accountId: String, policy: CollectPolicy, start: PolicyRun): PolicyRun {
        var run = start
        val open = wallet.store.journal(setOf(JournalStatus.Prepared, JournalStatus.Unknown)).filter { it.accountId == accountId }
        // A move still waiting for its note at home: let it settle before sending more.
        if (open.any { it.type == OperationType.Mint && it.detail != null }) return run
        val byHost = outstanding(accountId).filter { it.host != policy.homeHost }.groupBy { it.host }
        for ((host, notes) in byHost) {
            if (open.any { it.type == OperationType.Melt && it.host == host }) continue
            if (wallet.collectBlockedUntil(accountId, host) > wallet.now()) continue
            var refused: NotCollected.Reason? = null
            val result = NoteManager(wallet).move(accountId, notes.map { it.id }, policy.homeHost, automatic = true) { quote, notesMsat ->
                val fee = notesMsat - quote.expectedMsat
                (fee * 100 <= policy.feeCapPercent.toLong() * notesMsat).also { ok -> if (!ok) refused = NotCollected.Reason.FeeTooHigh(fee, policy.feeCapPercent) }
            }
            val total = notes.sumOf { it.amountMsat }
            run = when (result) {
                is MintMoveResult.Moved -> run.copy(collected = run.collected + Collected(host, result.arrivedMsat, result.feeMsat))
                MintMoveResult.Pending -> run.copy(collected = run.collected + Collected(host, 0, 0, pending = true))
                is MintMoveResult.Failed -> {
                    val reason = refused ?: when (result.problem) {
                        NoteProblem.BelowMinimum -> NotCollected.Reason.TooSmall(result.limitMsat)
                        else -> NotCollected.Reason.Failed(result.problem).also {
                            // A payment attempt that failed (or a refusal) waits; an unreachable mint is just asked again.
                            if (result.problem != NoteProblem.Unreachable) wallet.blockCollect(accountId, host, wallet.now() + COLLECT_COOLDOWN_MILLIS)
                        }
                    }
                    run.copy(notCollected = run.notCollected + NotCollected(host, total, reason))
                }
            }
            if (result == MintMoveResult.Pending) break
        }
        return run
    }

    private suspend fun outstanding(accountId: String): List<Note> = wallet.store.notes(accountId).first()
        .filter { it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.purpose != null && it.index != null }

    private fun Note.forStrategy() = StrategyNote(id, amountMsat, purpose == NotePurpose.LightningAddress.value, updatedAtMillis)

    private companion object {
        /** Spends per merge request; LUD-25 notes ~8-12 `ck1`s fit common URL limits. */
        const val MAX_MERGE = 10

        /** Steps per mint and run: a strategy that never settles still ends. The next refresh continues. */
        const val MAX_ROUNDS = 50

        /** How long collecting leaves a mint alone after a move from it failed. */
        const val COLLECT_COOLDOWN_MILLIS = 60 * 60_000L
    }
}
