package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurl.Bolt11
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.protocol.MintingPayRequest
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests

/** Why a note action can't be done (or wasn't). Every case maps to a full string in the app. */
enum class NoteProblem {
    /** A selected note isn't an outstanding note of this account (any more). */
    NotFound,

    /** The selected notes are on different mints; combine or move one mint's notes at a time. */
    DifferentMints,

    /** Combining needs at least two notes. */
    TooFewNotes,

    /** A split amount must leave change worth more than the split fee (see [NoteManager.splitLimits]). */
    InvalidAmount,

    /** The notes are already on that mint. */
    SameMint,

    /** The target isn't a usable LNURLcash mint. */
    MintInvalid,
    Unreachable,

    /** The target accepts less (or more) than the notes are worth. */
    BelowMinimum,
    AboveMaximum,

    /** The target's invoice doesn't match what was asked for. */
    InvoiceMismatch,

    /** A mint refused; nothing changed. */
    Refused,

    /** The payment to the target failed; the money is back. */
    PaymentFailed,

    /** Not moved: the quote wasn't accepted (an automatic move over the fee cap). Nothing was sent. */
    Declined,
}

sealed interface NoteActionResult {
    data class Done(val notes: List<Note>) : NoteActionResult

    /** Sent, outcome unknown; a later refresh settles it. */
    data object Pending : NoteActionResult

    data class Failed(val problem: NoteProblem) : NoteActionResult
}

/** The amounts a note can be split into: [minMsat]..[maxMsat] for the first part, the rest minus [feeMsat] as change. */
data class SplitLimits(val minMsat: Long, val maxMsat: Long, val feeMsat: Long)

sealed interface MintMoveQuote {
    data class Ready(
        val fromHost: String,
        val toHost: String,
        /** What leaves [fromHost]: the notes' value, merged into one. */
        val amountMsat: Long,
        /** What should arrive on [toHost], after its mint fee; the new note's certified value is authoritative. */
        val expectedMsat: Long,
    ) : MintMoveQuote {
        val feeMsat: Long get() = amountMsat - expectedMsat
    }

    data class Invalid(val problem: NoteProblem, val limitMsat: Long? = null) : MintMoveQuote
}

sealed interface MintMoveResult {
    data class Moved(val arrivedMsat: Long, val feeMsat: Long) : MintMoveResult

    /** Paid, but the new note hasn't shown up yet (or the payment is still in flight); a later refresh settles it. */
    data object Pending : MintMoveResult

    /** Nothing was paid. [limitMsat]: the target's minimum or maximum, for [NoteProblem.BelowMinimum]/[NoteProblem.AboveMaximum]. */
    data class Failed(val problem: NoteProblem, val limitMsat: Long? = null) : MintMoveResult
}

/**
 * What the notes page does with notes: combine (merge) and split them on their mint, and move them to another
 * mint. A move melts the notes (merged into one first) into an invoice from the target's own minting payRequest
 * whose comment names a fresh key of this account there (LUD-25 minting), so the target mints the note to us.
 * Both sides are journaled: the melt, and a [OperationType.Mint] entry for the note expected on the target, linked
 * by its detail to the melt and settled by [MintReconciler].
 */
