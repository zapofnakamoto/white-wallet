package whitewallet.core.wallet

import whitewallet.core.model.Mint
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.NotePurpose
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind
import java.io.IOException

/** Identifies one derived note key. */
data class KeyId(val accountId: String, val host: String, val purpose: Int, val index: Long)

/** What the mint said about a derived key the last time it was looked up (by `p`, never by spend). */
sealed interface KeyProbe {
    val checkedAtMillis: Long

    /**
     * The mint doesn't know a note at this key. It may still hold the key for a note an unpaid invoice would make,
     * which it never shows: only a refused output (`already in use`) tells.
     */
    data class Unused(override val checkedAtMillis: Long) : KeyProbe

    /** An outstanding note. [imported] is true if this lookup added it to the wallet. */
    data class Outstanding(val amountMsat: Long, val certified: Boolean, val imported: Boolean, override val checkedAtMillis: Long) : KeyProbe

    data class Spent(override val checkedAtMillis: Long) : KeyProbe

    /** Refused for another reason: [pending] (a note being paid out, so the key is used) or an unexpected error. */
    data class Refused(val reason: String?, override val checkedAtMillis: Long) : KeyProbe {
        val pending: Boolean get() = RejectionKind.fromReason(reason) == RejectionKind.Pending
    }

    /** No answer: network failure, rate limiting or an invalid response. Stops a scan. */
    data class Failed(val reason: String, override val checkedAtMillis: Long) : KeyProbe
}

/**
 * Looks up one derived key and brings the wallet in line with the answer: an outstanding note the wallet does not
 * know is imported, and a note the wallet holds follows the mint's state (outstanding, spent, or unknown when the mint
 * doesn't know it).
 */
internal class KeyProber(private val wallet: Wallet) {
    suspend fun probe(id: KeyId, mint: Mint): KeyProbe {
        val purpose = NotePurpose.entries.first { it.value == id.purpose }
        val q = wallet.branch(id.accountId, id.host).notePubkey(purpose, id.index)
        val noteId = q.toHex()
        val now = wallet.now()
        val result = try {
            val info = wallet.client.lookup(mint.withdrawUrl, q)
            val pinned = Pinning.check(wallet, mint, info.mintPubkey)
            val certificate = info.certificate?.takeIf { Certificates.verify(it, q, pinned) && it.amountMsat == info.maxWithdrawableMsat }
            val existing = wallet.store.note(noteId)
            if (existing == null || existing.state == NoteState.Spent || existing.state == NoteState.Unknown || existing.amountMsat != info.maxWithdrawableMsat) {
                wallet.store.upsertNotes(listOf(Note(noteId, id.accountId, id.host, NoteKind.KeyPath, id.purpose, id.index,
                    info.maxWithdrawableMsat, certificate?.encode() ?: existing?.certificate,
                    // A note handed out as a link stays out of the balance until it is redeemed or taken back.
                    if (existing?.state == NoteState.Shared) NoteState.Shared else NoteState.Outstanding, now)))
            }
            if (existing == null) ensureCounterPast(id)
            KeyProbe.Outstanding(info.maxWithdrawableMsat, certificate != null, imported = existing == null, now)
        } catch (e: NoteLookupError) {
            when (e.kind) {
                RejectionKind.Spent -> {
                    wallet.store.note(noteId)?.takeIf { it.state == NoteState.Outstanding || it.state == NoteState.Unknown }?.let {
                        wallet.store.upsertNotes(listOf(it.copy(state = NoteState.Spent, updatedAtMillis = now)))
                    }
                    ensureCounterPast(id)
                    KeyProbe.Spent(now)
                }
                RejectionKind.NoteUnknown -> {
                    // A note the wallet holds that the mint doesn't know: out of the balance, kept until it knows it again.
                    wallet.store.note(noteId)?.takeIf { it.state == NoteState.Outstanding }?.let {
                        wallet.store.upsertNotes(listOf(it.copy(state = NoteState.Unknown, updatedAtMillis = now)))
                    }
                    KeyProbe.Unused(now)
                }
                RejectionKind.RateLimited -> KeyProbe.Failed(e.reason ?: "rate limited", now)
                else -> KeyProbe.Refused(e.reason, now)
            }
        } catch (e: IOException) {
            KeyProbe.Failed(e.message ?: "network error", now)
        } catch (e: ProtocolViolation) {
            KeyProbe.Failed(e.message ?: "invalid response", now)
        }
        wallet.recordProbe(id, result)
        return result
    }

    /** A used key must never be handed out again: keep the wallet's own counters past it. */
    private suspend fun ensureCounterPast(id: KeyId) {
        if (id.purpose == NotePurpose.LightningAddress.value) return
        if (wallet.store.currentIndex(id.accountId, id.host, id.purpose) <= id.index) {
            wallet.store.setIndex(id.accountId, id.host, id.purpose, id.index + 1)
        }
    }
}
