package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import whitewallet.core.model.Activity
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.HandedOut
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.isShared
import whitewallet.lnurlcash.Redaction
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.spendDomain

/** Where a note handed out as a link stands. */
enum class SharedNoteState {
    /** Nobody has claimed it yet; the link still holds the money. */
    Waiting,

    /** Spent at its mint: by whoever holds the link, or by another device with the same recovery phrase. */
    Spent,

    /** Taken back into the balance. */
    Reclaimed,

    /** Removed for good while its mint didn't know it: whether it was claimed isn't known ([ActivityStatus.Unverified]). */
    Unverified,
}

/**
 * A note handed out as a link. [link] (the money itself) is only there while it is [SharedNoteState.Waiting], and not once the user asked
 * to take it back. [confirmed]: its mint said it is there; false for a note handed out offline until the wallet could ask, and while its
 * mint doesn't know it. [unknownToMint]: its mint said it doesn't know the note: it is set aside with the wallet's other unknown notes
 * ([NoteState.Unknown]) and waits until the mint knows it again. [takeBackAsked]: the user asked to take it back while its mint didn't know
 * it ([ReclaimResult.WhenKnown]); it is taken back as soon as the mint knows it, unless it was claimed first. [checkedAtMillis]: when its
 * mint last answered for it while it waits; null before the first answer.
 */
data class SharedNote(
    val id: String,
    val host: String,
    val amountMsat: Long,
    val certified: Boolean,
    val state: SharedNoteState,
    val createdAtMillis: Long,
    @property:RevealsNoteSecret val link: String?,
    val confirmed: Boolean = true,
    val unknownToMint: Boolean = false,
    val checkedAtMillis: Long? = null,
    val takeBackAsked: Boolean = false,
) {
    /** Debug text without the link, which is the money. */
    @OptIn(RevealsNoteSecret::class)
    override fun toString(): String =
        "SharedNote(id=$id, host=$host, amountMsat=$amountMsat, certified=$certified, state=$state, createdAtMillis=$createdAtMillis, " +
            "link=${link?.let(Redaction::url)}, confirmed=$confirmed, unknownToMint=$unknownToMint, checkedAtMillis=$checkedAtMillis, " +
            "takeBackAsked=$takeBackAsked)"
}

sealed interface CreateNoteResult {
    data class Created(val note: SharedNote, val feeMsat: Long) : CreateNoteResult

    /** The split was sent but its outcome is unknown; nothing was handed out. A later refresh settles the split. */
    data object Pending : CreateNoteResult

    data class Failed(val problem: PayProblem) : CreateNoteResult
}

sealed interface ShareNoteResult {
    data class Shared(val note: SharedNote) : ShareNoteResult

    /** The mint says the note is already spent (now recorded); nothing was handed out. */
    data object AlreadySpent : ShareNoteResult

    /** The mint's answer couldn't be read; nothing was handed out. (Offline, the note is handed out unconfirmed.) */
    data object Unreachable : ShareNoteResult

    /** The mint says it doesn't know the note; nothing was handed out, and the note is set aside (see [UnknownNotes]). */
    data object UnknownToMint : ShareNoteResult

    /** Not an outstanding note of the active account with a derived key. */
    data object NotShareable : ShareNoteResult
}

sealed interface ReclaimResult {
    data class Reclaimed(val amountMsat: Long) : ReclaimResult

    /** Too late: it was spent already. */
    data object AlreadySpent : ReclaimResult

    /** Sent, outcome unknown; a later refresh settles it. The note is out of the balance either way until then. */
    data object Pending : ReclaimResult

    /**
     * Its mint doesn't know the note, so it can't be taken back now: it is taken back as soon as the mint knows it again (see
     * [HandedOut.TakeBack]). Until then it stays out of the balance, and its link is no longer shown.
     */
    data object WhenKnown : ReclaimResult

    /**
     * Not taken back: [PayProblem.Refused] when the mint answered but wouldn't (with [mintMessage], its own words for a refusal the wallet
     * doesn't recognize: its text, in its language, never the app's), [PayProblem.KeyChanged] when its signing key doesn't match the pinned
     * one (nothing was asked or sent), [PayProblem.Unreachable] only when the mint couldn't be reached.
     */
    data class Failed(val problem: PayProblem, val mintMessage: String? = null) : ReclaimResult
}

