package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import kotlinx.coroutines.flow.first
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain

/** Where a note handed out as a link stands. */
enum class SharedNoteState {
    /** Nobody has redeemed it yet; the link still holds the money. */
    Waiting,
    Redeemed,

    /** Taken back into the balance. */
    Reclaimed,
}

/** A note handed out as a link. [link] (the money itself) is only there while it is [SharedNoteState.Waiting]. */
data class SharedNote(
    val id: String,
    val host: String,
    val amountMsat: Long,
    val certified: Boolean,
    val state: SharedNoteState,
    val createdAtMillis: Long,
    val link: String?,
)

sealed interface CreateNoteResult {
    data class Created(val note: SharedNote, val feeMsat: Long) : CreateNoteResult

    /** The split was sent but its outcome is unknown; nothing was handed out. A later refresh settles the split. */
    data object Pending : CreateNoteResult

    data class Failed(val problem: PayProblem) : CreateNoteResult
}

sealed interface ShareNoteResult {
    data class Shared(val note: SharedNote) : ShareNoteResult

    /** The service says the note is already spent (now recorded); nothing was handed out. */
    data object AlreadySpent : ShareNoteResult

    /** The service couldn't confirm the note is still there; nothing was handed out. */
    data object Unreachable : ShareNoteResult

    /** Not an outstanding note of the active account with a derived key. */
    data object NotShareable : ShareNoteResult
}

sealed interface ReclaimResult {
    data class Reclaimed(val amountMsat: Long) : ReclaimResult

    /** Too late: someone redeemed it. */
    data object AlreadyRedeemed : ReclaimResult

    /** Sent, outcome unknown; a later refresh settles it. The note is out of the balance either way until then. */
    data object Pending : ReclaimResult

    data class Failed(val problem: PayProblem) : ReclaimResult
}

/**
 * Sends a note as a link (brief §5 rule 7): one note of exactly the amount is made from the account's notes (as for a
 * payment), marked [NoteState.Shared] and handed out as `lnurlw://…?k1=<ck1>&c=<cs1>`. The link is never stored:
 * it is re-derived from the seed whenever it is shown. The note stays shared until a lookup by `p` shows it burned
 * (redeemed), or the user takes it back by rotating it to a fresh key of their own.
 * History has one [ActivityKind.SentNote] row per note, `note:<Q>`: pending, then done (redeemed) or cancelled (taken back).
 */
internal class NoteSender(private val wallet: Wallet) {
    private val log = ActivityLog(wallet)
    private val mutator = NoteMutator(wallet)

    /** The most a single note can be made worth from one service's notes. */
    suspend fun availableMsat(): Long = candidates(wallet.activeId()).maxOfOrNull { (host, notes) -> PayPlanner.max(notes, baseFee(host)) } ?: 0

    suspend fun quote(amountMsat: Long): PayQuote {
        if (amountMsat <= 0) return PayQuote.Invalid(PayProblem.BelowMinimum, 1)
        val candidates = candidates(wallet.activeId())
        candidates.firstNotNullOfOrNull { (host, notes) -> PayPlanner.plan(notes, amountMsat, baseFee(host)) }
            ?.let { return PayQuote.Ok(amountMsat, it.feeMsat.coerceAtLeast(0)) }
        val (host, notes) = candidates.maxByOrNull { (h, n) -> PayPlanner.max(n, baseFee(h)) } ?: return PayQuote.Invalid(PayProblem.NoFunds)
        val all = PayPlanner.max(notes, baseFee(host))
        return if (amountMsat > all) PayQuote.Invalid(PayProblem.TooMuch, all) else PayQuote.Unreachable(PayPlanner.maxWithChange(notes, baseFee(host)), all)
    }

