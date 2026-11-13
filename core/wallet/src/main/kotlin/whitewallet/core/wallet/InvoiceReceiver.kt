package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import java.util.UUID
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.Mint
import whitewallet.core.model.OperationType
import whitewallet.lnurl.Bolt11
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string
import whitewallet.lnurlcash.protocol.MintingPayRequest
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests

/** The outcome of [Wallet.requestInvoice]. */
sealed interface InvoiceRequest {
    /** Paying [invoice] mints a note worth [expectedMsat] (the amount less the mint's fee) to this account. */
    data class Ready(val id: String, val invoice: String, val amountMsat: Long, val expectedMsat: Long, val expiresAtMillis: Long) :
        InvoiceRequest

    /** The mint takes payments between [minMsat] and [maxMsat] (and more than its fee). */
    data class OutOfRange(val minMsat: Long, val maxMsat: Long) : InvoiceRequest

    data object Unreachable : InvoiceRequest

    data object Refused : InvoiceRequest
}

/** Where an invoice from [Wallet.requestInvoice] stands. */
sealed interface InvoiceStatus {
    data object Waiting : InvoiceStatus

    data class Paid(val receivedMsat: Long) : InvoiceStatus

    /** It expired unpaid (or the request is unknown). */
    data object Expired : InvoiceStatus
}

/** An invoice from a mint's own minting payRequest, minting to a key of ours (LUD-25 minting). */
internal sealed interface MintInvoice {
    data class Ok(val invoice: String, val expiresAtMillis: Long) : MintInvoice

    /** The key was used before: recover the index and try a fresh one. */
    data object InUse : MintInvoice

    data object Unreachable : MintInvoice

    data object Refused : MintInvoice

    /** The invoice isn't for the amount asked, or describes something else. */
    data object Mismatch : MintInvoice

    companion object {
        /** An invoice from [pay] for [amountMsat] whose comment names [key]; checked like any LUD-06 invoice. */
        suspend fun fetch(wallet: Wallet, pay: MintingPayRequest, amountMsat: Long, key: ByteVector32): MintInvoice {
            val response =
                try {
                    wallet.client.get(Requests.mintInvoice(pay.pay.callback, amountMsat, key))
                } catch (e: IOException) {
                    return Unreachable
                } catch (e: ProtocolViolation) {
                    return Unreachable
                }
            if (response is LnurlResponse.Error) {
                return if (RejectionKind.fromReason(response.reason) == RejectionKind.AlreadyInUse) InUse else Refused
            }
            val raw = response.raw.string("pr") ?: return Mismatch
            val invoice = Bolt11.decode(raw) ?: return Mismatch
            // The reference mint's minting invoices carry a memo rather than the metadata hash; a hash, if given, must match.
            val hashOk = invoice.descriptionHashHex == null || invoice.descriptionHashHex == Payer.sha256Hex(pay.pay.metadata)
            if (invoice.amountMsat != amountMsat || !hashOk) return Mismatch
            return Ok(invoice.raw, invoice.expiresAtSeconds * 1000)
        }

        /**
         * An invoice from [pay] for [amountMsat] to a fresh wallet key of [accountId] at [host], reserved before it is shown to the mint. A
         * key the mint knows already is passed over (the index recovered once), up to [WalletSettings.newNoteMaxKeysTriedWhileInUse] keys.
         * The last key tried and the mint's answer for it: [Ok], or why there is no invoice ([InUse] if every key was).
         */
        suspend fun forFreshKey(
            wallet: Wallet,
            guard: IndexGuard,
            accountId: String,
            host: String,
            pay: MintingPayRequest,
            amountMsat: Long,
        ): ForKey {
            var attempt = 0
            while (true) {
                val reserved = guard.reserve(accountId, host, KeySet.Wallet)
                val answer = fetch(wallet, pay, amountMsat, reserved.key)
                if (answer != InUse || ++attempt >= wallet.settings.newNoteMaxKeysTriedWhileInUse) return ForKey(reserved, answer)
                if (attempt == 1) guard.recover(accountId, host, KeySet.Wallet)
            }
        }

        /**
         * Journals [invoice] of [mint], minting to [output], as an invoice of the wallet ([OperationType.Mint]): [MintReconciler] picks its
         * note up once paid and drops it once expired. [detail]: the melt of ours that pays it, if any.
         */
        suspend fun journal(
            wallet: Wallet,
            id: String,
            accountId: String,
            mint: Mint,
            output: ReservedOutput,
            invoice: String,
            detail: String? = null,
        ) {
            val now = wallet.now()
            wallet.store.putJournal(
                JournalEntry(
                    id,
                    accountId,
                    type = OperationType.Mint,
                    mint.host,
                    mint.withdrawUrl,
                    inputIds = emptyList(),
                    inputK1s = emptyList(),
                    outputs = listOf(output.ref),
                    requestUrl = invoice,
                    status = JournalStatus.Prepared,
                    detail = detail,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                )
            )
        }
    }

