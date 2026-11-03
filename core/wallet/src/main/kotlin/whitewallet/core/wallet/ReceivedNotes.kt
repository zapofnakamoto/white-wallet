package whitewallet.core.wallet

import java.io.IOException
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import fr.acinq.bitcoin.ByteVector32

/**
 * Notes received as links (see [ReceivedNote]): kept with their spend from the moment the user keeps or claims one,
 * claimed as soon as their mint can be reached and any lock has passed, and settled with their journal entry when a
 * claim's outcome was unknown. Once claimed, the money is one of the wallet's own notes.
 */
internal class ReceivedNotes(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
    private val log = ActivityLog(wallet)

    /**
     * Keeps [link] for [accountId]: locked if its spend claims a time still ahead, or [locked] (its mint said so),
     * waiting otherwise. [amountMsat] is what its mint or a verified certificate says, if known. A note already kept
     * keeps its place; one being claimed or settled is left as it is.
     */
    suspend fun keep(link: NoteLink, accountId: String, amountMsat: Long?, locked: Boolean = false): ReceivedNote {
        val spend = link.spend
        val existing = wallet.store.receivedNote(spend.q.toHex())
        if (existing != null && existing.state != ReceivedState.Waiting && existing.state != ReceivedState.Locked) return existing
        val script = spend as? Spend.Script
        val until = script?.lockedUntilSeconds?.let { it * 1000 }
        val now = wallet.now()
        val state = if (locked || (until != null && until > now)) ReceivedState.Locked else ReceivedState.Waiting
        val note = ReceivedNote(spend.q.toHex(), accountId, link.host, link.withdrawUrl, spend.k1, link.certificate, link.declaredAmountMsat,
            amountMsat ?: existing?.amountMsat, until, script?.lockedForSeconds, state, null, existing?.receivedAtMillis ?: now, now)
        wallet.store.putReceivedNote(note)
        if (state == ReceivedState.Waiting) wallet.syncWhenOnline()
        return note
    }

    /**
     * Claims [note] into its account now. [info] is its mint's answer for its spend when the caller has one (with the
     * mint's key checked, and [certified] if its certificate verified); otherwise the mint is asked here.
     */
    suspend fun claim(note: ReceivedNote, info: NoteInfo? = null, certified: Boolean = false): ClaimResult {
        val spend = note.k1?.let(Spend::parse) ?: return ClaimResult.Failed(RejectionKind.Other, null)
        val answer = info ?: try {
            wallet.client.noteInfo(note.withdrawUrl, spend.k1).also { answer ->
                // Nothing is claimed from a mint that signs with another key than the pinned one (see Pinning).
                val pinned = wallet.store.mint(note.host)?.mintPubkey
                if (pinned != null && pinned != answer.mintPubkey.toHex()) return waiting(note)
            }
        } catch (e: NoteLookupError) {
            return refused(note, spend, e.kind, e.reason)
        } catch (e: ProtocolViolation) {
            return waiting(note)
        } catch (e: IOException) {
            return waiting(note)
        }
        if (answer.maxWithdrawableMsat <= 0) return refused(note, spend, RejectionKind.Spent, null)
        val result = mutator.rotateIn(note.accountId, note.host, note.withdrawUrl, answer, spend, answer.maxWithdrawableMsat, sent = null)
        return when (result) {
            is MutationResult.Done -> {
                val out = result.outputs.single()
                val amount = out.amountMsat.takeIf { it > 0 } ?: answer.maxWithdrawableMsat
                log.record("claim:${result.journalId}", note.accountId, ActivityKind.Claimed, amount, note.host, certified = out.certificate != null || certified)
                update(note.copy(state = ReceivedState.Claimed, k1 = null, amountMsat = amount, journalId = result.journalId))
                if (wallet.store.mint(note.host) == null) Onboarding(wallet).connectMint(note.host)
                ClaimResult.Claimed(amount, out.certificate != null)
            }
            // Either the rotate landed or the note is back where it was once reconciled; then it is tried again.
            is MutationResult.Pending -> {
                log.record("claim:${result.journalId}", note.accountId, ActivityKind.Claimed, answer.maxWithdrawableMsat, note.host, ActivityStatus.Pending,
                    certified = certified, journalId = result.journalId)
                update(note.copy(state = ReceivedState.Claiming, amountMsat = answer.maxWithdrawableMsat, journalId = result.journalId))
                ClaimResult.Pending
            }
            is MutationResult.Refused -> refused(note, spend, result.kind, result.reason)
            is MutationResult.Unreachable -> waiting(note)
        }
    }

    /**
     * On every refresh: settles claims whose outcome was unknown, then claims every kept note whose mint may take it
     * now (a lock by date once the date has passed; a wait after crediting is only known to the mint, so it is asked).
     */
    suspend fun retryAll() {
        for (note in wallet.store.receivedNotes(setOf(ReceivedState.Claiming))) settle(note)
        for (note in wallet.store.receivedNotes(setOf(ReceivedState.Waiting, ReceivedState.Locked))) {
            if (note.state == ReceivedState.Locked && (note.lockedUntilMillis ?: 0) > wallet.now()) continue
            claim(note)
        }
    }

    /** Forgets a note that is no longer worth anything (spent by someone else, or not spendable). */
    suspend fun dismiss(id: String) {
        val note = wallet.store.receivedNote(id) ?: return
        if (note.state == ReceivedState.Gone || note.state == ReceivedState.Invalid) wallet.store.deleteReceivedNote(id)
    }

    /** A claim sent earlier: claimed if its journal entry landed; if it didn't, gone or waiting, by what the mint says. */
    private suspend fun settle(note: ReceivedNote) {
        val entry = note.journalId?.let { id -> wallet.store.journal(JournalStatus.entries.toSet()).firstOrNull { it.id == id } }
        when (entry?.status) {
            JournalStatus.Confirmed -> {
                val out = entry.outputs.firstOrNull()?.let { wallet.store.note(it.q) }
                update(note.copy(state = ReceivedState.Claimed, k1 = null, amountMsat = out?.amountMsat ?: note.amountMsat))
            }
            JournalStatus.Rejected, null -> {
                val live = try {
                    isLive(note)
                } catch (e: IOException) {
                    return
                }
                if (live) update(note.copy(state = ReceivedState.Waiting, journalId = null)) else update(note.copy(state = ReceivedState.Gone, k1 = null))
            }
            else -> Unit
        }
    }

    /** Whether its mint still holds [note], asked by its key alone. Throws [IOException] when it can't tell. */
    private suspend fun isLive(note: ReceivedNote): Boolean = try {
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
            return ClaimResult.Kept(update(note.copy(state = ReceivedState.Locked)))
        }
        update(note.copy(state = ReceivedState.Waiting))
        return ClaimResult.Failed(kind, reason)
    }

    private suspend fun waiting(note: ReceivedNote): ClaimResult {
        val kept = update(note.copy(state = ReceivedState.Waiting))
        wallet.syncWhenOnline()
        return ClaimResult.Kept(kept)
    }

    private suspend fun update(note: ReceivedNote): ReceivedNote =
        note.copy(updatedAtMillis = wallet.now()).also { wallet.store.putReceivedNote(it) }
}
