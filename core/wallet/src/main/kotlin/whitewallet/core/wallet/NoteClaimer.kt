package whitewallet.core.wallet

import fr.acinq.bitcoin.PublicKey
import java.io.IOException
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
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
        /** The note may be spent another way too (a script note with other leaves or a real internal key). */
        val hasOtherPaths: Boolean = false,
    ) : ClaimPreview

    /** Can't be claimed now, but can be kept ([Wallet.keepClaim]) and is claimed as soon as it can be. */
    sealed interface Keepable : ClaimPreview {
        val link: NoteLink
    }

    /**
     * Its mint can't be reached. [declaredMsat] is what the link says; [certifiedMsat] what its certificate says, if
     * it verified against the pinned key of a mint the wallet knows.
     */
    data class Offline(override val link: NoteLink, val declaredMsat: Long?, val certifiedMsat: Long?) : Keepable

    /**
     * Its spend claims a time its mint hasn't reached: [untilMillis] (a date), or [forSeconds] after its mint last
     * credited the note, which only the mint knows. [amountMsat] is what the mint says, if it could be asked.
     */
    data class Locked(override val link: NoteLink, val untilMillis: Long?, val forSeconds: Long?, val amountMsat: Long?) : Keepable

    /**
     * Its mint ([host]) presents another signing key than the one pinned for it, so the spend isn't sent there: a host
     * taken over by someone else couldn't present the mint's key. Kept, it is claimed once the user accepts the new key
     * (the mint's details). [amountMsat] is what the mint says now.
     */
    data class KeyChanged(override val link: NoteLink, val host: String, val amountMsat: Long) : Keepable

    /** A script note whose spend doesn't satisfy its script, or claims a time no mint honours (a block height). */
    data object NotSpendable : ClaimPreview

    data class Invalid(val reason: String) : ClaimPreview
    data object AlreadySpent : ClaimPreview
    data object AlreadyInWallet : ClaimPreview
}

sealed interface ClaimResult {
    data class Claimed(val amountMsat: Long, val certified: Boolean) : ClaimResult

    /** Refused by the mint; nothing changed. */
    data class Failed(val kind: RejectionKind, val reason: String?) : ClaimResult

    /** Not known yet whether it landed. Kept in the journal and settled on the next refresh. */
    data object Pending : ClaimResult

    /** Not claimed yet, but kept: claimed by a refresh once its mint can be reached and any lock has passed. */
    data class Kept(val note: ReceivedNote) : ClaimResult
}

/**
 * Claims a note handed over by someone else: verify it, keep it (see [ReceivedNotes]), then rotate it straight into
 * this wallet's own branch.
 */