/**
 * Sends a note as a link (brief §5 rule 7): one note of exactly the amount is made from the account's notes (as for a payment), marked
 * [NoteState.Shared] and handed out as `lnurlw://…?k1=<ck1>&c=<cs1>`. The link is never stored: it is re-derived from the seed whenever it
 * is shown. The note stays shared until a lookup by `p` shows it burned (spent), or the user takes it back by rotating it to a fresh key of
 * their own. History has one [ActivityKind.SentNote] row per note, `note:<Q>`: pending, then done (spent) or cancelled (taken back).
 *
 * A shared note its mint says it doesn't know is set aside like any other unknown note ([UnknownNotes]), remembering it was handed out
 * ([Note.handedOut]): once the mint knows it again it waits to be claimed again, never back in the balance, or is taken back at once if the
 * user asked to meanwhile.
 */
internal class NoteSender(private val wallet: Wallet) {
    private val log = ActivityLog(wallet)
    private val mutator = NoteMutator(wallet)
    private val payer = Payer(wallet)
    private val unknownNotes = UnknownNotes(wallet)
    private val prober = KeyProber(wallet)

    /** The most a single note can be made worth from one mint's notes; with [from], that mint's. */
    suspend fun availableMsat(from: String? = null): Long {
        val accountId = wallet.activeId()
        return candidates(accountId, from).maxOfOrNull { (host, notes) -> PayPlanner.max(notes, baseFee(accountId, host)) } ?: 0
    }

    /** What making a note of [amountMsat] costs; with [from], made from that mint's notes only. */
    suspend fun quote(amountMsat: Long, from: String? = null): PayQuote {
        if (amountMsat <= 0) return PayQuote.Invalid(PayProblem.BelowMinimum, 1)
        val accountId = wallet.activeId()
        val candidates = candidates(accountId, from)
        candidates
            .firstNotNullOfOrNull { (host, notes) -> PayPlanner.plan(notes, amountMsat, baseFee(accountId, host))?.let { host to it } }
            ?.let { (host, plan) ->
                return PayQuote.Ok(amountMsat, plan.feeMsat.coerceAtLeast(0), host)
            }
        val (host, notes) =
            candidates.maxByOrNull { (h, n) -> PayPlanner.max(n, baseFee(accountId, h)) }
                ?: return PayQuote.Invalid(
                    if (candidates(accountId, from, keyChanged = true).isEmpty()) PayProblem.NoFunds else PayProblem.KeyChanged
                )
        val all = PayPlanner.max(notes, baseFee(accountId, host))
        return if (amountMsat > all) PayQuote.Invalid(PayProblem.TooMuch, all)
        else PayQuote.Unreachable(PayPlanner.maxWithChange(notes, baseFee(accountId, host)), all)
    }

    suspend fun create(amountMsat: Long, from: String? = null): CreateNoteResult {
        when (val q = quote(amountMsat, from)) {
            is PayQuote.Ok -> Unit
            is PayQuote.Unreachable -> return CreateNoteResult.Failed(PayProblem.NotExact)
            is PayQuote.Invalid -> return CreateNoteResult.Failed(q.problem)
        }
        val accountId = wallet.activeId()
        val (host, plan) =
            candidates(accountId, from).firstNotNullOfOrNull { (host, notes) ->
                PayPlanner.plan(notes, amountMsat, baseFee(accountId, host))?.let { host to it }
            } ?: return CreateNoteResult.Failed(PayProblem.TooMuch)
        val note =
            when (val made = payer.exact(accountId, host, plan, amountMsat)) {
                is MutationResult.Done -> made.outputs.first()
                is MutationResult.Pending -> return CreateNoteResult.Pending
                is MutationResult.Refused -> return CreateNoteResult.Failed(PayProblem.Refused)
                is MutationResult.Unreachable ->
                    return CreateNoteResult.Failed(if (made.keyChanged) PayProblem.KeyChanged else PayProblem.Unreachable)
            }
        val shared =
            note.copy(
                amountMsat = note.amountMsat.takeIf { it > 0 } ?: amountMsat,
                state = NoteState.Shared,
                updatedAtMillis = wallet.now(),
            )
        wallet.store.upsertNotes(listOf(shared))
        log.record(
            id = activityId(shared.id),
            accountId,
            kind = ActivityKind.SentNote,
            shared.amountMsat,
            host,
            status = ActivityStatus.Pending,
            feeMsat = plan.feeMsat.takeIf { it > 0 },
            certified = shared.certificate != null,
        )
        return CreateNoteResult.Created(checkNotNull(sharedNote(shared.id)), plan.feeMsat.coerceAtLeast(0))
    }

