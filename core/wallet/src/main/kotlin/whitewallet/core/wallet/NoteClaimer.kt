package whitewallet.core.wallet

import fr.acinq.bitcoin.PublicKey
import java.io.IOException
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.isShared
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
     * Its mint can't be reached. [declaredMsat] is what the link says; [certifiedMsat] what its certificate says, if it verified against
     * the pinned key of a mint the wallet knows.
     */
    data class Offline(override val link: NoteLink, val declaredMsat: Long?, val certifiedMsat: Long?) : Keepable

    /**
     * Its spend claims a time its mint hasn't reached: [untilMillis] (a date), or [forSeconds] after its mint last credited the note, which
     * only the mint knows. [amountMsat] is what the mint says, if it could be asked.
     */
    data class Locked(override val link: NoteLink, val untilMillis: Long?, val forSeconds: Long?, val amountMsat: Long?) : Keepable

    /**
     * Its mint ([host]) presents another signing key than the one pinned for it, so the spend isn't sent there. Kept, it is claimed once
     * the user accepts the new key (the mint's details). [amountMsat] is what the mint says now.
     */
    data class KeyChanged(override val link: NoteLink, val host: String, val amountMsat: Long) : Keepable

    /** A script note whose spend doesn't satisfy its script, or claims a time no mint honours (a block height). */
    data object NotSpendable : ClaimPreview

    /**
     * One of the active account's own notes, handed out as a link and not claimed yet: taken back ([Wallet.reclaimNote] with [id]), never
     * claimed. [reachable] is false while its mint can't be asked.
     */
    data class OwnShared(val id: String, val amountMsat: Long, val reachable: Boolean) : ClaimPreview

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
 * Claims a note handed over by someone else: verify it, keep it (see [ReceivedNotes]), then rotate it straight into this wallet's own
 * branch.
 */
internal class NoteClaimer(private val wallet: Wallet) {
    private val received = ReceivedNotes(wallet)

    suspend fun preview(input: String, accountId: String): ClaimPreview {
        val link = NoteLink.parse(input) ?: return ClaimPreview.Invalid("not a note")
        val spend = link.spend
        val domain = spendDomain(link.host)
        if (spend is Spend.KeyPath && !spend.verify(domain)) return ClaimPreview.Invalid("this note is not valid for ${link.host}")
        val script = spend as? Spend.Script
        if (script != null && (!script.isValidLockClaim || !script.verify(domain))) return ClaimPreview.NotSpendable
        val mine = wallet.store.note(spend.q.toHex())
        if (mine?.state == NoteState.Outstanding) return ClaimPreview.AlreadyInWallet
        // The account's own note handed out as a link: it is taken back, not claimed. In another account it is
        // received like any other note, and its own account finds it spent on the next refresh.
        val shared = mine?.takeIf { it.accountId == accountId && it.state.isShared }
        if (wallet.store.receivedNote(spend.q.toHex())?.state == ReceivedState.Claimed) return ClaimPreview.AlreadyInWallet
        // A lock by date still ahead: no use asking with the spend, the mint refuses it until then.
        if (script?.lockedUntilSeconds?.let { it * 1000 > wallet.now() } == true) return locked(link, script)
        // Asked by the note's key alone: the spend goes out only with the claim, to a mint with the pinned key. A wait
        // after crediting is the mint's to judge then; its refusal keeps the note as locked.
        val info =
            try {
                wallet.client.lookup(link.withdrawUrl, spend.q)
            } catch (e: NoteLookupError) {
                return when (e.kind) {
                    RejectionKind.Spent,
                    RejectionKind.NoteUnknown -> ClaimPreview.AlreadySpent
                    else -> ClaimPreview.Invalid(e.reason ?: "refused")
                }
            } catch (e: ProtocolViolation) {
                return ClaimPreview.Invalid(e.message ?: "invalid response")
            } catch (e: IOException) {
                if (shared != null) return ClaimPreview.OwnShared(shared.id, shared.amountMsat, reachable = false)
                return ClaimPreview.Offline(link, link.declaredAmountMsat, certifiedOffline(link, accountId))
            }
        if (info.maxWithdrawableMsat <= 0) return ClaimPreview.AlreadySpent
        if (shared != null) return ClaimPreview.OwnShared(shared.id, shared.amountMsat, reachable = true)
        val own = wallet.store.account(accountId)?.host
        val mint = wallet.mintOf(accountId, link.host)
        if (mint?.mintPubkey != null && mint.mintPubkey != info.mintPubkey.toHex())
            return ClaimPreview.KeyChanged(link, link.host, info.maxWithdrawableMsat)
        return ClaimPreview.Ready(
            link,
            info,
            info.maxWithdrawableMsat,
            certified(link, info),
            isOwnMint = link.host == own,
            trusted = wallet.isTrusted(wallet.walletOf(accountId), MintKey(link.host, info.mintPubkey.toHex())),
            hasOtherPaths = script?.hasOtherPaths == true,
        )
    }

    suspend fun claim(preview: ClaimPreview.Ready, accountId: String): ClaimResult {
        val note = received.keep(preview.link, accountId, preview.amountMsat)
        return received.claim(note, preview.info, preview.certified)
    }

    /** Keeps a note that can't be claimed now; a refresh claims it once it can be. */
    suspend fun keep(preview: ClaimPreview.Keepable, accountId: String): ClaimResult.Kept =
        when (preview) {
            is ClaimPreview.Offline -> ClaimResult.Kept(received.keep(preview.link, accountId, preview.certifiedMsat))
            is ClaimPreview.Locked -> ClaimResult.Kept(received.keep(preview.link, accountId, preview.amountMsat, ReceivedState.Locked))
            is ClaimPreview.KeyChanged ->
                ClaimResult.Kept(received.keep(preview.link, accountId, preview.amountMsat, ReceivedState.KeyChanged))
        }

    /** A locked note: what it is worth is asked by its key alone, which the mint answers whatever the lock. */
    private suspend fun locked(link: NoteLink, script: Spend.Script): ClaimPreview {
        val amount =
            try {
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

    /** Whether the mint vouches for the note at what it is worth: its own certificate, or the link's, verifies. */
    private fun certified(link: NoteLink, info: NoteInfo): Boolean =
        listOfNotNull(info.certificate, link.certificate?.let(Cs1::decode)).any {
            it.amountMsat == info.maxWithdrawableMsat && certifies(it, link.host, link.spend.q, info.mintPubkey)
        }

    /** The link's certificate amount, if it verifies against the pinned key of its mint (known from earlier). */
    private suspend fun certifiedOffline(link: NoteLink, accountId: String): Long? {
        val certificate = link.certificate?.let(Cs1::decode) ?: return null
        val key = wallet.mintOf(accountId, link.host)?.mintPubkey ?: return null
        val mintKey = runCatching { PublicKey.fromHex(key) }.getOrNull() ?: return null
        return certificate.amountMsat.takeIf { certifies(certificate, link.host, link.spend.q, mintKey) }
    }
}
