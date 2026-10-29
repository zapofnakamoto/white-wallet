package whitewallet.core.wallet

import java.io.IOException
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.spendDomain

sealed interface ClaimPreview {
    data class Ready(
        val link: NoteLink,
        val info: NoteInfo,
        val amountMsat: Long,
        /** True if the mint's certificate for this note verified against its key. */
        val certified: Boolean,
        val isOwnMint: Boolean,
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

    /** Refused by the mint; nothing changed. */
    data class Failed(val kind: RejectionKind, val reason: String?) : ClaimResult

    /** Not known yet whether it landed. Kept in the journal and settled on the next refresh. */
    data object Pending : ClaimResult
}

/** Claims a note handed over by someone else: verify it, then rotate it straight into this wallet's own branch. */
internal class NoteClaimer(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
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
        val mint = wallet.store.mint(link.host)
        if (mint?.mintPubkey != null && mint.mintPubkey != info.mintPubkey.toHex()) {
            return ClaimPreview.Invalid("the signing key of ${link.host} changed")
        }
        val certificate = (link.certificate?.let(Cs1::decode) ?: info.certificate)
        val certified = certificate != null && certificate.amountMsat == info.maxWithdrawableMsat &&
            Certificates.verify(certificate, spend.q, info.mintPubkey)
        return ClaimPreview.Ready(link, info, info.maxWithdrawableMsat, certified, isOwnMint = link.host == own,
            trusted = wallet.isTrusted(MintKey(link.host, info.mintPubkey.toHex())))
    }

    suspend fun claim(preview: ClaimPreview.Ready, accountId: String): ClaimResult {
        val host = preview.link.host
        val spend = preview.link.spend
        // A note this app sent as a link: spent with the claim, and its History row settled. Claimed into its own
        // account it is taken back, like NoteSender.reclaim, not received.
        val sent = wallet.store.note(spend.q.toHex())?.takeIf { it.state == NoteState.Shared }
        val received = sent?.accountId != accountId
        return when (val result = mutator.rotateIn(accountId, host, preview.link.withdrawUrl, preview.info, spend, preview.amountMsat, sent)) {
            is MutationResult.Done -> {
                val note = result.outputs.single()
                val amount = note.amountMsat.takeIf { it > 0 } ?: preview.amountMsat
                sent?.let { NoteSender(wallet).claimed(it, accountId) }
                if (received) log.record("claim:${result.journalId}", accountId, ActivityKind.Claimed, amount, host, certified = note.certificate != null || preview.certified)
                rememberMint(host)
                ClaimResult.Claimed(amount, note.certificate != null)
            }
            // Either the rotate landed or the note is back where it was once reconciled.
            is MutationResult.Pending -> {
                sent?.let { NoteSender(wallet).claimed(it, accountId) }
                if (received) log.record("claim:${result.journalId}", accountId, ActivityKind.Claimed, preview.amountMsat, host, ActivityStatus.Pending,
                    certified = preview.certified, journalId = result.journalId)
                ClaimResult.Pending
            }
            is MutationResult.Refused -> ClaimResult.Failed(result.kind, result.reason)
            is MutationResult.Unreachable -> ClaimResult.Failed(RejectionKind.Other, result.reason)
        }
    }

    /**
     * A note from a mint this wallet hasn't used yet: its mint record (withdraw URL, fees, pinned key) is what
     * every later operation on the note needs (paying, combining, moving it). Best effort; a move retries it.
     */
    private suspend fun rememberMint(host: String) {
        if (wallet.store.mint(host) == null) Onboarding(wallet).connectMint(host)
    }
}