internal class NoteClaimer(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
    private val log = ActivityLog(wallet)
    private val received = ReceivedNotes(wallet)

    suspend fun preview(input: String, accountId: String): ClaimPreview {
        val link = NoteLink.parse(input) ?: return ClaimPreview.Invalid("not a note")
        val spend = link.spend
        val domain = spendDomain(link.host)
        if (spend is Spend.KeyPath && !spend.verify(domain)) return ClaimPreview.Invalid("this note is not valid for ${link.host}")
        val script = spend as? Spend.Script
        if (script != null && (!script.isValidLockClaim || !script.verify(domain))) return ClaimPreview.NotSpendable
        wallet.store.note(spend.q.toHex())?.let { if (it.state == NoteState.Outstanding) return ClaimPreview.AlreadyInWallet }
        if (wallet.store.receivedNote(spend.q.toHex())?.state == ReceivedState.Claimed) return ClaimPreview.AlreadyInWallet
        // A lock by date still ahead: no use asking with the spend, the mint refuses it until then.
        if (script?.lockedUntilSeconds?.let { it * 1000 > wallet.now() } == true) return locked(link, script)
        // Asked by the note's key alone: the spend goes out only with the claim, to a mint with the pinned key. A wait
        // after crediting is the mint's to judge then; its refusal keeps the note as locked.
        val info = try {
            wallet.client.lookup(link.withdrawUrl, spend.q)
        } catch (e: NoteLookupError) {
            return when (e.kind) {
                RejectionKind.Spent, RejectionKind.NoteUnknown -> ClaimPreview.AlreadySpent
                else -> ClaimPreview.Invalid(e.reason ?: "refused")
            }
        } catch (e: ProtocolViolation) {
            return ClaimPreview.Invalid(e.message ?: "invalid response")
        } catch (e: IOException) {
            return ClaimPreview.Offline(link, link.declaredAmountMsat, certifiedOffline(link))
        }
        if (info.maxWithdrawableMsat <= 0) return ClaimPreview.AlreadySpent
        val own = wallet.store.account(accountId)?.host
        val mint = wallet.store.mint(link.host)
        if (mint?.mintPubkey != null && mint.mintPubkey != info.mintPubkey.toHex()) return ClaimPreview.KeyChanged(link, link.host, info.maxWithdrawableMsat)
        return ClaimPreview.Ready(link, info, info.maxWithdrawableMsat, certified(link, info), isOwnMint = link.host == own,
            trusted = wallet.isTrusted(MintKey(link.host, info.mintPubkey.toHex())), hasOtherPaths = script?.hasOtherPaths == true)
    }

    suspend fun claim(preview: ClaimPreview.Ready, accountId: String): ClaimResult {
        val spend = preview.link.spend
        // A note this app sent as a link: spent with the claim, and its History row settled. It is this wallet's own
        // note already (its key derives from a seed here), so it isn't kept as a received one.
        val sent = wallet.store.note(spend.q.toHex())?.takeIf { it.state == NoteState.Shared }
        if (sent != null) return claimSent(preview, sent, accountId)
        val note = received.keep(preview.link, accountId, preview.amountMsat)
        return received.claim(note, preview.info, preview.certified)
    }

    /** Keeps a note that can't be claimed now; a refresh claims it once it can be. */
    suspend fun keep(preview: ClaimPreview.Keepable, accountId: String): ClaimResult.Kept = when (preview) {
        is ClaimPreview.Offline -> ClaimResult.Kept(received.keep(preview.link, accountId, preview.certifiedMsat))
        is ClaimPreview.Locked -> ClaimResult.Kept(received.keep(preview.link, accountId, preview.amountMsat, ReceivedState.Locked))
        is ClaimPreview.KeyChanged -> ClaimResult.Kept(received.keep(preview.link, accountId, preview.amountMsat, ReceivedState.KeyChanged))
    }

    /** Claimed into its own account a sent note is taken back, like NoteSender.reclaim, not received. */
    private suspend fun claimSent(preview: ClaimPreview.Ready, sent: whitewallet.core.model.Note, accountId: String): ClaimResult {
        val host = preview.link.host
        val received = sent.accountId != accountId
        return when (val result = mutator.rotateIn(accountId, host, preview.link.withdrawUrl, preview.info, preview.link.spend, preview.amountMsat, sent)) {
            is MutationResult.Done -> {
                val note = result.outputs.single()
                val amount = note.amountMsat.takeIf { it > 0 } ?: preview.amountMsat
                NoteSender(wallet).claimed(sent, accountId)
                if (received) log.record("claim:${result.journalId}", accountId, ActivityKind.Claimed, amount, host, certified = note.certificate != null || preview.certified)
                ClaimResult.Claimed(amount, note.certificate != null)
            }
            // Either the rotate landed or the note is back where it was once reconciled.
            is MutationResult.Pending -> {
                NoteSender(wallet).claimed(sent, accountId)
                if (received) log.record("claim:${result.journalId}", accountId, ActivityKind.Claimed, preview.amountMsat, host, ActivityStatus.Pending,
                    certified = preview.certified, journalId = result.journalId)
                ClaimResult.Pending
            }
            is MutationResult.Refused -> ClaimResult.Failed(result.kind, result.reason)
            is MutationResult.Unreachable -> ClaimResult.Failed(RejectionKind.Other, result.reason)
        }
    }

    /** A locked note: what it is worth is asked by its key alone, which the mint answers whatever the lock. */
    private suspend fun locked(link: NoteLink, script: Spend.Script): ClaimPreview {
        val amount = try {
            wallet.client.lookup(link.withdrawUrl, script.q).maxWithdrawableMsat
        } catch (e: NoteLookupError) {
            if (e.kind == RejectionKind.Spent || e.kind == RejectionKind.NoteUnknown) return ClaimPreview.AlreadySpent
            null
        } catch (e: ProtocolViolation) {
            null
        } catch (e: IOException) {
            null
        }
        return ClaimPreview.Locked(link, script.lockedUntilSeconds?.let { it * 1000 }, script.lockedForSeconds, amount)
    }

    private fun certified(link: NoteLink, info: NoteInfo): Boolean {
        val certificate = link.certificate?.let(Cs1::decode) ?: info.certificate
        return certificate != null && certificate.amountMsat == info.maxWithdrawableMsat && Certificates.verify(certificate, link.spend.q, info.mintPubkey)
    }

    /** The link's certificate amount, if it verifies against the pinned key of its mint (known from earlier). */
    private suspend fun certifiedOffline(link: NoteLink): Long? {
        val certificate = link.certificate?.let(Cs1::decode) ?: return null
        val key = wallet.store.mint(link.host)?.mintPubkey ?: return null
        val mintKey = runCatching { PublicKey.fromHex(key) }.getOrNull() ?: return null
        return certificate.amountMsat.takeIf { Certificates.verify(certificate, link.spend.q, mintKey) }
    }
}
