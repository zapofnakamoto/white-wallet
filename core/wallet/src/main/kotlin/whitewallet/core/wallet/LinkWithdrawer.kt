package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.flow.first
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.ReceivedState
import whitewallet.core.model.UnclaimedReason
import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.protocol.MintingPayRequest
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.NoteLink
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests

/**
 * A withdraw link the wallet takes in over Lightning (LUD-03; LUD-25 calls it a melt): the link's service pays an invoice from a mint of
 * the account, which mints a note to a key of the account. For links that aren't a note this wallet can keep at their own mint: a withdraw
 * link of another kind of service (LNbits, an ATM), or a note at a mint that can't keep it here.
 */
data class WithdrawLink(
    /** The service's host, as in the link. */
    val host: String,
    /** The link's URL as it is requested (`https://`, resolved from `lnurlw://` or bech32), `k1` included if it has one. */
    val url: String,
    /** Where the withdraw is sent, on the service's own origin; null until the service was asked. */
    val callback: String?,
    /** What the withdraw carries: the service's own token, or else a note's spend. */
    @property:RevealsNoteSecret val k1: String,
    val minMsat: Long,
    val maxMsat: Long,
    /** The link is a note link ([NoteLink.parse]): it is kept as a received note while it is received this way. */
    val note: NoteLink?,
) {
    /** Debug text without the `k1`. */
    override fun toString(): String =
        "WithdrawLink(host=$host, callback=$callback, minMsat=$minMsat, maxMsat=$maxMsat, note=${note != null})"
}

/** Why a link can't be received over Lightning as it stands. */
enum class MeltProblem {
    /** The account has no mint to receive into. */
    NoMint,

    /** The mint to receive into doesn't take what the link pays ([ClaimPreview.Melt.acceptMinMsat] to `acceptMaxMsat`). */
    OutOfRange,

    /** The mint to receive into couldn't be asked for an invoice. */
    MintUnreachable,
}

/** Receives withdraw links over Lightning into a mint of the account; see [WithdrawLink]. */
internal class LinkWithdrawer(private val wallet: Wallet) {
    /**
     * What [text] is, when it isn't a note link: a withdraw link to receive over Lightning, something to pay, or neither. Its service is
     * asked with the link as given, unless its `k1` is shaped like a note's spend ([NoteLink.parse] didn't take it: a broken one).
     */
    suspend fun open(text: String, accountId: String): ClaimPreview {
        val bare = text.trim().replaceFirst(Regex("^lightning:", RegexOption.IGNORE_CASE), "")
        val url = Lnurl.resolve(bare) ?: return ClaimPreview.Invalid("not a note")
        val host = Lnurl.host(url) ?: return ClaimPreview.Invalid("not a note")
        // Shaped like a note's spend, but none this wallet can read: never sent anywhere.
        if (Lnurl.queryParams(url).any { it.first.equals("k1", ignoreCase = true) && looksLikeSpend(it.second) })
            return ClaimPreview.Invalid("not a note")
        val response =
            try {
                @OptIn(RevealsNoteSecret::class) wallet.client.withdrawLink(url)
            } catch (e: IOException) {
                return ClaimPreview.OfflineLink
            } catch (e: ProtocolViolation) {
                return ClaimPreview.Invalid(e.message ?: "invalid response")
            }
        return when (response) {
            is LnurlResponse.WithdrawRequest -> {
                val k1 = response.k1 ?: return ClaimPreview.Invalid("the link has no k1")
                withTarget(
                    WithdrawLink(host, url, response.callback, k1, response.minWithdrawable, response.maxWithdrawable, null),
                    null,
                    accountId,
                    null,
                )
            }
            is LnurlResponse.PayRequest -> ClaimPreview.PayLink(bare)
            is LnurlResponse.Error -> ClaimPreview.Invalid(response.reason ?: "refused")
            else -> ClaimPreview.Invalid("not a withdraw link")
        }
    }

