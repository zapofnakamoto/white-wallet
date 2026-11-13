package whitewallet.core.wallet

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import whitewallet.core.model.ArchiveReason
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.OperationType
import whitewallet.core.model.ReceivedState
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
    /** The link as given (to open it again). */
    val text: String,
    /** The service's host, as in the link. */
    val host: String,
    /** The link's URL, `k1` included if it has one. */
    val url: String,
    /** Where the withdraw is sent, on the service's own origin; null until the service was asked. */
    val callback: String?,
    /** What the withdraw carries: the service's own token, or a note's spend. Null until known. */
    @property:RevealsNoteSecret val k1: String?,
    val minMsat: Long,
    val maxMsat: Long,
    /** The link is a note link ([NoteLink.parse]): it is kept as a received note while it is received this way. */
    val note: NoteLink?,
) {
    /** Debug text without the `k1`. */
    override fun toString(): String =
        "WithdrawLink(host=$host, callback=$callback, minMsat=$minMsat, maxMsat=$maxMsat, note=${note != null})"
}

/** Why a link is received over Lightning rather than kept at its own mint. */
enum class MeltReason {
    /** No note at all: a withdraw link of another kind of service. One confirm. */
    Plain,

    /** A note at a mint that publishes no signing key, which the wallet doesn't allow. */
    NoSigningKey,

    /** Looks like a note, but its service doesn't answer for it as one. */
    NotANote,

    /** Its mint publishes no signing key and refused to keep it for this wallet. */
    Refused;

    internal val archiveReason: ArchiveReason?
        get() =
            when (this) {
                Plain -> null
                NoSigningKey -> ArchiveReason.NoSigningKey
                NotANote -> ArchiveReason.NotANote
                Refused -> ArchiveReason.Refused
            }

