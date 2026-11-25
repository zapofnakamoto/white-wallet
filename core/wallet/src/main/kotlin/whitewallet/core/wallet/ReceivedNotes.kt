package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.UnclaimedReason
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.InvalidMintPubkey
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests

/**
 * Notes received as links (see [ReceivedNote]): kept with their spend from the moment the user keeps or claims one, claimed as soon as
 * their mint can be reached and any lock has passed, and settled with their journal entry when a claim's outcome was unknown. Once claimed,
 * the money is one of the wallet's own notes.
 */
internal class ReceivedNotes(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
    private val log = ActivityLog(wallet)
    private val onboarding = Onboarding(wallet)

    /**
     * Keeps [link] for [accountId]: as [found] when the preview found why it can't be claimed now (locked, or its mint's key changed);
     * otherwise locked if its spend claims a time still ahead, else waiting. [amountMsat] is what its mint or a verified certificate says,
     * if known. A note already kept keeps its place; one being claimed or settled is left as it is.
     */
    suspend fun keep(link: NoteLink, accountId: String, amountMsat: Long?, found: ReceivedState? = null): ReceivedNote {
        val spend = link.spend
        val existing = wallet.store.receivedNote(spend.q.toHex())
        if (existing != null && existing.state !in RETRIED) return existing
        val script = spend as? Spend.Script
        val until = script?.lockedUntilSeconds?.let { it * 1000 }
        val now = wallet.now()
        val state = found ?: if (until != null && until > now) ReceivedState.Locked else ReceivedState.Waiting
        // Stored with its spend: the seed can't derive it again.
        @OptIn(RevealsNoteSecret::class)
        val note =
            ReceivedNote(
                id = spend.q.toHex(),
                accountId,
                link.host,
                link.withdrawUrl,
                spend.k1,
                link.certificate,
                declaredMsat = link.declaredAmountMsat,
                amountMsat = amountMsat ?: existing?.amountMsat,
                lockedUntilMillis = until,
                script?.lockedForSeconds,
                state,
                journalId = null,
                receivedAtMillis = existing?.receivedAtMillis ?: now,
                updatedAtMillis = now,
            )
        wallet.store.putReceivedNote(note)
        if (state == ReceivedState.Waiting) wallet.syncWhenOnline()
        return note
    }

    /**
     * Claims [note] into its account now. [info] is its mint's answer for the note when the caller has one ([certified] if its certificate
     * verified); otherwise the mint is asked here, by the note's key alone. The spend goes out only with the claim itself, and only to a
     * mint that presents the key pinned for it: whoever gets the spend can claim the note. The key check catches a mint presenting another
     * key; one relaying the mint's answers is TLS's to stop.
     */
    suspend fun claim(note: ReceivedNote, info: NoteInfo? = null, certified: Boolean = false): ClaimResult {
        @OptIn(RevealsNoteSecret::class) val spend = note.k1?.let(Spend::parse) ?: return ClaimResult.Failed(RejectionKind.Other, null)
        val answer =
            info
                ?: when (val found = KeyLookup.of(wallet, note.accountId, note.host, note.withdrawUrl, spend.q)) {
                    is KeyLookup.Note -> found.info
                    // As Claim offers: only Lightning brings it in, once the user agrees (asked by key again on each refresh meanwhile).
                    is KeyLookup.OtherService,
                    KeyLookup.NotANote -> return lightningOnly(note, UnclaimedReason.NotANote)
                    is KeyLookup.Refused ->
                        return if (found.burned) gone(note, found.kind, found.reason) else refused(note, spend, found.kind, found.reason)
                    KeyLookup.KeyChanged -> return keyChanged(note)
                    KeyLookup.Unreachable -> return waiting(note)
                }
        val known = wallet.mintOf(note.accountId, note.host)
        val pinned = known?.mintPubkey
        // Before its worth: a mint whose key doesn't match never ends the note by saying it is worth nothing.
        if (pinned != null && pinned != answer.mintPubkey?.toHex()) return keyChanged(note)
        // As Claim offers: the wallet keeps no notes at a mint without a signing key.
        val key = answer.mintPubkey ?: return lightningOnly(note, UnclaimedReason.NoSigningKey)
        // A mint met here for the first time is connected before the claim, with the key it just presented: the claim's new key is then
        // found past the keys the phrase already used there (see [IndexGuard]). If it can't be connected, the claim starts at the first key
        // and the mint is connected after it.
        if (known == null) onboarding.connectMint(wallet.walletOf(note.accountId), note.host, expectedKey = key.toHex())
        val result =
            mutator.rotateIn(note.accountId, note.host, note.withdrawUrl, info = answer, spend, amountMsat = answer.maxWithdrawableMsat)
        return when (result) {
            is MutationResult.Done -> {
                val out = result.outputs.single()
                val amount = out.amountMsat.takeIf { it > 0 } ?: answer.maxWithdrawableMsat
                log.record(
                    id = "claim:${result.journalId}",
                    note.accountId,
                    kind = ActivityKind.Claimed,
                    amountMsat = amount,
                    note.host,
                    certified = out.certificate != null || certified,
                )
                update(
                    note.copy(
                        state = ReceivedState.Claimed,
                        k1 = null,
                        amountMsat = amount,
                        journalId = result.journalId,
                        unclaimedReason = null,
                    )
                )
                if (wallet.mintOf(note.accountId, note.host) == null) onboarding.connectMint(wallet.walletOf(note.accountId), note.host)
                ClaimResult.Claimed(amount, out.certificate != null)
            }
            // Either the rotate landed or the note is back where it was once reconciled; then it is tried again.
            is MutationResult.Pending -> {
                log.record(
                    id = "claim:${result.journalId}",
                    note.accountId,
                    kind = ActivityKind.Claimed,
                    amountMsat = answer.maxWithdrawableMsat,
                    note.host,
                    status = ActivityStatus.Pending,
                    certified = certified,
                    journalId = result.journalId,
                )
                // A claim at its mint, not one over Lightning: settled by its rotate (see [settle]).
                update(
                    note.copy(
                        state = ReceivedState.Claiming,
                        amountMsat = answer.maxWithdrawableMsat,
                        journalId = result.journalId,
                        unclaimedReason = null,
                    )
                )
                ClaimResult.Pending
            }
            // Refused as spent, unknown, pending or already in use (LUD-25: a burned input, or a clash on a new key): gone only if a lookup
            // by its key shows it taken elsewhere.
            is MutationResult.Refused -> {
                val lookUp = result.kind in UnknownNotes.GONE || result.kind in setOf(RejectionKind.AlreadyInUse, RejectionKind.Pending)
                if (lookUp && shownBurned(note)) gone(note, result.kind, result.reason)
                else refused(note, spend, result.kind, result.reason)
            }
            is MutationResult.Unreachable if result.keyChanged -> keyChanged(note)
            is MutationResult.Unreachable -> waiting(note)
        }
    }

    /** Can't be kept at its mint, for [reason]: waits for the user to receive it over Lightning ([LinkWithdrawer]). */
    private suspend fun lightningOnly(note: ReceivedNote, reason: UnclaimedReason): ClaimResult.Kept =
        ClaimResult.Kept(
            update(note.copy(state = ReceivedState.LightningOnly, unclaimedReason = reason, failingSinceMillis = null, journalId = null))
        )

    /** Keeps [link] to be received over Lightning later (see [ReceivedState.LightningOnly]). */
    suspend fun keepForLightning(link: NoteLink, accountId: String, amountMsat: Long?, reason: UnclaimedReason): ReceivedNote {
        val note = keep(link, accountId, amountMsat, ReceivedState.LightningOnly)
        if (note.state != ReceivedState.LightningOnly) return note
        return update(note.copy(unclaimedReason = reason))
    }

    /** [link] is being received over Lightning by invoice [journalId] (see [LinkWithdrawer]), settled with the invoice. */
    suspend fun melting(link: NoteLink, accountId: String, amountMsat: Long, reason: UnclaimedReason, journalId: String): ReceivedNote {
        val note = keep(link, accountId, amountMsat, ReceivedState.LightningOnly)
        return update(
            note.copy(
                state = ReceivedState.Melting,
                amountMsat = amountMsat,
                journalId = journalId,
                unclaimedReason = reason,
                failingSinceMillis = null,
            )
        )
    }

    /** Receiving it over Lightning was refused, or its invoice expired unpaid: it waits for the user again. */
    suspend fun meltFailed(note: ReceivedNote) {
        update(note.copy(state = ReceivedState.LightningOnly, journalId = null))
    }

    /** Received over Lightning: [amountMsat] arrived as one of the wallet's own notes. */
    suspend fun melted(note: ReceivedNote, amountMsat: Long) {
        update(note.copy(state = ReceivedState.Claimed, k1 = null, amountMsat = amountMsat, unclaimedReason = null))
    }

    /**
     * On every refresh: settles claims whose outcome was unknown, drops the spends gone notes kept from older versions (see [forget]), then
     * claims every kept note whose mint may take it now (a lock by date once the date has passed; a wait after crediting is only known to
     * the mint, so it is asked; a changed key is asked about again, and once the user accepted it the note is claimed). Only the notes of
     * the accounts [accountIds]: those of the active wallet.
     */
    suspend fun retryAll(accountIds: Set<String>) {
        for (note in wallet.store.receivedNotes(SETTLED).filter { it.accountId in accountIds }) settle(note)
        for (note in
            wallet.store.receivedNotes(setOf(ReceivedState.Gone, ReceivedState.Archived)).filter { it.accountId in accountIds }) forget(
            note
        )
        for (note in wallet.store.receivedNotes(RETRIED).filter { it.accountId in accountIds }) {
            if (note.state == ReceivedState.Locked && (note.lockedUntilMillis ?: 0) > wallet.now()) continue
            claim(note)
        }
        // Its host didn't answer as for a note (or not yet: a passing trouble looks the same): asked again by key, and claimed at its mint
        // once it does. Any other waits for the user to receive it over Lightning.
        for (note in wallet.store.receivedNotes(setOf(ReceivedState.LightningOnly)).filter { it.accountId in accountIds }) {
            if (note.unclaimedReason != UnclaimedReason.NotANote) continue
            @OptIn(RevealsNoteSecret::class) val spend = note.k1?.let(Spend::parse) ?: continue
            val found = KeyLookup.of(wallet, note.accountId, note.host, note.withdrawUrl, spend.q)
            if (found is KeyLookup.Note) claim(note, found.info)
        }
    }

    /**
     * Puts a kept note aside ([ReceivedState.Archived], by the user): no longer tried until the user tries it again. Not one being claimed,
     * whose outcome is settled first.
     */
    suspend fun archive(id: String) {
        val note = wallet.store.receivedNote(id) ?: return
        if (note.state in RETRIED || note.state == ReceivedState.LightningOnly)
            update(note.copy(state = ReceivedState.Archived, unclaimedReason = UnclaimedReason.ByUser))
    }

    /** Takes an archived note out of the archive and claims it now; kept again, with a new week, if it can't be. Null if not archived. */
    suspend fun retry(id: String): ClaimResult? {
        val note = wallet.store.receivedNote(id)?.takeIf { it.state == ReceivedState.Archived } ?: return null
        return claim(update(note.copy(state = ReceivedState.Waiting, failingSinceMillis = null, unclaimedReason = null)))
    }

    /**
     * Forgets a note for good: one spent by someone else (nothing left to lose), or an archived one, whose spend is gone with it: the
     * recovery phrase can't bring it back.
     */
    suspend fun remove(id: String) {
        val note = wallet.store.receivedNote(id) ?: return
        if (note.state == ReceivedState.Gone || note.state == ReceivedState.Archived) wallet.store.deleteReceivedNote(id)
    }

    /**
     * A claim sent earlier, or an invoice its melt was sent with: claimed if its journal entry landed. If it didn't: a melt waits for the
     * user again; a claim is gone or waiting, by what the mint says.
     */
    private suspend fun settle(note: ReceivedNote) {
        val entry = note.journalId?.let { id -> wallet.store.journal(JournalStatus.entries.toSet()).firstOrNull { it.id == id } }
        when (entry?.status) {
            JournalStatus.Confirmed -> {
                val out = entry.outputs.firstOrNull()?.let { wallet.store.note(it.q) }
                update(
                    note.copy(
                        state = ReceivedState.Claimed,
                        k1 = null,
                        amountMsat = out?.amountMsat ?: note.amountMsat,
                        unclaimedReason = null,
                    )
                )
            }
            JournalStatus.Rejected,
            null -> {
                if (note.state == ReceivedState.Melting) return meltFailed(note)
                val burned =
                    try {
                        isBurned(note)
                    } catch (e: IOException) {
                        return
                    } catch (e: MintKeyChangedException) {
                        keyChanged(note.copy(journalId = null))
                        return
                    }
                if (burned) ended(note) else update(note.copy(state = ReceivedState.Waiting, journalId = null))
            }
            JournalStatus.Prepared,
            JournalStatus.Unknown -> Unit
        }
    }

    /**
     * Whether someone else spent [note] or is paying it out ([takenElsewhere]), asked by its key alone. A note, or a refusal that says
     * spent or unknown in the mint's own words, doesn't show that. Throws [IOException] when it can't tell, and [MintKeyChangedException]
     * when a mint whose key the account pinned presents another or none: its answer can't end the note.
     */
    private suspend fun isBurned(note: ReceivedNote): Boolean =
        try {
            val q = ByteVector32(note.id)
            val pinned = wallet.mintOf(note.accountId, note.host)?.takeIf { it.mintPubkey != null }
            if (pinned != null) {
                Pinning.lookup(wallet, KeyCheckedMint.of(wallet, pinned) ?: throw MintKeyChangedException(note.host), q)
            } else {
                // key-checked: no key is pinned for this host, so there is none to compare.
                wallet.client.lookup(note.withdrawUrl, q)
            }
            false
        } catch (e: NoteLookupError) {
            Pinning.keyed(e)
            when {
                e.takenElsewhere -> true
                e.kind in UnknownNotes.GONE -> false
                else -> throw IOException(e.reason)
            }
        } catch (e: ProtocolViolation) {
            throw IOException(e.message)
        }

    /** [isBurned], or false when the mint can't tell. */
    private suspend fun shownBurned(note: ReceivedNote): Boolean =
        try {
            isBurned(note)
        } catch (e: IOException) {
            false
        } catch (e: MintKeyChangedException) {
            false
        }

    /** Burned by its mint (see [ended]). */
    private suspend fun gone(note: ReceivedNote, kind: RejectionKind, reason: String?): ClaimResult {
        ended(note)
        return ClaimResult.Failed(kind, reason, ClaimFailure.Spent)
    }

    /**
     * Burned by its mint: someone else spent it, or is paying it out. Never asked about again, whatever becomes of that payment: a note two
     * people try to spend isn't tried again. Its spend is dropped.
     */
    private suspend fun ended(note: ReceivedNote): ReceivedNote =
        update(note.copy(state = ReceivedState.Gone, k1 = null, failingSinceMillis = null, unclaimedReason = null))

    /**
     * Older versions kept the spend of a note answered 0, asked about it again for a week and then archived it as spent: dropped now, and
     * such an archived note is gone like any other. Only notes received from others: the wallet's own notes, and a received one being
     * received over Lightning, are payments of its own, settled with their journal entries.
     */
    private suspend fun forget(note: ReceivedNote) {
        @OptIn(RevealsNoteSecret::class) val kept = note.k1 != null
        if (kept && (note.state == ReceivedState.Gone || note.unclaimedReason == UnclaimedReason.Spent)) ended(note)
    }

    /**
     * A refusal of a note its mint didn't show burned: locked if its spend claims a time (the mint's clock decides); else waiting, as for a
     * refusal that says spent or unknown in the mint's own words, which proves nothing: its spend is the only copy.
     */
    private suspend fun refused(note: ReceivedNote, spend: Spend, kind: RejectionKind, reason: String?): ClaimResult {
        val script = spend as? Spend.Script
        if (script?.lockedUntilSeconds != null || script?.lockedForSeconds != null) {
            return ClaimResult.Kept(update(note.copy(state = ReceivedState.Locked, failingSinceMillis = null)))
        }
        // Another refusal (rate limited, a mint's own trouble).
        failing(note, UnclaimedReason.Refused)
        return ClaimResult.Failed(kind, reason, ClaimFailure.Retrying)
    }

    /** Its mint presents another signing key than the pinned one, or none: kept, and tried again on each refresh. */
    private suspend fun keyChanged(note: ReceivedNote): ClaimResult =
        ClaimResult.Kept(update(note.copy(state = ReceivedState.KeyChanged, failingSinceMillis = null)))

    /** Its mint couldn't be reached: kept, and tried again once online. */
    private suspend fun waiting(note: ReceivedNote): ClaimResult {
        val kept = failing(note, UnclaimedReason.Unreachable)
        if (kept.state == ReceivedState.Waiting) wallet.syncWhenOnline()
        return ClaimResult.Kept(kept)
    }

    /**
     * A claim that failed for [reason]: tried again on every refresh, for [Wallet.receivedGiveUpMillis] from the first failure (whatever
     * its reason), then archived with the last one. Never deleted: its spend is the only copy.
     */
    private suspend fun failing(note: ReceivedNote, reason: UnclaimedReason): ReceivedNote {
        val since = note.failingSinceMillis ?: wallet.now()
        val givenUp = wallet.now() - since >= wallet.receivedGiveUpMillis
        return update(
            note.copy(
                state = if (givenUp) ReceivedState.Archived else ReceivedState.Waiting,
                failingSinceMillis = since,
                unclaimedReason = reason.takeIf { givenUp },
            )
        )
    }

    private suspend fun update(note: ReceivedNote): ReceivedNote =
        note.copy(updatedAtMillis = wallet.now()).also { wallet.store.putReceivedNote(it) }

    private companion object {
        /** Kept, and claimed by a refresh as soon as it can be. */
        val RETRIED = setOf(ReceivedState.Waiting, ReceivedState.Locked, ReceivedState.KeyChanged)

        /** Sent, at its mint or over Lightning, and settled by a refresh with its journal entry. */
        val SETTLED = setOf(ReceivedState.Claiming, ReceivedState.Melting)
    }
}