    /**
     * A note link that can't be kept at its mint, for [reason]. What its host answered by the note's key ([found]) says where to send it
     * and what it pays: its mint's answer, the note's whole value for its spend; another service's, what that service pays for its own
     * `k1`. Without either ([KeyLookup.NotANote]), what it pays stays unknown until the user agrees to show the link to its service
     * ([ask]).
     */
    suspend fun forNote(link: NoteLink, reason: UnclaimedReason, found: KeyLookup, accountId: String): ClaimPreview {
        @OptIn(RevealsNoteSecret::class) val url = checkNotNull(Lnurl.resolve(link.encode())) { "a note link always resolves" }
        @OptIn(RevealsNoteSecret::class) val unasked = WithdrawLink(link.host, url, callback = null, link.spend.k1, 0, 0, note = link)
        val withdraw =
            when (found) {
                is KeyLookup.Note -> {
                    val value = found.info.maxWithdrawableMsat
                    unasked.copy(callback = found.info.callback, minMsat = value, maxMsat = value)
                }
                is KeyLookup.OtherService ->
                    @OptIn(RevealsNoteSecret::class)
                    unasked.copy(callback = found.callback, k1 = found.k1, minMsat = found.minMsat, maxMsat = found.maxMsat)
                else -> return melt(unasked, reason, accountId, target = wallet.homeOf(accountId), amountMsat = null)
            }
        return withTarget(withdraw, reason, accountId, null)
    }

    /** The user agreed to show the link to its own service: asks it what the link pays, and where to send it. */
    suspend fun ask(preview: ClaimPreview.Melt, accountId: String): ClaimPreview {
        val response =
            try {
                @OptIn(RevealsNoteSecret::class) wallet.client.withdrawLink(preview.link.url)
            } catch (e: IOException) {
                return ClaimPreview.OfflineLink
            } catch (e: ProtocolViolation) {
                return ClaimPreview.Invalid(e.message ?: "invalid response")
            }
        return when (response) {
            is LnurlResponse.WithdrawRequest -> {
                val link =
                    preview.link.copy(
                        callback = response.callback,
                        k1 = @OptIn(RevealsNoteSecret::class) (response.k1 ?: preview.link.k1),
                        minMsat = response.minWithdrawable,
                        maxMsat = response.maxWithdrawable,
                    )
                withTarget(link, preview.reason, accountId, preview.target)
            }
            is LnurlResponse.Error -> ClaimPreview.Invalid(response.reason ?: "refused")
            else -> ClaimPreview.Invalid("not a withdraw link")
        }
    }

    /**
     * [preview] again, received into [host] (one of the account's mints) instead. A link whose service wasn't asked yet ([ask]) stays so:
     * what it pays is only known then. Any other is checked against [host] now.
     */
    suspend fun into(preview: ClaimPreview.Melt, host: String, accountId: String): ClaimPreview =
        if (preview.link.callback == null) preview.copy(target = host, problem = null)
        else withTarget(preview.link, preview.reason, accountId, host)

    /** Into [chosen], the account's home mint, or a mint the user picks; the amount the target takes within what the link pays. */
    private suspend fun withTarget(link: WithdrawLink, reason: UnclaimedReason?, accountId: String, chosen: String?): ClaimPreview {
        val target = chosen ?: wallet.homeOf(accountId)
        return melt(link, reason, accountId, target, amountMsat = link.maxMsat)
    }

    private suspend fun melt(
        link: WithdrawLink,
        reason: UnclaimedReason?,
        accountId: String,
        target: String?,
        amountMsat: Long?,
    ): ClaimPreview.Melt {
        val mints = wallet.store.mints(wallet.walletOf(accountId)).first().map { it.host }
        val base = ClaimPreview.Melt(link, reason, target, mints, amountMsat = null, expectedMsat = null)
        if (target == null) return if (mints.isEmpty()) base.copy(problem = MeltProblem.NoMint) else base
        if (amountMsat == null) return base
        val pay =
            try {
                wallet.client.servicePayRequest(target)
            } catch (e: IOException) {
                return base.copy(problem = MeltProblem.MintUnreachable)
            } catch (e: ProtocolViolation) {
                return base.copy(problem = MeltProblem.MintUnreachable)
            }
        val amount =
            amountOf(link, pay)
                ?: return base.copy(
                    problem = MeltProblem.OutOfRange,
                    acceptMinMsat = pay.pay.minSendable,
                    acceptMaxMsat = pay.pay.maxSendable,
                    amountMsat = link.maxMsat,
                )
        return base.copy(amountMsat = amount, expectedMsat = pay.fee?.noteValue(amount) ?: amount)
    }