    suspend fun create(amountMsat: Long): CreateNoteResult {
        when (val q = quote(amountMsat)) {
            is PayQuote.Ok -> Unit
            is PayQuote.Unreachable -> return CreateNoteResult.Failed(PayProblem.NotExact)
            is PayQuote.Invalid -> return CreateNoteResult.Failed(q.problem)
        }
        val accountId = wallet.activeId()
        val (host, plan) = candidates(accountId).firstNotNullOfOrNull { (host, notes) -> PayPlanner.plan(notes, amountMsat, baseFee(host))?.let { host to it } }
            ?: return CreateNoteResult.Failed(PayProblem.TooMuch)
        val note = when (val made = Payer(wallet).exact(accountId, host, plan, amountMsat, OutputSpec.Own(accountId, NotePurpose.Wallet))) {
            is MutationResult.Done -> made.outputs.first()
            is MutationResult.Pending -> return CreateNoteResult.Pending
            is MutationResult.Refused -> return CreateNoteResult.Failed(PayProblem.Refused)
            is MutationResult.Unreachable -> return CreateNoteResult.Failed(PayProblem.Unreachable)
        }
        val shared = note.copy(amountMsat = note.amountMsat.takeIf { it > 0 } ?: amountMsat, state = NoteState.Shared, updatedAtMillis = wallet.now())
        wallet.store.upsertNotes(listOf(shared))
        log.record(activityId(shared.id), accountId, ActivityKind.SentNote, shared.amountMsat, host, ActivityStatus.Pending,
            plan.feeMsat.takeIf { it > 0 }, certified = shared.certificate != null)
        return CreateNoteResult.Created(checkNotNull(sharedNote(shared.id)), plan.feeMsat.coerceAtLeast(0))
    }

    /**
     * Hands out the whole note [id] as it is, without splitting: once the service confirms it is still there, it is
     * marked shared and gets a History row, like a note made with [create].
     */
    suspend fun share(id: String): ShareNoteResult {
        val accountId = wallet.activeId()
        val note = wallet.store.note(id)
            ?.takeIf { it.accountId == accountId && it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.index != null }
            ?: return ShareNoteResult.NotShareable
        when (check(note)) {
            Check.Waiting -> Unit
            Check.Redeemed -> return ShareNoteResult.AlreadySpent
            Check.Unknown -> return ShareNoteResult.Unreachable
        }
        val shared = note.copy(state = NoteState.Shared, updatedAtMillis = wallet.now())
        wallet.store.upsertNotes(listOf(shared))
        log.record(activityId(id), accountId, ActivityKind.SentNote, shared.amountMsat, shared.host, ActivityStatus.Pending,
            certified = shared.certificate != null)
        return ShareNoteResult.Shared(checkNotNull(sharedNote(id)))
    }

    /** The active account's sent note [id], with its link while nobody has redeemed it. */
    suspend fun sharedNote(id: String): SharedNote? {
        val accountId = wallet.activeId()
        val activity = activity(accountId, id) ?: return null
        val note = wallet.store.note(id)?.takeIf { it.accountId == accountId }
        val state = when {
            activity.status == ActivityStatus.Cancelled -> SharedNoteState.Reclaimed
            note?.state == NoteState.Shared -> SharedNoteState.Waiting
            else -> SharedNoteState.Redeemed
        }
        return SharedNote(id, activity.host, activity.amountMsat, activity.certified == true, state, activity.createdAtMillis,
            link = note?.takeIf { state == SharedNoteState.Waiting }?.let { link(it) })
    }

