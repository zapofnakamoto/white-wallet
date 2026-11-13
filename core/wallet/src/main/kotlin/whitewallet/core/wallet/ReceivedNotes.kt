package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.ArchiveReason
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind

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
                ?: try {
                    wallet.client.lookup(note.withdrawUrl, spend.q)
                } catch (e: NoteLookupError) {
                    return refused(note, spend, e.kind, e.reason)
                } catch (e: ProtocolViolation) {
                    return waiting(note, ArchiveReason.NotANote)
                } catch (e: IOException) {
                    return waiting(note, ArchiveReason.Unreachable)
                }
        if (answer.maxWithdrawableMsat <= 0) return refused(note, spend, RejectionKind.Spent, null)
        val known = wallet.mintOf(note.accountId, note.host)
        val pinned = known?.mintPubkey
        if (pinned != null && pinned != answer.mintPubkey.toHex())
            return ClaimResult.Kept(update(note.copy(state = ReceivedState.KeyChanged, failingSinceMillis = null)))
        // A mint met here for the first time is connected before the claim, with the key it just presented: the claim's new key is then
        // found past the keys the phrase already used there (see [IndexGuard]). If it can't be connected, the claim starts at the first key
        // and the mint is connected after it.
        if (known == null) onboarding.connectMint(wallet.walletOf(note.accountId), note.host, expectedKey = answer.mintPubkey.toHex())
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
                update(note.copy(state = ReceivedState.Claimed, k1 = null, amountMsat = amount, journalId = result.journalId))
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
                update(note.copy(state = ReceivedState.Claiming, amountMsat = answer.maxWithdrawableMsat, journalId = result.journalId))
                ClaimResult.Pending
            }
            is MutationResult.Refused -> refused(note, spend, result.kind, result.reason)
            is MutationResult.Unreachable -> waiting(note, ArchiveReason.Unreachable)
        }
    }

    /**
     * On every refresh: settles claims whose outcome was unknown, then claims every kept note whose mint may take it now (a lock by date
     * once the date has passed; a wait after crediting is only known to the mint, so it is asked; a changed key is asked about again, and
     * once the user accepted it the note is claimed). Only the notes of the accounts [accountIds]: those of the active wallet.
     */
    suspend fun retryAll(accountIds: Set<String>) {
        for (note in wallet.store.receivedNotes(setOf(ReceivedState.Claiming)).filter { it.accountId in accountIds }) settle(note)
        for (note in wallet.store.receivedNotes(RETRIED).filter { it.accountId in accountIds }) {
            if (note.state == ReceivedState.Locked && (note.lockedUntilMillis ?: 0) > wallet.now()) continue
            claim(note)
        }
    }

    /**
     * Puts a kept note aside ([ReceivedState.Archived], by the user): no longer tried until the user tries it again. Not one being claimed,
     * whose outcome is settled first.
     */
    suspend fun archive(id: String) {
        val note = wallet.store.receivedNote(id) ?: return
        if (note.state in RETRIED) update(note.copy(state = ReceivedState.Archived, archivedReason = ArchiveReason.ByUser))
    }

    /** Takes an archived note out of the archive and claims it now; kept again, with a new week, if it can't be. Null if not archived. */
    suspend fun retry(id: String): ClaimResult? {
        val note = wallet.store.receivedNote(id)?.takeIf { it.state == ReceivedState.Archived } ?: return null
        return claim(update(note.copy(state = ReceivedState.Waiting, failingSinceMillis = null, archivedReason = null)))
    }

    /**
     * Forgets a note for good: one spent by someone else (nothing left to lose), or an archived one, whose spend is gone with it: the
     * recovery phrase can't bring it back.
     */
    suspend fun remove(id: String) {
        val note = wallet.store.receivedNote(id) ?: return
        if (note.state == ReceivedState.Gone || note.state == ReceivedState.Archived) wallet.store.deleteReceivedNote(id)
    }

    /** A claim sent earlier: claimed if its journal entry landed; if it didn't, gone or waiting, by what the mint says. */
    private suspend fun settle(note: ReceivedNote) {
        val entry = note.journalId?.let { id -> wallet.store.journal(JournalStatus.entries.toSet()).firstOrNull { it.id == id } }
        when (entry?.status) {
            JournalStatus.Confirmed -> {
                val out = entry.outputs.firstOrNull()?.let { wallet.store.note(it.q) }
                update(note.copy(state = ReceivedState.Claimed, k1 = null, amountMsat = out?.amountMsat ?: note.amountMsat))
            }
            JournalStatus.Rejected,
            null -> {
                val live =
                    try {
                        isLive(note)
                    } catch (e: IOException) {
                        return
                    }
                if (live) update(note.copy(state = ReceivedState.Waiting, journalId = null))
                else update(note.copy(state = ReceivedState.Gone, k1 = null))
            }
            else -> Unit
        }
    }

    /** Whether its mint still holds [note], asked by its key alone. Throws [IOException] when it can't tell. */
    private suspend fun isLive(note: ReceivedNote): Boolean =
        try {
            wallet.client.lookup(note.withdrawUrl, ByteVector32(note.id)).maxWithdrawableMsat > 0
        } catch (e: NoteLookupError) {
            if (e.kind in UnknownNotes.GONE) false else throw IOException(e.reason)
        } catch (e: ProtocolViolation) {
            throw IOException(e.message)
        }

    /** A refusal: gone if the mint doesn't hold the note; locked if its spend claims a time (the mint's clock decides); else waiting. */
    private suspend fun refused(note: ReceivedNote, spend: Spend, kind: RejectionKind, reason: String?): ClaimResult {
        if (kind in UnknownNotes.GONE) {
            update(note.copy(state = ReceivedState.Gone, k1 = null))
            return ClaimResult.Failed(kind, reason)
        }
        val script = spend as? Spend.Script
        if (script?.lockedUntilSeconds != null || script?.lockedForSeconds != null) {
            return ClaimResult.Kept(update(note.copy(state = ReceivedState.Locked, failingSinceMillis = null)))
        }
        // Another refusal (rate limited, a mint's own trouble).
        failing(note, ArchiveReason.Refused)
        return ClaimResult.Failed(kind, reason)
    }

    /** Its mint couldn't be reached, or didn't answer as for a note: kept, and tried again once online. */
    private suspend fun waiting(note: ReceivedNote, reason: ArchiveReason): ClaimResult {
        val kept = failing(note, reason)
        if (kept.state == ReceivedState.Waiting) wallet.syncWhenOnline()
        return ClaimResult.Kept(kept)
    }

    /**
     * A claim that failed for [reason]: tried again on every refresh, for [Wallet.receivedGiveUpMillis] from the first failure (whatever
     * its reason), then archived with the last one. Never deleted: its spend is the only copy.
     */
    private suspend fun failing(note: ReceivedNote, reason: ArchiveReason): ReceivedNote {
        val since = note.failingSinceMillis ?: wallet.now()
        val givenUp = wallet.now() - since >= wallet.receivedGiveUpMillis
        return update(
            note.copy(
                state = if (givenUp) ReceivedState.Archived else ReceivedState.Waiting,
                failingSinceMillis = since,
                archivedReason = reason.takeIf { givenUp },
            )
        )
    }

    private suspend fun update(note: ReceivedNote): ReceivedNote =
        note.copy(updatedAtMillis = wallet.now()).also { wallet.store.putReceivedNote(it) }

    private companion object {
        /** Kept, and claimed by a refresh as soon as it can be. */
        val RETRIED = setOf(ReceivedState.Waiting, ReceivedState.Locked, ReceivedState.KeyChanged)
    }
}