    /**
     * Hands out the whole note [id] as it is, without splitting: marked shared, with a History row, like a note made with [create]. Its
     * link derives from the seed, so this works offline too: then it is handed out unconfirmed ([NoteState.SharedUnconfirmed]), and the
     * first lookup once online confirms it, or finds it spent.
     */
    suspend fun share(id: String): ShareNoteResult {
        val accountId = wallet.activeId()
        val note =
            wallet.store.note(id)?.takeIf {
                it.accountId == accountId && it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.index != null
            } ?: return ShareNoteResult.NotShareable
        val state =
            when (check(note)) {
                Check.Waiting,
                Check.InUse -> NoteState.Shared
                Check.Offline -> NoteState.SharedUnconfirmed
                Check.Spent -> return ShareNoteResult.AlreadySpent
                Check.UnknownToMint -> {
                    unknownNotes.sortOut(listOf(note))
                    return ShareNoteResult.UnknownToMint
                }
                Check.Unreadable,
                is Check.Refused,
                Check.KeyChanged,
                Check.NoMint -> return ShareNoteResult.Unreachable
            }
        val shared =
            note.copy(state = state, updatedAtMillis = wallet.now(), checkedAtMillis = wallet.now().takeIf { state == NoteState.Shared })
        wallet.store.upsertNotes(listOf(shared))
        log.record(
            id = activityId(id),
            accountId,
            kind = ActivityKind.SentNote,
            shared.amountMsat,
            shared.host,
            status = ActivityStatus.Pending,
            certified = shared.certificate != null,
        )
        return ShareNoteResult.Shared(checkNotNull(sharedNote(id)))
    }

    /** The active account's sent note [id], with its link while nobody has claimed it and the user didn't ask to take it back. */
    suspend fun sharedNote(id: String): SharedNote? {
        val accountId = wallet.activeId()
        val activity = activity(accountId, id) ?: return null
        val note = wallet.store.note(id)?.takeIf { it.accountId == accountId }
        val state =
            when {
                activity.status == ActivityStatus.Cancelled -> SharedNoteState.Reclaimed
                activity.status == ActivityStatus.Unverified -> SharedNoteState.Unverified
                note?.isHandedOut() == true -> SharedNoteState.Waiting
                else -> SharedNoteState.Spent
            }
        val waiting = note?.takeIf { state == SharedNoteState.Waiting }
        val takeBackAsked = waiting?.handedOut == HandedOut.TakeBack
        return SharedNote(
            id,
            activity.host,
            activity.amountMsat,
            certified = activity.certified == true,
            state,
            activity.createdAtMillis,
            link = waiting?.takeIf { !takeBackAsked }?.let { link(it) },
            confirmed = waiting == null || waiting.state == NoteState.Shared,
            unknownToMint = waiting?.state == NoteState.Unknown,
            checkedAtMillis = waiting?.checkedAtMillis,
            takeBackAsked = takeBackAsked,
        )
    }

    /**
     * Takes a sent note back while nobody has claimed it: rotated to a fresh wallet key, back in the balance. One its mint doesn't know
     * can't be: the wish is kept, and it is taken back as soon as the mint knows it again ([ReclaimResult.WhenKnown]). A mint that answered
     * but wouldn't is never told as one that couldn't be reached (see [ReclaimResult.Failed]).
     */
    suspend fun reclaim(id: String): ReclaimResult {
        val accountId = wallet.activeId()
        val note =
            wallet.store.note(id)?.takeIf { it.accountId == accountId && it.isHandedOut() && it.index != null }
                ?: return if (sharedNote(id)?.state == SharedNoteState.Spent) ReclaimResult.AlreadySpent
                else ReclaimResult.Failed(PayProblem.Refused)
        if (note.state == NoteState.Unknown) {
            wallet.store.upsertNotes(listOf(note.copy(handedOut = HandedOut.TakeBack)))
            // The mint may know it again by now: then it is taken back at once.
            return when (askAgain(note)) {
                is KeyProbe.Outstanding -> takeBackIfAsked(id) ?: ReclaimResult.WhenKnown
                is KeyProbe.Spent -> ReclaimResult.AlreadySpent
                else -> ReclaimResult.WhenKnown
            }
        }
        return when (val check = check(note)) {
            Check.Waiting -> takeBack(note)
            else -> settled(note, check) ?: failure(check) ?: ReclaimResult.Failed(PayProblem.Refused)
        }
    }

