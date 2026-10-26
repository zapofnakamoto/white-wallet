package whitewallet.core.wallet

import java.io.IOException
import java.util.UUID
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.core.model.OutputRef
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain

sealed interface ClaimPreview {
    data class Ready(
        val link: NoteLink,
        val info: NoteInfo,
        val amountMsat: Long,
        /** True if the service's certificate for this note verified against its key. */
        val certified: Boolean,
        val isOwnService: Boolean,
        /** The note's mint, with the key it signs with now, is one the user trusts (see [Wallet.mints]). */
        val trusted: Boolean = false,
    ) : ClaimPreview

    data class Invalid(val reason: String) : ClaimPreview
    data object AlreadySpent : ClaimPreview
    data object AlreadyInWallet : ClaimPreview
    data class NetworkError(val reason: String) : ClaimPreview
}

sealed interface ClaimResult {
    data class Claimed(val amountMsat: Long, val certified: Boolean) : ClaimResult

    /** Refused by the service; nothing changed. */
    data class Failed(val kind: RejectionKind, val reason: String?) : ClaimResult

    /** Not known yet whether it landed. Kept in the journal and settled on the next refresh. */
    data object Pending : ClaimResult
}

/** Claims a note handed over by someone else: verify it, then rotate it straight into this wallet's own branch. */
internal class NoteClaimer(private val wallet: Wallet) {
    private val guard = IndexGuard(wallet)
    private val log = ActivityLog(wallet)

    suspend fun preview(input: String, accountId: String): ClaimPreview {
        val link = NoteLink.parse(input) ?: return ClaimPreview.Invalid("not a note")
        val spend = link.spend
        if (spend is Spend.Script) return ClaimPreview.Invalid("unsupported note type")
        if (spend is Spend.KeyPath && !spend.verify(spendDomain(link.host))) return ClaimPreview.Invalid("this note is not valid for ${link.host}")
        wallet.store.note(spend.q.toHex())?.let { if (it.state == NoteState.Outstanding) return ClaimPreview.AlreadyInWallet }
        val info = try {
            wallet.client.noteInfo(link.withdrawUrl, spend.k1)
        } catch (e: NoteLookupError) {
            return if (e.kind == RejectionKind.Spent || e.kind == RejectionKind.NoteUnknown) ClaimPreview.AlreadySpent else ClaimPreview.Invalid(e.reason ?: "refused")
        } catch (e: ProtocolViolation) {
            return ClaimPreview.Invalid(e.message ?: "invalid response")
        } catch (e: IOException) {
            return ClaimPreview.NetworkError(e.message ?: "network error")
        }
        if (info.maxWithdrawableMsat <= 0) return ClaimPreview.AlreadySpent
        val own = wallet.store.account(accountId)?.host
        val service = wallet.store.service(link.host)
        if (service?.mintPubkey != null && service.mintPubkey != info.mintPubkey.toHex()) {
            return ClaimPreview.Invalid("the signing key of ${link.host} changed")
        }
        val certificate = (link.certificate?.let(Cs1::decode) ?: info.certificate)
        val certified = certificate != null && certificate.amountMsat == info.maxWithdrawableMsat &&
            Certificates.verify(certificate, spend.q, info.mintPubkey)
        return ClaimPreview.Ready(link, info, info.maxWithdrawableMsat, certified, isOwnService = link.host == own,
            trusted = wallet.isTrusted(MintKey(link.host, info.mintPubkey.toHex())))
    }

