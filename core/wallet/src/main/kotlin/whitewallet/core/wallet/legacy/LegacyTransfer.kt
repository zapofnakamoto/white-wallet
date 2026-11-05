/*
 * Legacy: LUD-25/26 draft 2f17578 (tag: mint). Removable once every mint the wallet uses runs LUD-26 at 92e430f or later; see
 * protocol/SPEC.md, "Removing legacy 2f17578".
 */
package whitewallet.core.wallet.legacy

import whitewallet.core.model.ActivityKind
import whitewallet.core.model.ActivityStatus
import whitewallet.core.wallet.InternalTransfer
import whitewallet.core.wallet.MutationResult
import whitewallet.core.wallet.OutputSpec
import whitewallet.core.wallet.PayPlan
import whitewallet.core.wallet.PayResult
import whitewallet.core.wallet.Payer
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.legacy.LegacyBranch
import whitewallet.lnurlcash.legacy.LegacyPurpose
import whitewallet.lnurlcash.protocol.legacy.LegacyCpub

/** A payee's purpose-2 branch, from their `text/cpub` (`cx1:<next index>`), the note's output named by the payer. */
class LegacyTransfer internal constructor(internal val branch: LegacyBranch, internal val nextIndex: Long) : InternalTransfer {
    companion object {
        internal fun from(pay: LnurlResponse.PayRequest): LegacyTransfer? =
            LegacyCpub.from(pay)?.let { (cx1, next) -> LegacyBranch.fromCx1(cx1)?.let { LegacyTransfer(it, next) } }
    }
}

/** Makes a note of [amountMsat] at the payee's key; null when a note was set aside before anything was paid. */
internal suspend fun Payer.payAtKey(
    transfer: LegacyTransfer,
    accountId: String,
    host: String,
    plan: PayPlan,
    amountMsat: Long,
    counterparty: String?,
): PayResult? {
    val target = OutputSpec.External { attempt -> transfer.branch.notePubkey(LegacyPurpose.LightningAddress, transfer.nextIndex + attempt) }
    return when (val made = exact(accountId, host, plan, amountMsat, target)) {
        is MutationResult.Done -> {
            log.record(
                id = "pay:${made.journalId}",
                accountId,
                kind = ActivityKind.Sent,
                amountMsat,
                host,
                status = ActivityStatus.Done,
                feeMsat = plan.feeMsat.takeIf { it > 0 },
                counterparty,
                journalId = made.journalId,
            )
            PayResult.Paid(amountMsat, plan.feeMsat.coerceAtLeast(0))
        }
        is MutationResult.Pending -> {
            log.record(
                id = "pay:${made.journalId}",
                accountId,
                kind = ActivityKind.Sent,
                amountMsat,
                host,
                status = ActivityStatus.Pending,
                counterparty = counterparty,
                journalId = made.journalId,
            )
            PayResult.Pending(amountMsat)
        }
        is MutationResult.Refused -> if (made.setAside.isNotEmpty()) null else with(this) { made.toFailure() }
        is MutationResult.Unreachable -> with(this) { made.toFailure() }
    }
}