    internal companion object {
        fun of(reason: ArchiveReason?): MeltReason =
            when (reason) {
                ArchiveReason.NoSigningKey -> NoSigningKey
                ArchiveReason.Refused -> Refused
                else -> NotANote
            }
    }
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
    private val guard = IndexGuard(wallet)

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
                    WithdrawLink(text, host, url, response.callback, k1, response.minWithdrawable, response.maxWithdrawable, null),
                    MeltReason.Plain,
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
     * A note link that can't be kept at its mint, for [reason]. [answer], the mint's answer for it (by its key), says where to send it and
     * what it is worth: all of it, unless the answer carries the service's own `k1` (no mint's answer for a note does). Without one
     * ([MeltReason.NotANote] from an answer that isn't a withdraw at all), what it pays stays unknown until the user agrees to show the
     * link to its service ([ask]).
     */
    suspend fun forNote(link: NoteLink, reason: MeltReason, answer: LnurlResponse.WithdrawRequest?, accountId: String): ClaimPreview {
        @OptIn(RevealsNoteSecret::class) val url = link.encode()
        val own = answer?.k1
        val withdraw =
            WithdrawLink(
                text = url,
                host = link.host,
                url = url,
                callback = answer?.callback,
                k1 = own ?: @OptIn(RevealsNoteSecret::class) link.spend.k1,
                minMsat = (if (own != null) answer.minWithdrawable else answer?.maxWithdrawable) ?: 0,
                maxMsat = answer?.maxWithdrawable ?: 0,
                note = link,
            )
        if (answer == null) return melt(withdraw, reason, accountId, target = wallet.homeOf(accountId), amountMsat = null)
        return withTarget(withdraw, reason, accountId, null)
    }

    /** The user agreed to show the link to its own service: asks it what the link pays, and where to send it. */
    suspend fun ask(preview: ClaimPreview.Melt, accountId: String): ClaimPreview {
        val response =
            try {
                @OptIn(RevealsNoteSecret::class) wallet.client.withdrawLink(Lnurl.resolve(preview.link.url) ?: preview.link.url)
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

    /** [preview] again, received into [host] (one of the account's mints) instead. */
    suspend fun into(preview: ClaimPreview.Melt, host: String, accountId: String): ClaimPreview =
        if (preview.amountMsat == null) preview.copy(target = host) else withTarget(preview.link, preview.reason, accountId, host)

    /** Into [chosen], the account's home mint, or a mint the user picks; the amount the target takes within what the link pays. */
    private suspend fun withTarget(link: WithdrawLink, reason: MeltReason, accountId: String, chosen: String?): ClaimPreview {
        val target = chosen ?: wallet.homeOf(accountId)
        return melt(link, reason, accountId, target, amountMsat = link.maxMsat)
    }

    private suspend fun melt(
        link: WithdrawLink,
        reason: MeltReason,
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
        @OptIn(RevealsNoteSecret::class) val k1 = link.k1 ?: return ClaimResult.Failed(RejectionKind.Other, "not asked yet")
        val mint = wallet.mintOf(accountId, target) ?: return ClaimResult.Failed(RejectionKind.Other, "unknown mint")
        val pay =
            try {
                wallet.client.servicePayRequest(target)
            } catch (e: IOException) {
                return ClaimResult.Failed(RejectionKind.Other, e.message)
            } catch (e: ProtocolViolation) {
                return ClaimResult.Failed(RejectionKind.Other, e.message)
            }
        val amount = amountOf(link, pay) ?: return ClaimResult.Failed(RejectionKind.Other, "out of range")
        val expected = pay.fee?.noteValue(amount) ?: amount
        var reserved: ReservedOutput? = null
        var invoice: String? = null
        for (attempt in 0 until wallet.settings.newNoteMaxKeysTriedWhileInUse) {
            val key = guard.reserve(accountId, target, KeySet.Wallet)
            when (val fetched = MintInvoice.fetch(wallet, pay, amount, key.key)) {
                is MintInvoice.Ok -> {
                    reserved = key
                    invoice = fetched.invoice
                    break
                }
                MintInvoice.InUse -> if (attempt == 0) guard.recover(accountId, target, KeySet.Wallet)
                MintInvoice.Unreachable -> return ClaimResult.Failed(RejectionKind.Other, "unreachable")
                MintInvoice.Refused,
                MintInvoice.Mismatch -> return ClaimResult.Failed(RejectionKind.Other, "refused")
            }
        }
        if (reserved == null || invoice == null) return ClaimResult.Failed(RejectionKind.Other, "refused")
        val id = UUID.randomUUID().toString()
        val now = wallet.now()
        // An invoice of the wallet like any other: paid, its note is picked up and recorded in History; expired, it is dropped.
        wallet.store.putJournal(
            JournalEntry(
                id,
                accountId,
                type = OperationType.Mint,
                target,
                mint.withdrawUrl,
                inputIds = emptyList(),
                inputK1s = emptyList(),
                outputs = listOf(reserved.ref),
                requestUrl = invoice,
                status = JournalStatus.Prepared,
                detail = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        )
        val received = ReceivedNotes(wallet)
        val kept = link.note?.let { received.melting(it, accountId, amount, preview.reason, id) }
        @OptIn(RevealsNoteSecret::class) val request = Requests.melt(callback, k1, invoice)
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
        // Sent: the service pays in the background. The invoice says when it did.
        val mints = MintReconciler(wallet)
        var wait = wallet.settings.paymentStatusFirstWaitMillis
        val deadline = wallet.now() + wallet.payWaitMillis
        while (true) {
            val entry = mints.entry(id) ?: return ClaimResult.Pending
            when (mints.reconcile(entry)) {
                JournalStatus.Confirmed -> {
                    val arrived = wallet.store.note(reserved.ref.q)?.amountMsat ?: expected
                    kept?.let { received.melted(it, arrived) }
                    return ClaimResult.Claimed(arrived, certified = false)
                }
                JournalStatus.Rejected -> {
                    kept?.let { received.meltFailed(it) }
                    return ClaimResult.Failed(RejectionKind.Other, null)
                }
                else -> if (wallet.now() >= deadline) return ClaimResult.Pending
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(wallet.settings.paymentStatusLongestWaitMillis)
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