internal class NoteManager(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
    private val guard = IndexGuard(wallet)
    private val log = ActivityLog(wallet)

    suspend fun combine(ids: Collection<String>): NoteActionResult {
        val accountId = wallet.activeId()
        val notes = when (val selected = select(accountId, ids)) {
            is Selected.Ok -> selected.notes
            is Selected.Problem -> return NoteActionResult.Failed(selected.problem)
        }
        if (notes.size < 2) return NoteActionResult.Failed(NoteProblem.TooFewNotes)
        val host = notes.first().host
        val result = mutator.mutate(accountId, host, notes, OperationType.Merge, listOf(OutputSpec.Own(accountId, NotePurpose.Wallet))) { callback, k1s, outs ->
            Requests.merge(callback, k1s, outs.single().key)
        }
        val inputs = notes.sumOf { it.amountMsat }
        when (result) {
            is MutationResult.Done -> result.outputs.single().let { out ->
                log.record("op:${result.journalId}", accountId, ActivityKind.Combined, out.amountMsat.takeIf { it > 0 } ?: inputs, host,
                    feeMsat = ActivityLog.fee(inputs, result.outputs), noteCount = notes.size, journalId = result.journalId)
            }
            is MutationResult.Pending -> log.record("op:${result.journalId}", accountId, ActivityKind.Combined, inputs, host, ActivityStatus.Pending,
                noteCount = notes.size, journalId = result.journalId)
            else -> Unit
        }
        return result.toAction()
    }

    suspend fun splitLimits(id: String): SplitLimits? {
        val note = select(wallet.activeId(), listOf(id)).let { (it as? Selected.Ok)?.notes?.single() } ?: return null
        val fee = baseFee(note.host)
        return SplitLimits(1, note.amountMsat - fee - 1, fee).takeIf { it.maxMsat >= it.minMsat }
    }

    suspend fun split(id: String, amountMsat: Long): NoteActionResult {
        val accountId = wallet.activeId()
        val note = when (val selected = select(accountId, listOf(id))) {
            is Selected.Ok -> selected.notes.single()
            is Selected.Problem -> return NoteActionResult.Failed(selected.problem)
        }
        val limits = splitLimits(id) ?: return NoteActionResult.Failed(NoteProblem.InvalidAmount)
        if (amountMsat !in limits.minMsat..limits.maxMsat) return NoteActionResult.Failed(NoteProblem.InvalidAmount)
        val outputs = listOf(OutputSpec.Own(accountId, NotePurpose.Wallet), OutputSpec.Own(accountId, NotePurpose.Change))
        val result = mutator.mutate(accountId, note.host, listOf(note), OperationType.Split, outputs) { callback, k1s, outs ->
            Requests.split(callback, k1s, amountMsat, outs[0].key, outs[1].key)
        }
        when (result) {
            is MutationResult.Done -> log.record("op:${result.journalId}", accountId, ActivityKind.Split, note.amountMsat, note.host,
                feeMsat = limits.feeMsat.takeIf { it > 0 }, noteCount = 2, journalId = result.journalId)
            is MutationResult.Pending -> log.record("op:${result.journalId}", accountId, ActivityKind.Split, note.amountMsat, note.host,
                ActivityStatus.Pending, noteCount = 2, journalId = result.journalId)
            else -> Unit
        }
        return result.toAction()
    }

    suspend fun quoteMove(ids: Collection<String>, toHost: String): MintMoveQuote = when (val q = prepareMove(wallet.activeId(), ids, toHost)) {
        is PreparedMove.Ready -> q.quote
        is PreparedMove.Problem -> MintMoveQuote.Invalid(q.problem, q.limitMsat)
    }

    suspend fun move(ids: Collection<String>, toHost: String): MintMoveResult = move(wallet.activeId(), ids, toHost, automatic = false) { _, _ -> true }

    /**
     * Moves [ids] of [accountId] to [toHost] if [accept] agrees with the quote (asked once, before anything is sent;
     * a refusal returns [MintMoveResult.Failed] with [NoteProblem.Declined]). [automatic] marks the History row as
     * done by the note policies.
     */
    suspend fun move(
        accountId: String,
        ids: Collection<String>,
        toHost: String,
        automatic: Boolean,
        accept: (quote: MintMoveQuote.Ready, notesMsat: Long) -> Boolean,
    ): MintMoveResult {
        val prepared = when (val q = prepareMove(accountId, ids, toHost)) {
            is PreparedMove.Ready -> q
            is PreparedMove.Problem -> return MintMoveResult.Failed(q.problem, q.limitMsat)
        }
        if (!accept(prepared.quote, prepared.notes.sumOf { it.amountMsat })) return MintMoveResult.Failed(NoteProblem.Declined)
        val quote = prepared.quote
        var notes = prepared.notes

        // One note pays one invoice: merge first. If anything later fails, the notes are just combined.
        if (notes.size > 1) {
            when (val merged = mutator.mutate(accountId, quote.fromHost, notes, OperationType.Merge, listOf(OutputSpec.Own(accountId, NotePurpose.Wallet))) { callback, k1s, outs ->
                Requests.merge(callback, k1s, outs.single().key)
            }) {
                is MutationResult.Done -> notes = merged.outputs
                is MutationResult.Pending -> return MintMoveResult.Pending
                is MutationResult.Refused -> return MintMoveResult.Failed(NoteProblem.Refused)
                is MutationResult.Unreachable -> return MintMoveResult.Failed(NoteProblem.Unreachable)
            }
        }
        val note = notes.single()
        val amount = note.amountMsat.takeIf { it > 0 } ?: quote.amountMsat

        // The target mints to a fresh key of ours: reserve it (persist before disclose), then ask for its invoice.
        var output: ReservedOutput? = null
        var invoice: String? = null
        for (attempt in 0 until IndexGuard.IN_USE_ATTEMPTS) {
            val reserved = guard.reserve(accountId, quote.toHost, NotePurpose.Wallet)
            when (val fetched = MintInvoice.fetch(wallet, prepared.pay, amount, reserved.key)) {
                is MintInvoice.Ok -> { output = reserved; invoice = fetched.invoice; break }
                is MintInvoice.InUse -> if (attempt == 0) guard.recover(accountId, quote.toHost, NotePurpose.Wallet)
                is MintInvoice.Unreachable -> return MintMoveResult.Failed(NoteProblem.Unreachable)
                is MintInvoice.Refused -> return MintMoveResult.Failed(NoteProblem.Refused)
                is MintInvoice.Mismatch -> return MintMoveResult.Failed(NoteProblem.InvoiceMismatch)
            }
        }
        if (output == null || invoice == null) return MintMoveResult.Failed(NoteProblem.Refused)

        val target = wallet.store.mint(quote.toHost) ?: return MintMoveResult.Failed(NoteProblem.MintInvalid)
        val mintId = UUID.randomUUID().toString()
        val expected = quote.expectedMsat
        val melted = Melter(wallet).melt(accountId, quote.fromHost, note, invoice) { meltId ->
            val now = wallet.now()
            wallet.store.putJournal(JournalEntry(mintId, accountId, OperationType.Mint, quote.toHost, target.withdrawUrl, emptyList(), emptyList(),
                listOf(output.ref), invoice, JournalStatus.Prepared, meltId, now, now))
            log.record("mint:$mintId", accountId, ActivityKind.MovedMint, expected, quote.fromHost, ActivityStatus.Pending,
                (amount - expected).takeIf { it > 0 }, quote.toHost, automatic = automatic, journalId = mintId)
        }
        val mints = MintReconciler(wallet)
        return when (melted) {
            is MeltOutcome.Paid -> awaitMint(mints, mintId, output.ref.q, amount, expected)
            is MeltOutcome.Pending -> MintMoveResult.Pending
            is MeltOutcome.PaymentFailed, is MeltOutcome.Refused -> {
                mints.entry(mintId)?.let { mints.reconcile(it) }
                MintMoveResult.Failed(if (melted is MeltOutcome.Refused) NoteProblem.Refused else NoteProblem.PaymentFailed)
            }
            MeltOutcome.Unreachable -> MintMoveResult.Failed(NoteProblem.Unreachable)
            MeltOutcome.NoteGone -> MintMoveResult.Failed(NoteProblem.Refused)
        }
    }

    /** The target mints once it sees the payment; give it a moment. */
    private suspend fun awaitMint(mints: MintReconciler, mintId: String, q: String, amount: Long, expected: Long): MintMoveResult {
        var wait = 250L
        val deadline = wallet.now() + wallet.payWaitMillis
        while (true) {
            val entry = mints.entry(mintId) ?: return MintMoveResult.Pending
            if (mints.reconcile(entry) == JournalStatus.Confirmed) {
                val arrived = wallet.store.note(q)?.amountMsat ?: expected
                return MintMoveResult.Moved(arrived, amount - arrived)
            }
            if (wallet.now() >= deadline) return MintMoveResult.Pending
            delay(wait)
            wait = (wait * 2).coerceAtMost(2_000)
        }
    }

    private sealed interface PreparedMove {
        data class Ready(val quote: MintMoveQuote.Ready, val notes: List<Note>, val pay: MintingPayRequest) : PreparedMove
        data class Problem(val problem: NoteProblem, val limitMsat: Long? = null) : PreparedMove
    }

    private suspend fun prepareMove(accountId: String, ids: Collection<String>, toHost: String): PreparedMove {
        val notes = when (val selected = select(accountId, ids)) {
            is Selected.Ok -> selected.notes
            is Selected.Problem -> return PreparedMove.Problem(selected.problem)
        }
        val fromHost = notes.first().host
        val host = Onboarding.normalizeHost(toHost) ?: return PreparedMove.Problem(NoteProblem.MintInvalid)
        if (host == fromHost) return PreparedMove.Problem(NoteProblem.SameMint)
        // Notes claimed from a mint before its record was kept: paying out of it needs the record.
        if (wallet.store.mint(fromHost) == null) {
            when (val source = Onboarding(wallet).connectMint(fromHost)) {
                is ConnectResult.Connected -> Unit
                is ConnectResult.Failed -> return PreparedMove.Problem(
                    if (source.result is OnboardResult.NetworkError) NoteProblem.Unreachable else NoteProblem.MintInvalid,
                )
            }
        }
        val pay = try {
            wallet.client.servicePayRequest(host)
        } catch (e: ProtocolViolation) {
            return PreparedMove.Problem(NoteProblem.MintInvalid)
        } catch (e: IOException) {
            return PreparedMove.Problem(NoteProblem.Unreachable)
        }
        // Pins the target's signing key and stores it as one of the account's mints.
        when (val connected = Onboarding(wallet).connectMint(host)) {
            is ConnectResult.Connected -> Unit
            is ConnectResult.Failed -> return PreparedMove.Problem(
                if (connected.result is OnboardResult.NetworkError) NoteProblem.Unreachable else NoteProblem.MintInvalid,
            )
        }
        val amount = PayPlanner.max(notes, baseFee(fromHost))
        if (amount < pay.pay.minSendable) return PreparedMove.Problem(NoteProblem.BelowMinimum, pay.pay.minSendable)
        if (amount > pay.pay.maxSendable) return PreparedMove.Problem(NoteProblem.AboveMaximum, pay.pay.maxSendable)
        val expected = pay.fee?.noteValue(amount) ?: amount
        if (expected <= 0) return PreparedMove.Problem(NoteProblem.BelowMinimum, pay.pay.minSendable)
        return PreparedMove.Ready(MintMoveQuote.Ready(fromHost, host, amount, expected), notes, pay)
    }

    private sealed interface Selected {
        data class Ok(val notes: List<Note>) : Selected
        data class Problem(val problem: NoteProblem) : Selected
    }

    /** The account's outstanding key-path notes with [ids], all on one mint. */
    private suspend fun select(accountId: String, ids: Collection<String>): Selected {
        if (ids.isEmpty()) return Selected.Problem(NoteProblem.NotFound)
        val byId = wallet.store.notes(accountId).first()
            .filter { it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.index != null }
            .associateBy { it.id }
        val notes = ids.distinct().map { byId[it] ?: return Selected.Problem(NoteProblem.NotFound) }
        if (notes.map { it.host }.distinct().size > 1) return Selected.Problem(NoteProblem.DifferentMints)
        return Selected.Ok(notes.sortedByDescending { it.amountMsat })
    }

    private suspend fun baseFee(host: String): Long = wallet.store.mint(host)?.feeBaseMsat ?: 0

    private fun MutationResult.toAction(): NoteActionResult = when (this) {
        is MutationResult.Done -> NoteActionResult.Done(outputs)
        is MutationResult.Pending -> NoteActionResult.Pending
        is MutationResult.Refused -> NoteActionResult.Failed(if (kind == RejectionKind.InsufficientValue) NoteProblem.InvalidAmount else NoteProblem.Refused)
        is MutationResult.Unreachable -> NoteActionResult.Failed(NoteProblem.Unreachable)
    }
}