    /** Takes a sent note back while nobody has redeemed it: rotated to a fresh wallet key, back in the balance. */
    suspend fun reclaim(id: String): ReclaimResult {
        val accountId = wallet.activeId()
        val note = wallet.store.note(id)?.takeIf { it.accountId == accountId && it.state == NoteState.Shared && it.index != null }
            ?: return if (sharedNote(id)?.state == SharedNoteState.Redeemed) ReclaimResult.AlreadyRedeemed else ReclaimResult.Failed(PayProblem.Refused)
        when (check(note)) {
            Check.Redeemed -> return ReclaimResult.AlreadyRedeemed
            Check.Unknown -> return ReclaimResult.Failed(PayProblem.Unreachable)
            Check.Waiting -> Unit
        }
        val result = mutator.mutate(accountId, note.host, listOf(note), OperationType.Rotate, listOf(OutputSpec.Own(accountId, NotePurpose.Wallet))) { callback, k1s, outs ->
            Requests.rotate(callback, k1s.single(), outs.single().key)
        }
        return when (result) {
            is MutationResult.Done -> {
                cancel(accountId, id)
                ReclaimResult.Reclaimed(result.outputs.single().amountMsat.takeIf { it > 0 } ?: note.amountMsat)
            }
            // Either our rotate landed or the note is ours again once reconciled; the link can no longer be counted on.
            is MutationResult.Pending -> {
                cancel(accountId, id)
                ReclaimResult.Pending
            }
            // Redeemed in the meantime?
            is MutationResult.Refused -> if (check(note) == Check.Redeemed) ReclaimResult.AlreadyRedeemed else ReclaimResult.Failed(PayProblem.Refused)
            is MutationResult.Unreachable -> if (check(note) == Check.Redeemed) ReclaimResult.AlreadyRedeemed else ReclaimResult.Failed(PayProblem.Unreachable)
        }
    }

    /** On refresh: an account's shared notes that someone redeemed are marked spent, and their History rows done. */
    suspend fun reconcile(accountId: String) {
        wallet.store.notes(accountId).first().filter { it.state == NoteState.Shared }.forEach { check(it) }
    }

    private enum class Check { Waiting, Redeemed, Unknown }

    /** Looks [note] up by `p`; burned means redeemed (recorded as such). */
    private suspend fun check(note: Note): Check {
        val service = wallet.store.service(note.host) ?: return Check.Unknown
        try {
            wallet.client.lookup(service.withdrawUrl, ByteVector32(note.id))
            return Check.Waiting
        } catch (e: NoteLookupError) {
            if (e.kind != RejectionKind.Spent) return if (e.kind == RejectionKind.Pending) Check.Waiting else Check.Unknown
        } catch (e: IOException) {
            return Check.Unknown
        } catch (e: ProtocolViolation) {
            return Check.Unknown
        }
        wallet.store.upsertNotes(listOf(note.copy(state = NoteState.Spent, updatedAtMillis = wallet.now())))
        activity(note.accountId, note.id)?.takeIf { it.status == ActivityStatus.Pending }?.let { wallet.store.putActivity(it.copy(status = ActivityStatus.Done)) }
        return Check.Redeemed
    }

    private suspend fun cancel(accountId: String, id: String) {
        activity(accountId, id)?.let { wallet.store.putActivity(it.copy(status = ActivityStatus.Cancelled)) }
    }

    private suspend fun link(note: Note): String? {
        val service = wallet.store.service(note.host) ?: return null
        val spend = wallet.branch(note.accountId, note.host).spend(NotePurpose.entries.first { it.value == note.purpose }, note.index ?: return null, spendDomain(note.host))
        return NoteLink(service.withdrawUrl, spend, note.amountMsat, note.certificate).encode()
    }

    private suspend fun activity(accountId: String, noteId: String): Activity? =
        wallet.store.activities(accountId).first().firstOrNull { it.id == activityId(noteId) && it.kind == ActivityKind.SentNote }

    /** The services to make the note on: the home mint first, then the others. */
    private suspend fun candidates(accountId: String): List<Pair<String, List<Note>>> {
        val primary = wallet.store.account(accountId)?.host
        return wallet.store.notes(accountId).first()
            .filter { it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.index != null }
            .groupBy { it.host }.toList().sortedBy { it.first != primary }
    }

    private suspend fun baseFee(host: String): Long = wallet.store.service(host)?.feeBaseMsat ?: 0

    companion object {
        fun activityId(noteId: String) = "$SENT_NOTE_PREFIX$noteId"
    }
}

private const val SENT_NOTE_PREFIX = "note:"

/** The note behind a [ActivityKind.SentNote] History row (for [Wallet.sharedNote]), or null for any other row. */
val Activity.sentNoteId: String? get() = id.takeIf { kind == ActivityKind.SentNote }?.removePrefix(SENT_NOTE_PREFIX)