/**
 * What a link's host answers when asked by the note's key alone (`?p=`): the spend never goes out with it. A link shaped like a note may
 * also be a withdraw link of another kind of service, which only this answer tells apart.
 */
internal sealed interface KeyLookup {
    /** A mint's answer for the note. */
    data class Note(val info: NoteInfo) : KeyLookup

    /**
     * A withdraw answer with the service's own [k1], which no mint's answer by a note's key carries (LUD-25): another kind of service,
     * which pays between [minMsat] and [maxMsat] to [callback].
     */
    data class OtherService(val callback: String, @property:RevealsNoteSecret val k1: String, val minMsat: Long, val maxMsat: Long) :
        KeyLookup

    /** No answer for a note: a service that answers only the link itself, or a host's passing trouble (a proxy's error page). */
    data object NotANote : KeyLookup

    /**
     * The mint's error for the note: [burned] (see [takenElsewhere]), spent or unknown in the mint's own words (which prove nothing), or
     * refused for another reason.
     */
    data class Refused(val kind: RejectionKind, val reason: String?, val burned: Boolean = false) : KeyLookup

    /**
     * A mint whose key the account's wallet pinned doesn't match it: not asked, or it answered for the note with a `mintPubkey` that isn't
     * a key, which is none (see [Pinning.invalidKey]). From any other host, that answer is [NotANote].
     */
    data object KeyChanged : KeyLookup