    suspend fun claim(preview: ClaimPreview.Ready, accountId: String): ClaimResult {
        val host = preview.link.host
        val spend = preview.link.spend
        // A note this account sent as a link and now claims itself: taken back, like NoteSender.reclaim, not received.
        val ownShared = wallet.store.note(spend.q.toHex())?.takeIf { it.accountId == accountId && it.state == NoteState.Shared }
        repeat(MAX_INDEX_RETRIES) {
            // Persist before disclose: the index is reserved and the journal written before the cp1 leaves the device.
            val reserved = guard.reserve(accountId, host, NotePurpose.Wallet)
            val index = reserved.ref.index
            val q = reserved.key
            val url = Requests.rotate(preview.info.callback, spend.k1, q)
            val created = wallet.now()
            val entry = JournalEntry(UUID.randomUUID().toString(), accountId, OperationType.Rotate, host, preview.link.withdrawUrl,
                listOf(spend.q.toHex()), listOf(spend.k1), listOf(OutputRef(accountId, NotePurpose.Wallet.value, index, q.toHex())),
                url, JournalStatus.Prepared, null, created, created)
            wallet.store.putJournal(entry)

            when (val outcome = wallet.client.mutate(MutationType.Rotate, url)) {
                is MutationOutcome.Confirmed, is MutationOutcome.Unverifiable -> {
                    val c = (outcome as? MutationOutcome.Confirmed)?.certificate ?: (outcome as? MutationOutcome.Unverifiable)?.certificate
                    val cert = c?.let(Cs1::decode)?.takeIf { Certificates.verify(it, q, preview.info.mintPubkey) }
                    val amount = cert?.amountMsat ?: preview.amountMsat
                    wallet.store.upsertNotes(listOf(Note(q.toHex(), accountId, host, NoteKind.KeyPath, NotePurpose.Wallet.value, index, amount,
                        cert?.encode(), NoteState.Outstanding, wallet.now())))
                    wallet.store.putJournal(entry.copy(status = JournalStatus.Confirmed, updatedAtMillis = wallet.now()))
                    markSpent(spend.q.toHex(), accountId)
                    if (ownShared == null) log.record("claim:${entry.id}", accountId, ActivityKind.Claimed, amount, host, certified = cert != null || preview.certified)
                    rememberService(host)
                    return ClaimResult.Claimed(amount, cert != null)
                }
                is MutationOutcome.Rejected -> {
                    wallet.store.putJournal(entry.copy(status = JournalStatus.Rejected, detail = outcome.reason, updatedAtMillis = wallet.now()))
                    if (outcome.kind != RejectionKind.AlreadyInUse) return ClaimResult.Failed(outcome.kind, outcome.reason)
                    // The local index is behind what the service has seen: find the real one before retrying.
                    guard.recover(accountId, host, NotePurpose.Wallet)
                }
                is MutationOutcome.Ambiguous -> {
                    val unknown = entry.copy(status = JournalStatus.Unknown, detail = outcome.cause, updatedAtMillis = wallet.now())
                    wallet.store.putJournal(unknown)
                    val settled = Reconciler(wallet).reconcile(unknown)
                    val claimed = wallet.store.note(q.toHex())?.amountMsat ?: preview.amountMsat
                    if (settled != JournalStatus.Rejected && ownShared != null) {
                        // Either the rotate landed or the note is ours again once reconciled, as for NoteSender.reclaim.
                        NoteSender(wallet).claimed(ownShared, accountId)
                    } else if (settled != JournalStatus.Rejected) {
                        val status = if (settled == JournalStatus.Confirmed) ActivityStatus.Done else ActivityStatus.Pending
                        log.record("claim:${entry.id}", accountId, ActivityKind.Claimed, claimed, host, status, certified = preview.certified, journalId = entry.id)
                    }
                    return when (settled) {
                        JournalStatus.Confirmed -> ClaimResult.Claimed(claimed, false)
                        JournalStatus.Rejected -> ClaimResult.Failed(RejectionKind.Other, "the claim was not completed")
                        else -> ClaimResult.Pending
                    }
                }
            }
        }
        return ClaimResult.Failed(RejectionKind.AlreadyInUse, "no free note index")
    }

    /** The claimed note, if it is one of this app's: spent now, and if it was sent as a link, its History row settled. */
    private suspend fun markSpent(id: String, into: String) {
        val note = wallet.store.note(id) ?: return
        wallet.store.upsertNotes(listOf(note.copy(state = NoteState.Spent, updatedAtMillis = wallet.now())))
        if (note.state == NoteState.Shared) NoteSender(wallet).claimed(note, into)
    }

    companion object {
        private const val MAX_INDEX_RETRIES = 3
    }

    /**
     * A note from a mint this wallet hasn't used yet: its service record (withdraw URL, fees, pinned key) is what
     * every later operation on the note needs (paying, combining, moving it). Best effort; a move retries it.
     */
    private suspend fun rememberService(host: String) {
        if (wallet.store.service(host) == null) Onboarding(wallet).connectService(host)
    }
}
