package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.OperationType
import whitewallet.lnurl.Bolt11
import whitewallet.lnurl.Bolt11Invoice
import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string
import whitewallet.lnurlcash.CashBranch
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests
import whitewallet.lnurlcash.spendDomain

/** Where a payment goes. */
sealed interface PayDestination {
    data class Invoice(val invoice: Bolt11Invoice) : PayDestination

    /**
     * A LUD-06 pay link (a Lightning Address, `lnurlp://` or bech32 LNURL). [transfer] is set when the payee is on a
     * service this account holds notes on and publishes a `text/cpub`: then the notes are handed over directly
     * (LUD-26 internal transfer), without Lightning.
     */
    data class PayLink(
        val url: String,
        val callback: String,
        val metadata: String,
        val minMsat: Long,
        val maxMsat: Long,
        val commentAllowed: Int,
        val transfer: InternalTransfer?,
    ) : PayDestination
}

/** A payee's purpose-2 branch on the same service, from their `text/cpub` (`cx1:<next index>`). */
class InternalTransfer internal constructor(internal val branch: CashBranch, internal val nextIndex: Long)

/** Why a payment can't be made (or wasn't). Every case maps to a full string in the app. */
enum class PayProblem {
    /** Not an invoice, Lightning Address or pay link. */
    NotRecognized,

    /** An invoice that leaves the amount open (a note pays an exact amount). */
    NoAmount,
    Expired,

    /** The pay link or service couldn't be reached. */
    Unreachable,

    /** The payee's service refused (e.g. an unknown address). */
    PayeeRefused,

    /** The payee's invoice doesn't match what was asked for (amount or description hash). */
    InvoiceMismatch,

    /** The account's own address. */
    OwnAddress,

    /** Nothing to pay with. */
    NoFunds,

    /** More than the notes can pay. */
    TooMuch,

    /** The notes can't make exactly this amount (see [PayQuote.Unreachable]). */
    NotExact,
    BelowMinimum,
    AboveMaximum,

    /** The service refused the payment; nothing was paid. */
    Refused,

    /** The service tried and the payment failed; the money is back. */
    PaymentFailed,
}

sealed interface PayPreview {
    data class Ready(
        val destination: PayDestination,
        /** The invoice's amount; null when the payer chooses it (within [minMsat]..[maxMsat]). */
        val fixedAmountMsat: Long?,
        val minMsat: Long,
        val maxMsat: Long,
        /** 0 when no comment can be sent (an invoice, or an internal transfer, which carries none). */
        val commentMaxLength: Int,
        /** Who is paid: the Lightning Address or pay link identifier; null for a bare invoice. */
        val payee: String?,
        /** What the payee says the payment is for. */
        val description: String?,
        /** The most this account can pay to this destination. */
        val availableMsat: Long,
    ) : PayPreview {
        val internal: Boolean get() = (destination as? PayDestination.PayLink)?.transfer != null
    }

    data class Invalid(val problem: PayProblem) : PayPreview
}

sealed interface PayQuote {
    /** Payable; the balance drops by the amount plus [feeMsat] (0 when merge refunds cover the split fee). */
    data class Ok(val amountMsat: Long, val feeMsat: Long) : PayQuote

    /**
     * The notes can't make exactly this amount: one split needs change worth more than the base fee. Up to
     * [maxWithChangeMsat], or exactly [allMsat] (everything merged), works.
     */
    data class Unreachable(val maxWithChangeMsat: Long, val allMsat: Long) : PayQuote

    data class Invalid(val problem: PayProblem, val limitMsat: Long? = null) : PayQuote
}

sealed interface PayResult {
    data class Paid(val amountMsat: Long, val feeMsat: Long) : PayResult

    /** Sent; the payment's outcome isn't known yet. A later refresh settles it (see [Reconciler]). */
    data class Pending(val amountMsat: Long) : PayResult

    data class Failed(val problem: PayProblem) : PayResult
}

/**
 * Pays an invoice, Lightning Address or pay link from the active account's notes. A note pays exactly one amount,
 * so the notes are first merged and split into one worth exactly that (see [PayPlanner]); then either that note is
 * melted into the invoice (LUD-25), or, for a payee on the same service, the last merge/split names the payee's key
 * as its output (LUD-26 internal transfer). Every step is journaled; a melt is never retried.
 */
