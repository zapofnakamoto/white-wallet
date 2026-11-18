package whitewallet.core.wallet

import java.io.IOException
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.isShared
import whitewallet.lnurlcash.protocol.InvalidMintPubkey
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind

/** Identifies one derived note key. */
data class KeyId(
    val accountId: String,
    val host: String,
    val set: KeySet,
    val index: Long,
)

/** What the mint said about a derived key the last time it was looked up (by `p`, never by spend). */
sealed interface KeyProbe {
    val checkedAtMillis: Long

    /**
     * The mint doesn't know a note at this key (lnurl-mint's `Unknown note.`). It may still hold the key for a note an unpaid invoice would
     * make, which it never shows: only a refused output (`already in use`) tells.
     */
    data class Unused(override val checkedAtMillis: Long) : KeyProbe

    /** An outstanding note. [imported] is true if this lookup added it to the wallet. */
    data class Outstanding(val amountMsat: Long, val certified: Boolean, val imported: Boolean, override val checkedAtMillis: Long) :
        KeyProbe

    /**
     * A burned note: the mint answered `already in use` (LUD-25), or 0 (burned, or pending while a melt settles: a used key either way).
     */
    data class Spent(override val checkedAtMillis: Long) : KeyProbe

    /**
     * Refused for another reason: [pending] (a note being paid out, so the key is used), a spent note in a mint's own words, which proves
     * nothing, or an unexpected error. Neither used nor unused: a scan stops here and asks again later (LUD-26 "Recovery").
     */
    data class Refused(val reason: String?, override val checkedAtMillis: Long) : KeyProbe {
        val pending: Boolean
            get() = RejectionKind.fromReason(reason) == RejectionKind.Pending
    }

    /** No answer: network failure, rate limiting or an invalid response. Stops a scan. */
    data class Failed(val reason: String, override val checkedAtMillis: Long) : KeyProbe
}

/**
 * Looks up one derived key and brings the wallet in line with the answer: an outstanding note the wallet does not know is imported, and a
 * note the wallet holds follows the mint's state (outstanding, spent, or unknown when the mint doesn't know it). Only at a
 * [KeyCheckedMint]: most of these answers carry no key.
 */
internal class KeyProber(private val wallet: Wallet) {
    suspend fun probe(id: KeyId, mint: KeyCheckedMint): KeyProbe = apply(id, mint, lookup(id, mint))

    /**
     * Only the mint's answer for [id]'s key, nothing changed in the wallet yet: a scan sends several of these at once and [apply]s the
     * answers in key order. A failure is kept as the answer, for [apply] to judge.
     */
    suspend fun lookup(id: KeyId, mint: KeyCheckedMint): Result<NoteInfo> {
        val q = wallet.keys(id.accountId, id.host).pubkey(id)
        return try {
            // key-checked: a KeyCheckedMint.
            Result.success(wallet.client.lookup(mint.withdrawUrl, q))
        } catch (e: NoteLookupError) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: ProtocolViolation) {
            Result.failure(e)
        }
    }

    /** Brings the wallet in line with the mint's [answer] for [id] (see the class). */
    suspend fun apply(id: KeyId, mint: KeyCheckedMint, answer: Result<NoteInfo>): KeyProbe {
        // A refusal is an answer; no connection, or an answer that isn't a mint's (a proxy in front of one that is down), isn't.
        wallet.heardFrom(
            id.host,
            answered = answer.exceptionOrNull().let { it !is IOException && it !is ProtocolViolation || it is InvalidMintPubkey },
        )
        val q = wallet.keys(id.accountId, id.host).pubkey(id)
        val noteId = q.toHex()
        val now = wallet.now()
        val result =
            try {
                // A key that isn't one is none: a changed key at a pinned mint, which ends the scan like any other.
                val info = answer.getOrElse { if (it is InvalidMintPubkey) Pinning.invalidKey(mint.mint, it) else throw it }
                val pinned = Pinning.check(wallet, mint.mint, info.mintPubkey)
                val certificate =
                    info.certificate?.takeIf { certifies(it, id.host, q, pinned) && it.amountMsat == info.maxWithdrawableMsat }
                val existing = wallet.store.note(noteId)
                if (
                    existing == null ||
                        existing.state == NoteState.Spent ||
                        existing.state == NoteState.Unknown ||
                        existing.amountMsat != info.maxWithdrawableMsat
                ) {
                    wallet.store.upsertNotes(
                        listOf(
                            Note(
                                id = noteId,
                                id.accountId,
                                id.host,
                                kind = NoteKind.KeyPath,
                                id.set.purpose,
                                id.index,
                                amountMsat = info.maxWithdrawableMsat,
                                certificate = certificate?.encode() ?: existing?.certificate,
                                // A note handed out as a link stays out of the balance until it is spent or taken back.
                                state = existing?.state?.takeIf { it.isShared } ?: NoteState.Outstanding,
                                updatedAtMillis = now,
                                share = id.set.share,
                            )
                        )
                    )
                }
                if (existing == null) ensureCounterPast(id)
                KeyProbe.Outstanding(info.maxWithdrawableMsat, certificate != null, imported = existing == null, now)
            } catch (e: NoteLookupError) {
                when {
                    // Answered 0: burned, or still being paid. A note of ours being paid (Paying) is left to its melt.
                    e.burned || e.burnedOrPending -> {
                        wallet.store
                            .note(noteId)
                            ?.takeIf { it.host == id.host && (it.state == NoteState.Outstanding || it.state == NoteState.Unknown) }
                            ?.let {
                                wallet.store.upsertNotes(listOf(it.copy(state = NoteState.Spent, updatedAtMillis = now)))
                            }
                        ensureCounterPast(id)
                        KeyProbe.Spent(now)
                    }
                    e.kind == RejectionKind.NoteUnknown -> {
                        // A note the wallet holds that the mint doesn't know: out of the balance, kept until it knows it again. Mints on
                        // one domain share their keys, so the note may be another mint's, which this answer says nothing about.
                        wallet.store
                            .note(noteId)
                            ?.takeIf { it.host == id.host && it.state == NoteState.Outstanding }
                            ?.let {
                                // Set aside anew: not in the archive, whatever an earlier time there left.
                                wallet.store.upsertNotes(
                                    listOf(it.copy(state = NoteState.Unknown, updatedAtMillis = now, archivedAtMillis = null))
                                )
                            }
                        KeyProbe.Unused(now)
                    }
                    e.kind == RejectionKind.RateLimited -> KeyProbe.Failed(e.reason ?: "rate limited", now)
                    // A spent note in the mint's own words ("Note already spent.") among them: no proof the key was ever used.
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

    /** A used key must never be handed out again: keep the wallet's own counters past it (shares and addresses have none). */
    private suspend fun ensureCounterPast(id: KeyId) {
        val set = id.set
        if (set is KeySet.Share) return
        if (wallet.store.indexOf(id.accountId, id.host, set) <= id.index) wallet.store.setIndexOf(id.accountId, id.host, set, id.index + 1)
    }
}