    /**
     * Receives [preview] into its target: an invoice from the target's minting payRequest, to a fresh key of the account and journaled like
     * any invoice of the wallet, is sent with the link's `k1` to the link's callback. A note link is kept meanwhile; if the invoice isn't
     * paid, the note goes back to waiting for the user ([ReceivedState.LightningOnly]).
     */
    suspend fun withdraw(preview: ClaimPreview.Melt, accountId: String): ClaimResult {
        val link = preview.link
        val target = preview.target ?: return ClaimResult.Failed(RejectionKind.Other, "no mint to receive into")
        val callback = link.callback ?: return ClaimResult.Failed(RejectionKind.Other, "not asked yet")
        val pay =
            try {
                wallet.client.servicePayRequest(target)
            } catch (e: IOException) {
                return ClaimResult.Failed(RejectionKind.Other, e.message)
            } catch (e: ProtocolViolation) {
                return ClaimResult.Failed(RejectionKind.Other, e.message)
            }
        val amount = amountOf(link, pay) ?: return ClaimResult.Failed(RejectionKind.Other, "out of range")
        // An invoice of the wallet like any other: paid, its note is picked up and recorded in History; expired, it is dropped.
        val invoices = InvoiceReceiver(wallet)
        val invoice =
            when (val requested = invoices.request(accountId, target, pay, amount)) {
                is InvoiceRequest.Ready -> requested
                is InvoiceRequest.OutOfRange -> return ClaimResult.Failed(RejectionKind.Other, "out of range")
                InvoiceRequest.Unreachable -> return ClaimResult.Failed(RejectionKind.Other, "unreachable")
                InvoiceRequest.Refused -> return ClaimResult.Failed(RejectionKind.Other, "refused")
            }
        val received = ReceivedNotes(wallet)
        // A note link's preview always says why it is melted.
        val kept = link.note?.let { received.melting(it, accountId, amount, preview.reason ?: UnclaimedReason.NotANote, invoice.id) }
        @OptIn(RevealsNoteSecret::class) val request = Requests.melt(callback, link.k1, invoice.invoice)
        val outcome =
            when (val released = SecretGate(wallet).releaseLink(accountId, link, callback, request)) {
                is SecretGate.Released.Sent -> released.outcome
                is SecretGate.Released.NotSent -> {
                    kept?.let { received.meltFailed(it) }
                    return ClaimResult.Failed(RejectionKind.Other, released.reason)
                }
            }
        if (outcome is MutationOutcome.Rejected) {
            kept?.let { received.meltFailed(it) }
            return ClaimResult.Failed(outcome.kind, outcome.reason)
        }
        // Sent: the service pays in the background. The invoice says when it did; until then History names the service.
        ActivityLog(wallet)
            .record(
                MintReconciler.activityId(invoice.id),
                accountId,
                ActivityKind.Received,
                invoice.expectedMsat,
                target,
                ActivityStatus.Pending,
                counterparty = link.host,
                journalId = invoice.id,
            )
        val status = waitForPayment(wallet) { invoices.status(invoice.id).takeIf { it != InvoiceStatus.Waiting } }
        return when (status) {
            is InvoiceStatus.Paid -> {
                val arrived = status.receivedMsat.takeIf { it > 0 } ?: invoice.expectedMsat
                kept?.let { received.melted(it, arrived) }
                ClaimResult.Claimed(arrived, certified = false)
            }
            InvoiceStatus.Expired -> {
                kept?.let { received.meltFailed(it) }
                ClaimResult.Failed(RejectionKind.Other, null)
            }
            InvoiceStatus.Waiting,
            null -> ClaimResult.Pending
        }
    }

    private companion object {
        /** A note's spend has one of these forms (LUD-25): hex of a bearer note's preimage, a `ck1` or a `cw1`. */
        fun looksLikeSpend(k1: String): Boolean =
            k1.length == 64 && k1.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } ||
                k1.startsWith("ck1", ignoreCase = true) ||
                k1.startsWith("cw1", ignoreCase = true)

        /**
         * What to have [link] pay into a mint whose payRequest is [pay]: as much as both allow, in whole sats if that stays within both. A
         * note pays its whole value or nothing (LUD-25: a melt for any other amount is refused). Null if no amount fits.
         */
        fun amountOf(link: WithdrawLink, pay: MintingPayRequest): Long? {
            val low = maxOf(link.minMsat, pay.pay.minSendable, 1)
            val high = minOf(link.maxMsat, pay.pay.maxSendable)
            if (high < low) return null
            val whole = high - high % 1_000
            val amount = if (whole >= low) whole else high
            return amount.takeIf { (pay.fee?.noteValue(it) ?: it) > 0 }
        }
    }
}