    /**
     * Takes back note [id] now if the user asked to while its mint didn't know it ([HandedOut.TakeBack]) and the mint knows it again; null
     * if there is no such wish. The note may be any account's of the active wallet ([reconcile] runs for each on refresh): it is rotated to
     * a fresh key of its own account, with that account's keys, and only that account's records change.
     */
    suspend fun takeBackIfAsked(id: String): ReclaimResult? {
        val note =
            wallet.store.note(id)?.takeIf { it.state.isShared && it.handedOut == HandedOut.TakeBack && it.index != null } ?: return null
        return takeBack(note)
    }

    /**
     * Asks again, now, about [id] if it is a note of the active account handed out as a link that its mint didn't know: one it knows again
     * waits to be claimed again, or is taken back if the user asked to (see [reconcile]).
     */
    suspend fun askAgain(id: String) {
        wallet.store
            .note(id)
            ?.takeIf { it.accountId == wallet.activeId() && it.state == NoteState.Unknown && it.handedOut != null }
            ?.let { askAgain(it) }
    }

    /**
     * On refresh, for every account of the active wallet in turn (the accounts that share the active account's recovery phrase), not only
     * the active one: [accountId]'s shared notes are looked up by key. Spent ones are marked so, and their History rows done; one handed
     * out offline that its mint has is confirmed; one its mint doesn't know is set aside ([UnknownNotes]). One the user asked to take back
     * while its mint didn't know it is taken back now that it does ([takeBackIfAsked]), into [accountId]'s own balance; a take back that
     * doesn't go through keeps the wish for the next refresh. Each account's records change only by its own notes.
     */
    suspend fun reconcile(accountId: String) {
        for (note in wallet.store.notes(accountId).first().filter { it.state.isShared }) {
            if (check(note) == Check.Waiting && note.handedOut == HandedOut.TakeBack) takeBackIfAsked(note.id)
        }
    }

    /** Rotates the shared [note] to a fresh wallet key of its account: back in the balance, its History row cancelled. */
    private suspend fun takeBack(note: Note): ReclaimResult {
        val accountId = note.accountId
        val result =
            mutator.rotate(
                fromId = accountId,
                note.host,
                inputs = listOf(note.copy(handedOut = null)),
                outputs = listOf(OutputSpec(accountId, KeySet.Wallet)),
            )
        return when (result) {
            is MutationResult.Done -> {
                cancel(accountId, note.id)
                ReclaimResult.Reclaimed(result.outputs.single().amountMsat.takeIf { it > 0 } ?: note.amountMsat)
            }
            // Either our rotate landed or the note is ours again once reconciled; the link can no longer be counted on.
            is MutationResult.Pending -> {
                cancel(accountId, note.id)
                ReclaimResult.Pending
            }
            // Spent in the meantime, or no longer known to its mint? Otherwise the mint refused, in its own words.
            is MutationResult.Refused -> settled(note, afterRefusal(note)) ?: refused(result.reason)
            is MutationResult.Unreachable -> {
                val again = afterRefusal(note)
                settled(note, again)
                    ?: when {
                        result.keyChanged -> ReclaimResult.Failed(PayProblem.KeyChanged)
                        // Nothing was sent: the lookup before it may have been refused, which asking again tells.
                        else -> again?.let { failure(it) } ?: ReclaimResult.Failed(PayProblem.Unreachable)
                    }
            }
        }
    }

    /** After a take back that didn't go through: where [note] stands now; null if it is gone from the wallet or no longer handed out. */
    private suspend fun afterRefusal(note: Note): Check? {
        val now = wallet.store.note(note.id) ?: return null
        return when {
            now.state == NoteState.Spent -> Check.Spent
            now.state == NoteState.Unknown -> Check.UnknownToMint
            now.state.isShared -> check(now)
            else -> null
        }
    }

    /** A take back of [note] that [check] ends: it was spent, or its mint doesn't know it (the wish is kept); null otherwise. */
    private suspend fun settled(note: Note, check: Check?): ReclaimResult? =
        when (check) {
            Check.Spent -> ReclaimResult.AlreadySpent
            Check.UnknownToMint -> whenKnown(note.id)
            else -> null
        }