    /**
     * No answer at all; or, from a mint whose key the account's wallet pinned, none as for a note. Such a mint is never shown the link nor
     * paid out over Lightning ([SecretGate.releaseLink]): its notes wait for it to answer again (a deploy's error page, a challenge page),
     * and whoever answers in its place never gets the spend.
     */
    data object Unreachable : KeyLookup

    companion object {
        /** Asks [withdrawUrl], the link's host [host], about the note [q] for [accountId]. */
        suspend fun of(wallet: Wallet, accountId: String, host: String, withdrawUrl: String, q: ByteVector32): KeyLookup {
            val mint = wallet.mintOf(accountId, host)?.takeIf { it.mintPubkey != null }
            // Most answers carry no key: a mint whose key doesn't match isn't asked (see [KeyCheckedMint]).
            if (mint != null && KeyCheckedMint.of(wallet, mint) == null) return KeyChanged
            val pinned = mint != null
            val found = ask(wallet, host, withdrawUrl, q, mint?.mintPubkey)
            return if (pinned && (found is OtherService || found == NotANote)) Unreachable else found
        }

        /**
         * [pinnedKey]: the key the account's wallet pinned for the mint, if any. Whether [host] answered is kept ([Wallet.heardFrom]): a
         * received note waiting for a mint the account holds nothing on is told by it.
         */
        private suspend fun ask(wallet: Wallet, host: String, withdrawUrl: String, q: ByteVector32, pinnedKey: String?): KeyLookup {
            val pinned = pinnedKey != null
            val answer =
                try {
                    // key-checked: by [of], before asking.
                    wallet.client.get(Requests.lookup(withdrawUrl, q))
                } catch (e: IOException) {
                    wallet.heardFrom(host, answered = false)
                    return Unreachable
                } catch (e: ProtocolViolation) {
                    // As [KeyProber]: an answer that isn't a mint's (a proxy in front of one that is down) isn't one.
                    wallet.heardFrom(host, answered = false)
                    return NotANote
                }
            wallet.heardFrom(host)
            if (answer is LnurlResponse.WithdrawRequest) {
                answer.k1?.let {
                    return OtherService(answer.callback, it, answer.minWithdrawable, answer.maxWithdrawable)
                }
            }
            return try {
                Note(NoteInfo.from(answer, null))
            } catch (e: NoteLookupError) {
                // Answered 0: it ends a note only from a mint whose key matches, or, with none pinned, one that presents a key.
                if (e.burnedOrPending && pinned && e.mintPubkey?.toHex() != pinnedKey) return KeyChanged
                if (e.burnedOrPending && e.mintPubkey == null) return Unreachable
                Refused(e.kind, e.reason, burned = e.takenElsewhere)
            } catch (e: InvalidMintPubkey) {
                if (pinned) KeyChanged else NotANote
            } catch (e: ProtocolViolation) {
                NotANote
            }
        }
    }
}

/**
 * A received note someone else spent, or is paying out: its mint answered `already in use`, 0 (either), or `pending`. Never tried again,
 * whatever becomes of that payment. Only for notes received as links: the wallet's own note answered so is a payment of its own.
 */
internal val NoteLookupError.takenElsewhere: Boolean
    get() = burned || burnedOrPending || kind == RejectionKind.Pending