    /** What [forFreshKey] came to: the mint's [answer] for the key [output]. */
    class ForKey(val output: ReservedOutput, val answer: MintInvoice)
}

/**
 * Receiving by invoice, for accounts without (or besides) a Lightning address: the home mint's minting payRequest issues an invoice whose
 * note goes to a fresh wallet key of the account. The key is reserved and the expected note journaled ([OperationType.Mint], no melt)
 * before the invoice is shown; [MintReconciler] picks the note up once paid, records it in History, and drops the entry once the invoice
 * expired unpaid.
 */
internal class InvoiceReceiver(private val wallet: Wallet) {
    private val guard = IndexGuard(wallet)

    /** An invoice of the active account's home mint for [amountMsat]. */
    suspend fun request(amountMsat: Long): InvoiceRequest {
        val accountId = wallet.activeId()
        val host = wallet.homeOf(accountId) ?: return InvoiceRequest.Unreachable
        val pay =
            try {
                wallet.client.servicePayRequest(host)
            } catch (e: IOException) {
                return InvoiceRequest.Unreachable
            } catch (e: ProtocolViolation) {
                return InvoiceRequest.Unreachable
            }
        return request(accountId, host, pay, amountMsat)
    }

    /**
     * An invoice of [host] (one of [accountId]'s mints, whose minting payRequest is [pay]) for [amountMsat], to a fresh key of the account,
     * journaled before it is handed out.
     */
    suspend fun request(accountId: String, host: String, pay: MintingPayRequest, amountMsat: Long): InvoiceRequest {
        val expected = pay.fee?.noteValue(amountMsat) ?: amountMsat
        if (amountMsat < pay.pay.minSendable || amountMsat > pay.pay.maxSendable || expected <= 0) {
            return InvoiceRequest.OutOfRange(pay.pay.minSendable, pay.pay.maxSendable)
        }
        val mint = wallet.mintOf(accountId, host) ?: return InvoiceRequest.Unreachable
        val fetched = MintInvoice.forFreshKey(wallet, guard, accountId, host, pay, amountMsat)
        return when (val answer = fetched.answer) {
            is MintInvoice.Ok -> {
                val id = UUID.randomUUID().toString()
                MintInvoice.journal(wallet, id, accountId, mint, fetched.output, answer.invoice)
                InvoiceRequest.Ready(id, answer.invoice, amountMsat, expected, answer.expiresAtMillis)
            }
            MintInvoice.Unreachable -> InvoiceRequest.Unreachable
            MintInvoice.InUse,
            MintInvoice.Refused,
            MintInvoice.Mismatch -> InvoiceRequest.Refused
        }
    }

    suspend fun status(id: String): InvoiceStatus {
        val mints = MintReconciler(wallet)
        val entry = mints.entry(id) ?: return InvoiceStatus.Expired
        return when (mints.reconcile(entry)) {
            JournalStatus.Confirmed -> InvoiceStatus.Paid(wallet.store.note(entry.outputs.single().q)?.amountMsat ?: 0)
            JournalStatus.Rejected -> InvoiceStatus.Expired
            else -> InvoiceStatus.Waiting
        }
    }
}
