package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import java.io.IOException
import java.util.UUID
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.core.model.OperationType
import whitewallet.lnurl.Bolt11
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurl.string
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.protocol.MintingPayRequest
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import whitewallet.lnurlcash.protocol.Requests

/** The outcome of [Wallet.requestInvoice]. */
sealed interface InvoiceRequest {
    /** Paying [invoice] mints a note worth [expectedMsat] (the amount less the mint's fee) to this account. */
    data class Ready(val id: String, val invoice: String, val amountMsat: Long, val expectedMsat: Long, val expiresAtMillis: Long) : InvoiceRequest

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
            val response = try {
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
    }
}

/**
 * Receiving by invoice, for accounts without (or besides) a Lightning address: the home mint's minting payRequest
 * issues an invoice whose note goes to a fresh wallet key of the account. The key is reserved and the expected note
 * journaled ([OperationType.Mint], no melt) before the invoice is shown; [MintReconciler] picks the note up once
 * paid, records it in History, and drops the entry once the invoice expired unpaid.
 */
internal class InvoiceReceiver(private val wallet: Wallet) {
    private val guard = IndexGuard(wallet)

    suspend fun request(amountMsat: Long): InvoiceRequest {
        val accountId = wallet.activeId()
        val host = wallet.store.account(accountId)?.host ?: return InvoiceRequest.Unreachable
        val pay = try {
            wallet.client.servicePayRequest(host)
        } catch (e: IOException) {
            return InvoiceRequest.Unreachable
        } catch (e: ProtocolViolation) {
            return InvoiceRequest.Unreachable
        }
        val expected = pay.fee?.noteValue(amountMsat) ?: amountMsat
        if (amountMsat < pay.pay.minSendable || amountMsat > pay.pay.maxSendable || expected <= 0) {
            return InvoiceRequest.OutOfRange(pay.pay.minSendable, pay.pay.maxSendable)
        }
        val mint = wallet.store.mint(host) ?: return InvoiceRequest.Unreachable
        repeat(IndexGuard.IN_USE_ATTEMPTS) { attempt ->
            val reserved = guard.reserve(accountId, host, NotePurpose.Wallet)
            when (val fetched = MintInvoice.fetch(wallet, pay, amountMsat, reserved.key)) {
                is MintInvoice.Ok -> {
                    val id = UUID.randomUUID().toString()
                    val now = wallet.now()
                    wallet.store.putJournal(JournalEntry(id, accountId, OperationType.Mint, host, mint.withdrawUrl, emptyList(), emptyList(),
                        listOf(reserved.ref), fetched.invoice, JournalStatus.Prepared, null, now, now))
                    return InvoiceRequest.Ready(id, fetched.invoice, amountMsat, expected, fetched.expiresAtMillis)
                }
                MintInvoice.InUse -> if (attempt == 0) guard.recover(accountId, host, NotePurpose.Wallet)
                MintInvoice.Unreachable -> return InvoiceRequest.Unreachable
                MintInvoice.Refused, MintInvoice.Mismatch -> return InvoiceRequest.Refused
            }
        }
        return InvoiceRequest.Refused
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
