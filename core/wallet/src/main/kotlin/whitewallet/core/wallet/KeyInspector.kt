package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.Cp1

/** A derived note key as the developer key page shows it. Only public data: never the spend. */
data class DerivedKey(
    val id: KeyId,
    /** Lowercase hex of the x-only output key `Q`. */
    val q: String,
    val cp1: String,
    /** The mint's last answer for this key (saved across restarts), if it was ever looked up. */
    val probe: KeyProbe?,
    /** A lookup this session that got no answer after [probe]; the key's [status] does not depend on it. */
    val failure: KeyProbe.Failed?,
    /** The state of the matching note in the wallet, if it holds one. */
    val noteState: NoteState?,
    /** The matching note's value in the wallet, if it holds one. */
    val noteAmountMsat: Long?,
    /** What this index is, all sources combined. */
    val status: KeyStatus,
)

/**
 * The one state a derived key is in. The wallet's own (persisted) note decides first, since every lookup keeps it in line with the mint;
 * the mint's last saved answer fills in keys the wallet holds no note for; the tab's index tells handed-out keys from free ones.
 */
sealed interface KeyStatus {
    /** The wallet holds an unspent note on this key. */
    data class Funded(val amountMsat: Long) : KeyStatus

    /** An operation on its note may or may not have landed; the next refresh settles it. */
    data object Confirming : KeyStatus

    /** Used and emptied. The mint never accepts a spent key again. */
    data object Spent : KeyStatus

    /** Below the index, but the mint has never seen it (e.g. an operation that was refused). */
    data object Skipped : KeyStatus

    /** Below the index, the wallet holds no note and the mint was never asked about it. */
    data object HandedOut : KeyStatus

    /** The index itself: the next key the wallet uses (Address tab: where the next payment is looked for). */
    data object Next : KeyStatus

    /** Above the index: not used yet. */
    data object Free : KeyStatus

    companion object {
        fun of(index: Long, tabIndex: Long, note: Note?, probe: KeyProbe?): KeyStatus =
            when {
                note?.state == NoteState.Outstanding -> Funded(note.amountMsat)
                note?.state == NoteState.Ambiguous || note?.state == NoteState.Paying -> Confirming
                note?.state == NoteState.Spent || probe is KeyProbe.Spent -> Spent
                index < tabIndex -> if (probe is KeyProbe.Unused) Skipped else HandedOut
                index == tabIndex -> Next
                else -> Free
            }
    }
}

/**
 * One scan over keys [from] until [until] (exclusive; where it actually ended, since a scan runs on past every used key it finds);
 * [indexAfter] is the tab's index once it finished.
 */
data class KeyScanResult(
    val from: Long,
    val until: Long,
    val checked: Int,
    val outstanding: Int,
    val imported: Int,
    val stoppedEarly: String?,
    val indexAfter: Long,
)

sealed interface SetIndexResult {
    data class Applied(val index: Long) : SetIndexResult

    /** Refused: keys below [floor] have been used, and the mint never reissues a used key. Nothing changed. */
    data class BelowFloor(val floor: Long) : SetIndexResult
}

/** One of an account's shares (`S/a'/2/n'`), with the [address] registered on it, if the phone knows one. */
data class AddressShare(val share: Long, val address: String?) {
    val set: KeySet
        get() = KeySet.share(share)
}

/** Developer tools over the derived keys of the active account. */
internal class KeyInspector(private val wallet: Wallet) {
    private val prober = KeyProber(wallet)

    /** Mints the account has keys on: its home mint plus any its notes live on. */
    suspend fun hosts(accountId: String): List<String> {
        val profileHost = wallet.store.account(accountId)?.host
        val noteHosts = wallet.store.notes(accountId).first().map { it.host }
        return (listOfNotNull(profileHost) + noteHosts + wallet.store.counterHosts(accountId)).distinct()
    }

    /** The tab's one index: the wallet or change counter, or where the scan of a share or legacy purpose starts. */
    suspend fun index(accountId: String, host: String, set: KeySet): Long = wallet.store.indexOf(accountId, host, set)