    /** Why [check] keeps a take back from going through; null if it doesn't. "Couldn't connect" only when the mint wasn't reached. */
    private fun failure(check: Check): ReclaimResult.Failed? =
        when (check) {
            Check.Offline,
            Check.Unreadable -> ReclaimResult.Failed(PayProblem.Unreachable)
            Check.KeyChanged -> ReclaimResult.Failed(PayProblem.KeyChanged)
            is Check.Refused -> refused(check.reason)
            // Being paid out: a refusal the wallet knows, so in the app's words.
            Check.InUse,
            // Can't be: a mint holding notes can't be removed. Not asked, so not "couldn't connect".
            Check.NoMint -> ReclaimResult.Failed(PayProblem.Refused)
            Check.Waiting,
            Check.Spent,
            Check.UnknownToMint -> null
        }

    /** The mint refused, with its own words ([reason]) as one line of plain text. */
    private fun refused(reason: String?): ReclaimResult.Failed =
        ReclaimResult.Failed(PayProblem.Refused, Payer.mintMessage(reason, wallet.settings.paymentMintMessageMaxCharacters))

    /** The user asked to take back [id], which its mint doesn't know (set aside): it is taken back once the mint knows it. */
    private suspend fun whenKnown(id: String): ReclaimResult {
        wallet.store
            .note(id)
            ?.takeIf { it.handedOut != null }
            ?.let { wallet.store.upsertNotes(listOf(it.copy(handedOut = HandedOut.TakeBack))) }
        return ReclaimResult.WhenKnown
    }

    /** Looks [note] (handed out, unknown to its mint) up again by key ([KeyProber]); null if its mint can't be asked. */
    private suspend fun askAgain(note: Note): KeyProbe? {
        val known = wallet.mintOf(note.accountId, note.host) ?: return null
        val mint = KeyCheckedMint.of(wallet, known) ?: return null
        val now = wallet.now()
        wallet.unknownCheckedAt[note.id] = now
        val probe =
            try {
                prober.probe(KeySet.of(note).key(note.accountId, note.host, note.index ?: return null), mint)
            } catch (e: MintKeyChangedException) {
                return null
            }
        if (probe is KeyProbe.Unused) wallet.unknownAnsweredAt.update { it + (note.id to now) }
        return probe
    }

    /** What a lookup by key says about a handed-out note ([check]). */
    private sealed interface Check {
        /** Its mint has it: nobody claimed it yet. */
        data object Waiting : Check

        /** Its mint has it, being paid out (`pending`): there, but nobody can take it now. */
        data object InUse : Check

        /** Burned: claimed (recorded as spent). */
        data object Spent : Check

        /** Its mint says it doesn't know it (set aside, see [UnknownNotes]). */
        data object UnknownToMint : Check

        /** The mint couldn't be reached. */
        data object Offline : Check

        /** An answer that isn't a mint's (a proxy in front of one that is down): no answer, as [Wallet.heardFrom] takes it. */
        data object Unreadable : Check

        /**
         * The mint answered with a refusal in its own words ([reason], if it gave any), which says nothing of the note: a spent note in its
         * words among them, which proves nothing.
         */
        data class Refused(val reason: String?) : Check

        /**
         * Its signing key doesn't match the pinned one: not asked (its answers about a link carry no key), or the answer counts for
         * nothing.
         */
        data object KeyChanged : Check

        /** Not one of its account's mints: nobody to ask. */
        data object NoMint : Check
    }

    /**
     * Looks [note] up by `p`, never with its spend: burned (`already in use`) means spent (recorded as such); there confirms it. A spent
     * note in the mint's own words proves nothing: a refusal, like any other.
     */
    private suspend fun check(note: Note): Check {
        val known = wallet.mintOf(note.accountId, note.host) ?: return Check.NoMint
        // A mint whose key doesn't match can't tell: its answers about a link carry no key.
        val mint = KeyCheckedMint.of(wallet, known) ?: return Check.KeyChanged
        try {
            Pinning.lookup(wallet, mint, ByteVector32(note.id))
            return confirmed(note, Check.Waiting)
        } catch (e: NoteLookupError) {
            when {
                e.burned || e.burnedOrPending -> Unit
                e.kind == RejectionKind.Pending -> return confirmed(note, Check.InUse)
                e.kind == RejectionKind.NoteUnknown -> {
                    setAside(note)
                    return Check.UnknownToMint
                }
                else -> return Check.Refused(e.reason)
            }
        } catch (e: IOException) {
            return Check.Offline
        } catch (e: MintKeyChangedException) {
            return Check.KeyChanged
        } catch (e: ProtocolViolation) {
            return Check.Unreadable
        }
        wallet.store.upsertNotes(listOf(note.copy(state = NoteState.Spent, updatedAtMillis = wallet.now(), handedOut = null)))
        wallet.sentNoteDone(note)
        return Check.Spent
    }