internal class Payer(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
    private val log = ActivityLog(wallet)

    suspend fun preview(input: String): PayPreview {
        val text = input.trim().let(::unwrapBip21)
        Bolt11.decode(text)?.let { return previewInvoice(it) }
        val url = Lnurl.resolve(text) ?: return PayPreview.Invalid(PayProblem.NotRecognized)
        val response = try {
            wallet.client.get(url)
        } catch (e: IOException) {
            return PayPreview.Invalid(PayProblem.Unreachable)
        } catch (e: ProtocolViolation) {
            return PayPreview.Invalid(PayProblem.Unreachable)
        }
        val pay = when (response) {
            is LnurlResponse.PayRequest -> response
            is LnurlResponse.Error -> return PayPreview.Invalid(PayProblem.PayeeRefused)
            else -> return PayPreview.Invalid(PayProblem.NotRecognized)
        }
        val accountId = wallet.activeId()
        val payeeHost = Lnurl.host(url)?.lowercase()
        val transfer = pay.metadataEntries.firstOrNull { it.first == "text/cpub" }?.second?.let { cpub ->
            val branch = CashBranch.fromCx1(cpub.substringBeforeLast(':')) ?: return@let null
            InternalTransfer(branch, cpub.substringAfterLast(':', "0").toLongOrNull() ?: 0)
        }?.takeIf { payeeHost != null && outstanding(accountId).any { it.host == payeeHost } }
        if (transfer != null && payeeHost != null && transfer.branch.cx1().equals(wallet.branch(accountId, payeeHost).cx1(), ignoreCase = true)) {
            return PayPreview.Invalid(PayProblem.OwnAddress)
        }
        val destination = PayDestination.PayLink(url, pay.callback, pay.metadata, pay.minSendable, pay.maxSendable, pay.commentAllowed ?: 0, transfer)
        val identifier = pay.metadataEntries.firstOrNull { it.first == "text/identifier" || it.first == "text/email" }?.second
        val available = available(accountId, payeeHost.takeIf { transfer != null })
        if (available == 0L) return PayPreview.Invalid(PayProblem.NoFunds)
        return PayPreview.Ready(
            destination,
            fixedAmountMsat = pay.minSendable.takeIf { it == pay.maxSendable },
            minMsat = pay.minSendable,
            maxMsat = pay.maxSendable,
            commentMaxLength = if (transfer != null) 0 else pay.commentAllowed ?: 0,
            payee = lightningAddress(text) ?: identifier ?: payeeHost,
            description = pay.metadataEntries.firstOrNull { it.first == "text/plain" }?.second,
            availableMsat = available,
        )
    }

    private suspend fun previewInvoice(invoice: Bolt11Invoice): PayPreview {
        val amount = invoice.amountMsat ?: return PayPreview.Invalid(PayProblem.NoAmount)
        if (invoice.isExpired(wallet.now())) return PayPreview.Invalid(PayProblem.Expired)
        val available = available(wallet.activeId(), null)
        if (available == 0L) return PayPreview.Invalid(PayProblem.NoFunds)
        return PayPreview.Ready(PayDestination.Invoice(invoice), amount, amount, amount, 0, null, invoice.description, available)
    }

    suspend fun quote(preview: PayPreview.Ready, amountMsat: Long): PayQuote {
        if (amountMsat <= 0 || amountMsat < preview.minMsat) return PayQuote.Invalid(PayProblem.BelowMinimum, preview.minMsat)
        if (amountMsat > preview.maxMsat) return PayQuote.Invalid(PayProblem.AboveMaximum, preview.maxMsat)
        val accountId = wallet.activeId()
        val choice = choose(accountId, preview, amountMsat)
        if (choice != null) return PayQuote.Ok(amountMsat, choice.second.feeMsat.coerceAtLeast(0))
        // No plan anywhere: too much for every service, or an amount the notes can't make exactly.
        val (host, notes) = candidates(accountId, preview).maxByOrNull { (h, n) -> PayPlanner.max(n, baseFee(h)) } ?: return PayQuote.Invalid(PayProblem.NoFunds)
        val all = PayPlanner.max(notes, baseFee(host))
        return if (amountMsat > all) PayQuote.Invalid(PayProblem.TooMuch, all) else PayQuote.Unreachable(PayPlanner.maxWithChange(notes, baseFee(host)), all)
    }

    suspend fun pay(preview: PayPreview.Ready, amountMsat: Long, comment: String?): PayResult {
        when (val quote = quote(preview, amountMsat)) {
            is PayQuote.Ok -> Unit
            is PayQuote.Unreachable -> return PayResult.Failed(PayProblem.NotExact)
            is PayQuote.Invalid -> return PayResult.Failed(quote.problem)
        }
        val accountId = wallet.activeId()
        val (host, plan) = choose(accountId, preview, amountMsat) ?: return PayResult.Failed(PayProblem.TooMuch)
        val destination = preview.destination
        // Who was paid; an invoice's description isn't a recipient, so a bare invoice has none.
        val counterparty = preview.payee

        if (destination is PayDestination.PayLink && destination.transfer != null) {
            val transfer = destination.transfer
            val target = OutputSpec.External { attempt -> transfer.branch.notePubkey(NotePurpose.LightningAddress, transfer.nextIndex + attempt) }
            return when (val made = exact(accountId, host, plan, amountMsat, target)) {
                is MutationResult.Done -> {
                    log.record("pay:${made.journalId}", accountId, ActivityKind.Sent, amountMsat, host, ActivityStatus.Done,
                        plan.feeMsat.takeIf { it > 0 }, counterparty, journalId = made.journalId)
                    PayResult.Paid(amountMsat, plan.feeMsat.coerceAtLeast(0))
                }
                is MutationResult.Pending -> {
                    log.record("pay:${made.journalId}", accountId, ActivityKind.Sent, amountMsat, host, ActivityStatus.Pending,
                        counterparty = counterparty, journalId = made.journalId)
                    PayResult.Pending(amountMsat)
                }
                is MutationResult.Refused, is MutationResult.Unreachable -> made.toFailure()
            }
        }

        // Melt: get the invoice first, so nothing is split for a payee that refuses.
        val invoice = when (destination) {
            is PayDestination.Invoice -> destination.invoice
            is PayDestination.PayLink -> when (val fetched = fetchInvoice(destination, amountMsat, comment)) {
                is Fetched.Ok -> fetched.invoice
                is Fetched.Problem -> return PayResult.Failed(fetched.problem)
            }
        }
        if (invoice.isExpired(wallet.now())) return PayResult.Failed(PayProblem.Expired)
        val note = when (val made = exact(accountId, host, plan, amountMsat, OutputSpec.Own(accountId, NotePurpose.Wallet))) {
            is MutationResult.Done -> made.outputs.first()
            // Nothing is paid; the merge or split itself is settled by reconciliation on a later refresh.
            is MutationResult.Pending -> return PayResult.Failed(PayProblem.Unreachable)
            is MutationResult.Refused, is MutationResult.Unreachable -> return made.toFailure()
        }
        return melt(accountId, host, note, invoice, plan.feeMsat, counterparty)
    }

    private sealed interface Fetched {
        data class Ok(val invoice: Bolt11Invoice) : Fetched
        data class Problem(val problem: PayProblem) : Fetched
    }

    /** LUD-06 step 2: asks the payee's service for an invoice and checks it is for this amount and this pay link. */
    private suspend fun fetchInvoice(link: PayDestination.PayLink, amountMsat: Long, comment: String?): Fetched {
        val params = listOf("amount" to amountMsat.toString()) +
            listOfNotNull(comment?.trim()?.takeIf { it.isNotEmpty() && link.commentAllowed > 0 }?.take(link.commentAllowed)?.let { "comment" to it })
        val response = try {
            wallet.client.get(Lnurl.withQuery(link.callback, params))
        } catch (e: IOException) {
            return Fetched.Problem(PayProblem.Unreachable)
        } catch (e: ProtocolViolation) {
            return Fetched.Problem(PayProblem.Unreachable)
        }
        if (response is LnurlResponse.Error) return Fetched.Problem(PayProblem.PayeeRefused)
        val invoice = response.raw.string("pr")?.let(Bolt11::decode) ?: return Fetched.Problem(PayProblem.InvoiceMismatch)
        // The invoice must be for what was asked, and commit to this pay link's metadata (LUD-06).
        if (invoice.amountMsat != amountMsat || invoice.descriptionHashHex != sha256Hex(link.metadata)) return Fetched.Problem(PayProblem.InvoiceMismatch)
        return Fetched.Ok(invoice)
    }

    /**
     * Turns [plan]'s inputs into one note of exactly [amountMsat] at [target]: used as is, merged, split, or merged
     * then split. Returns the last mutation (whose first output is that note), or the input itself when no mutation
     * is needed.
     */
    internal suspend fun exact(accountId: String, host: String, plan: PayPlan, amountMsat: Long, target: OutputSpec): MutationResult {
        var inputs = plan.inputs
        if (!plan.merge && !plan.split) {
            if (target is OutputSpec.Own) return MutationResult.Done(inputs, journalId = "")
            return mutator.mutate(accountId, host, inputs, OperationType.Rotate, listOf(target)) { callback, k1s, outs ->
                Requests.rotate(callback, k1s.single(), outs.single().key)
            }
        }
        if (plan.merge) {
            val mergeTarget = if (plan.split) OutputSpec.Own(accountId, NotePurpose.Wallet) else target
            val merged = mutator.mutate(accountId, host, inputs, OperationType.Merge, listOf(mergeTarget)) { callback, k1s, outs ->
                Requests.merge(callback, k1s, outs.single().key)
            }
            if (!plan.split || merged !is MutationResult.Done) return merged
            inputs = merged.outputs
        }
        return mutator.mutate(accountId, host, inputs, OperationType.Split, listOf(target, OutputSpec.Own(accountId, NotePurpose.Change))) { callback, k1s, outs ->
            Requests.split(callback, k1s, amountMsat, outs[0].key, outs[1].key)
        }
    }

    /** Melts [note] into [invoice] and records the payment in History (see [Melter]). */
    private suspend fun melt(accountId: String, host: String, note: Note, invoice: Bolt11Invoice, feeMsat: Long, counterparty: String?): PayResult {
        val amount = note.amountMsat.takeIf { it > 0 } ?: invoice.amountMsat!!
        val outcome = Melter(wallet).melt(accountId, host, note, invoice.raw) { journalId ->
            log.record("pay:$journalId", accountId, ActivityKind.Sent, amount, host, ActivityStatus.Pending, feeMsat.takeIf { it > 0 }, counterparty, journalId = journalId)
        }
        return when (outcome) {
            is MeltOutcome.Paid -> PayResult.Paid(amount, feeMsat.coerceAtLeast(0))
            is MeltOutcome.Pending -> PayResult.Pending(amount)
            is MeltOutcome.PaymentFailed -> PayResult.Failed(PayProblem.PaymentFailed)
            is MeltOutcome.Refused -> PayResult.Failed(PayProblem.Refused)
            MeltOutcome.Unreachable -> PayResult.Failed(PayProblem.Unreachable)
        }
    }

    /** The service and plan to pay [amountMsat] with: the primary address's service first, then the others. */
    private suspend fun choose(accountId: String, preview: PayPreview.Ready, amountMsat: Long): Pair<String, PayPlan>? =
        candidates(accountId, preview).firstNotNullOfOrNull { (host, notes) -> PayPlanner.plan(notes, amountMsat, baseFee(host))?.let { host to it } }

    private suspend fun candidates(accountId: String, preview: PayPreview.Ready): List<Pair<String, List<Note>>> {
        val transferHost = (preview.destination as? PayDestination.PayLink)?.takeIf { it.transfer != null }?.url?.let(Lnurl::host)?.lowercase()
        val primary = wallet.store.account(accountId)?.host
        return outstanding(accountId).groupBy { it.host }
            .filterKeys { transferHost == null || it == transferHost }
            .entries.sortedBy { if (it.key == primary) 0 else 1 }
            .map { it.key to it.value }
    }

    private suspend fun available(accountId: String, onlyHost: String?): Long =
        outstanding(accountId).groupBy { it.host }.filterKeys { onlyHost == null || it == onlyHost }
            .maxOfOrNull { (host, notes) -> PayPlanner.max(notes, baseFee(host)) } ?: 0

    private suspend fun baseFee(host: String): Long = wallet.store.service(host)?.feeBaseMsat ?: 0

    private suspend fun outstanding(accountId: String): List<Note> =
        wallet.store.notes(accountId).first().filter { it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.index != null }

    private fun MutationResult.toFailure(): PayResult.Failed = when (this) {
        is MutationResult.Refused -> PayResult.Failed(if (kind == RejectionKind.InsufficientValue) PayProblem.TooMuch else PayProblem.Refused)
        else -> PayResult.Failed(PayProblem.Unreachable)
    }

    companion object {

        /** `bitcoin:<address>?lightning=<invoice>` (BIP-21 with a Lightning fallback) to the invoice. */
        fun unwrapBip21(text: String): String {
            if (!text.startsWith("bitcoin:", ignoreCase = true)) return text
            return Lnurl.queryParams("bitcoin://x" + text.substringAfter(':').let { if ('?' in it) "?" + it.substringAfter('?') else "" })
                .firstOrNull { it.first.equals("lightning", ignoreCase = true) }?.second ?: text
        }

        private fun lightningAddress(text: String): String? =
            text.removePrefix("lightning:").removePrefix("LIGHTNING:").trim().takeIf { Lnurl.lightningAddressUrl(it) != null }?.lowercase()

        fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