    /** Keys 0 until index + [gap] of one key set (capped). */
    suspend fun keys(
        accountId: String,
        host: String,
        set: KeySet,
        gap: Int,
        probes: Map<KeyId, KeyProbe>,
        failures: Map<KeyId, KeyProbe.Failed>,
    ): List<DerivedKey> {
        val keys = wallet.keysIfSeeded(accountId, host) ?: return emptyList()
        val tabIndex = index(accountId, host, set)
        val count = (tabIndex + gap).coerceAtMost(wallet.settings.keyScreenMaxKeysListed.toLong())
        return (0 until count).map { index ->
            val id = set.key(accountId, host, index)
            val q = keys.pubkey(set, index)
            val note = wallet.store.note(q.toHex())
            val probe = probes[id]
            val failure = failures[id]?.takeIf { it.checkedAtMillis >= (probe?.checkedAtMillis ?: Long.MIN_VALUE) }
            DerivedKey(
                id,
                q = q.toHex(),
                cp1 = Cp1.encode(q),
                probe,
                failure,
                noteState = note?.state,
                noteAmountMsat = note?.amountMsat,
                status = KeyStatus.of(index, tabIndex, note, probe),
            )
        }
    }

    /** Looks up keys from the index until [gap] unused keys in a row follow the last used one. */
    suspend fun scanAhead(accountId: String, host: String, set: KeySet, gap: Int): KeyScanResult {
        val from = index(accountId, host, set)
        return scan(accountId, host, set, from, until = from + gap, gap)
    }

    /** Looks up every key handed out so far, then on until [gap] unused keys in a row follow the last used one. */
    suspend fun recheckAll(accountId: String, host: String, set: KeySet, gap: Int): KeyScanResult =
        scan(accountId, host, set, from = 0, until = index(accountId, host, set) + gap, gap)

    /**
     * Looks up keys from [from], at least until [until] and then on until [gap] unused keys in a row follow the last used key found, moving
     * the index past it. Stops early if the mint stops answering or after [WalletSettings.keyScreenMaxKeysScanned] keys.
     */
    private suspend fun scan(accountId: String, host: String, set: KeySet, from: Long, until: Long, gap: Int): KeyScanResult {
        val mint =
            wallet.store.mint(host)
                ?: return KeyScanResult(
                    from,
                    until,
                    checked = 0,
                    outstanding = 0,
                    imported = 0,
                    stoppedEarly = "unknown host",
                    indexAfter = index(accountId, host, set),
                )
        var end = until
        var checked = 0
        var outstanding = 0
        var imported = 0
        var lastUsed = -1L
        var stopped: String? = null
        var index = from
        while (index < end) {
            if (checked == wallet.settings.keyScreenMaxKeysScanned) {
                stopped = "stopped after $checked keys"
                break
            }
            val probe = prober.probe(set.key(accountId, host, index), mint)
            checked++
            when (probe) {
                is KeyProbe.Outstanding -> {
                    outstanding++
                    if (probe.imported) imported++
                    lastUsed = index
                }
                is KeyProbe.Spent -> lastUsed = index
                is KeyProbe.Failed -> {
                    stopped = probe.reason
                    break
                }
                else -> Unit
            }
            if (lastUsed == index) end = maxOf(end, index + 1 + gap)
            index++
        }
        advanceIndex(accountId, host, set, lastUsed)
        return KeyScanResult(
            from,
            until = index,
            checked,
            outstanding,
            imported,
            stoppedEarly = stopped,
            indexAfter = index(accountId, host, set),
        )
    }

    suspend fun recheck(id: KeyId): KeyProbe {
        val mint = checkNotNull(wallet.store.mint(id.host)) { "unknown host ${id.host}" }
        return prober.probe(id, mint).also {
            if (it is KeyProbe.Outstanding || it is KeyProbe.Spent) advanceIndex(id.accountId, id.host, id.set, id.index)
        }
    }

    /** Moves the tab's index just past [lastUsed] if it is not already past it. Never lowers it. */
    private suspend fun advanceIndex(accountId: String, host: String, set: KeySet, lastUsed: Long) {
        if (lastUsed + 1 <= index(accountId, host, set)) return
        wallet.store.setIndexOf(accountId, host, set, lastUsed + 1)
    }

    private companion object {}
}