    /** Its mint has it: a note handed out offline is confirmed now. Returns [check]. */
    private suspend fun confirmed(note: Note, check: Check): Check {
        answered(
            if (note.state == NoteState.SharedUnconfirmed) note.copy(state = NoteState.Shared, updatedAtMillis = wallet.now()) else note
        )
        return check
    }

    /**
     * Its mint doesn't know the shared [note]: set aside like any other unknown note, out of the balance as it was, and asked about again
     * (see [UnknownNotes.recheckDue]); it remembers it was handed out ([Note.handedOut]).
     */
    private suspend fun setAside(note: Note) {
        if (note.state.isShared) wallet.store.upsertNotes(listOf(note.setAside(wallet.now())))
    }

    /** Its mint answered for [note] just now (stored only for a note that is handed out). */
    private suspend fun answered(note: Note) {
        if (note.state.isShared) wallet.store.upsertNotes(listOf(note.copy(checkedAtMillis = wallet.now())))
    }

    private suspend fun cancel(accountId: String, id: String) {
        activity(accountId, id)?.let { wallet.store.putActivity(it.copy(status = ActivityStatus.Cancelled)) }
    }

    /** The link the user hands out: the note's spend, on purpose. */
    @OptIn(RevealsNoteSecret::class)
    private suspend fun link(note: Note): String? {
        val mint = wallet.mintOf(note.accountId, note.host) ?: return null
        if (note.index == null) return null
        val spend = wallet.keys(note.accountId, note.host).spend(note, spendDomain(note.host))
        return NoteLink(mint.withdrawUrl, spend, note.amountMsat, note.certificate).encode()
    }

    private suspend fun activity(accountId: String, noteId: String): Activity? =
        wallet.store.activities(accountId).first().firstOrNull { it.id == activityId(noteId) && it.kind == ActivityKind.SentNote }

    /** The mints to make the note on: the home mint first, then the others (or the other way round, see [Wallet.payOrder]). */
    /**
     * The notes a note can be made from, by mint, in the order the wallet pays ([Wallet.payOrder]); [from] only, if given. Not on a mint
     * whose signing key doesn't match ([Wallet.mintsWithChangedKey]), or ([keyChanged]) only there.
     */
    private suspend fun candidates(accountId: String, from: String? = null, keyChanged: Boolean = false): List<Pair<String, List<Note>>> {
        val order = wallet.payOrder(accountId)
        val changed = wallet.mintsWithChangedKey.first()
        return wallet.store
            .notes(accountId)
            .first()
            .filter { it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.index != null && it.isPickable() }
            .filter { (from == null || it.host == from) && (it.host in changed) == keyChanged }
            .groupBy { it.host }
            .toList()
            .sortedBy { order(it.first) }
    }

    private suspend fun baseFee(accountId: String, host: String): Long = wallet.mintOf(accountId, host)?.feeBaseMsat ?: 0

    companion object {
        fun activityId(noteId: String) = "$SENT_NOTE_PREFIX$noteId"
    }
}

private const val SENT_NOTE_PREFIX = "note:"

/** Handed out as a link and not spent or taken back yet: shared, or set aside as unknown to its mint ([Note.handedOut]). */
internal fun Note.isHandedOut(): Boolean = state.isShared || (state == NoteState.Unknown && handedOut != null)

/** The note [note], handed out as a link, was spent: by whoever holds the link. Its History row is done. */
internal suspend fun Wallet.sentNoteDone(note: Note) = sentNoteEnds(note, ActivityStatus.Done)

/** The note [note], handed out as a link, ended as [status] while nobody knew of it being claimed: its History row says so. */
internal suspend fun Wallet.sentNoteEnds(note: Note, status: ActivityStatus) {
    store
        .activity(NoteSender.activityId(note.id))
        ?.takeIf { it.accountId == note.accountId && it.kind == ActivityKind.SentNote && it.status == ActivityStatus.Pending }
        ?.let { store.putActivity(it.copy(status = status)) }
}

/** The note behind a [ActivityKind.SentNote] History row (for [Wallet.sharedNote]), or null for any other row. */
val Activity.sentNoteId: String?
    get() = id.takeIf { kind == ActivityKind.SentNote }?.removePrefix(SENT_NOTE_PREFIX)