/**
 * Settles the receiving side of a move between mints: the note expected at one of our keys on the target.
 * Found (by `p`) means it arrived; if the linked melt failed, nothing will arrive; otherwise keep waiting.
 */
internal class MintReconciler(private val wallet: Wallet) {
    suspend fun entry(id: String): JournalEntry? = wallet.store.journal(JournalStatus.entries.toSet()).firstOrNull { it.id == id }

    suspend fun reconcile(entry: JournalEntry): JournalStatus {
        if (entry.status == JournalStatus.Confirmed || entry.status == JournalStatus.Rejected) return entry.status
        val output = entry.outputs.single()
        val q = ByteVector32(output.q)
        try {
            val info = wallet.client.lookup(entry.withdrawUrl, q)
            val cert = info.certificate?.takeIf { Certificates.verify(it, q, info.mintPubkey) }
            if (wallet.store.note(output.q) == null) {
                wallet.store.upsertNotes(listOf(Note(output.q, output.accountId, entry.host, NoteKind.KeyPath, output.purpose, output.index,
                    info.maxWithdrawableMsat, cert?.encode(), NoteState.Outstanding, wallet.now())))
                // An invoice of this wallet that was paid (a move's mint has its own row, settled below).
                if (entry.detail == null) {
                    val paid = Bolt11.decode(entry.requestUrl)?.amountMsat
                    ActivityLog(wallet).record("invoice:${entry.id}", output.accountId, ActivityKind.Received, info.maxWithdrawableMsat, entry.host,
                        feeMsat = paid?.let { it - info.maxWithdrawableMsat }?.takeIf { it > 0 }, certified = cert != null, journalId = entry.id)
                }
            }
            return finish(entry, JournalStatus.Confirmed)
        } catch (e: NoteLookupError) {
            if (e.kind != RejectionKind.NoteUnknown) return JournalStatus.Unknown
        } catch (e: IOException) {
            return JournalStatus.Unknown
        } catch (e: ProtocolViolation) {
            return JournalStatus.Unknown
        }
        val melt = entry.detail?.let { entry(it) }
        if (melt?.status == JournalStatus.Rejected) return finish(entry, JournalStatus.Rejected)
        // An invoice nobody paid: once it has expired (with a margin for a payment in flight), nothing will come.
        if (entry.detail == null && Bolt11.decode(entry.requestUrl)?.let { wallet.now() > it.expiresAtSeconds * 1000 + EXPIRY_GRACE_MILLIS } == true) {
            return finish(entry, JournalStatus.Rejected)
        }
        if (entry.status == JournalStatus.Prepared) wallet.store.putJournal(entry.copy(status = JournalStatus.Unknown, updatedAtMillis = wallet.now()))
        return JournalStatus.Unknown
    }

    private suspend fun finish(entry: JournalEntry, status: JournalStatus): JournalStatus {
        wallet.store.putJournal(entry.copy(status = status, updatedAtMillis = wallet.now()))
        ActivityLog(wallet).settle(entry.id, status)
        return status
    }

    private companion object {
        const val EXPIRY_GRACE_MILLIS = 10 * 60_000L
    }
}
