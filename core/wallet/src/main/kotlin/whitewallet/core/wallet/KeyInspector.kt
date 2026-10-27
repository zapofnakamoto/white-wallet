package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.Note
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.Cp1
import whitewallet.lnurlcash.NotePurpose

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
 * The one state a derived key is in. The wallet's own (persisted) note decides first, since every lookup keeps
 * it in line with the mint; the mint's last saved answer fills in keys the wallet holds no note for; the
 * tab's index tells handed-out keys from free ones.
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
        fun of(index: Long, tabIndex: Long, note: Note?, probe: KeyProbe?): KeyStatus = when {
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
 * The local derivation indexes for one account and mint: the next unused index for purposes 0 (wallet) and
 * 1 (change), and the index the next Lightning Address scan (purpose 2) starts from.
 */
data class KeyIndexes(val wallet: Long, val change: Long, val addressScanFrom: Long) {
    fun of(purpose: Int): Long = when (purpose) {
        NotePurpose.Wallet.value -> wallet
        NotePurpose.Change.value -> change
        else -> addressScanFrom
    }
}

/**
 * One scan over keys [from] until [until] (exclusive; where it actually ended, since a scan runs on past every used
 * key it finds); [indexAfter] is the tab's index once it finished.
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

/** Developer tools over the derived keys of the active account. */
internal class KeyInspector(private val wallet: Wallet) {
    private val prober = KeyProber(wallet)

    /** Mints the account has keys on: its home mint plus any its notes live on. */
    suspend fun hosts(accountId: String): List<String> {
        val profileHost = wallet.store.account(accountId)?.host
        val noteHosts = wallet.store.notes(accountId).first().map { it.host }
        return (listOfNotNull(profileHost) + noteHosts + wallet.store.counterHosts(accountId)).distinct()
    }

    suspend fun indexes(accountId: String, host: String) = KeyIndexes(
        wallet = wallet.store.currentIndex(accountId, host, NotePurpose.Wallet.value),
        change = wallet.store.currentIndex(accountId, host, NotePurpose.Change.value),
        addressScanFrom = wallet.store.addressScanFrom(accountId, host),
    )

    /** The tab's one index: the purpose 0/1 counter, or where the address scan (purpose 2) starts. */
    suspend fun index(accountId: String, host: String, purpose: Int): Long = indexes(accountId, host).of(purpose)

    /** Keys 0 until index + [gap] for one purpose (capped). */
    suspend fun keys(
        accountId: String,
        host: String,
        purpose: Int,
        gap: Int,
        probes: Map<KeyId, KeyProbe>,
        failures: Map<KeyId, KeyProbe.Failed>,
    ): List<DerivedKey> {
        val branch = wallet.branchIfSeeded(accountId, host) ?: return emptyList()
        val p = NotePurpose.entries.first { it.value == purpose }
        val tabIndex = index(accountId, host, purpose)
        val count = (tabIndex + gap).coerceAtMost(MAX_LISTED)
        return (0 until count).map { index ->
            val id = KeyId(accountId, host, purpose, index)
            val q = branch.notePubkey(p, index)
            val note = wallet.store.note(q.toHex())
            val probe = probes[id]
            val failure = failures[id]?.takeIf { it.checkedAtMillis >= (probe?.checkedAtMillis ?: Long.MIN_VALUE) }
            DerivedKey(id, q.toHex(), Cp1.encode(q), probe, failure, note?.state, note?.amountMsat, KeyStatus.of(index, tabIndex, note, probe))
        }
    }

    /** Looks up keys from the index until [gap] unused keys in a row follow the last used one. */
    suspend fun scanAhead(accountId: String, host: String, purpose: Int, gap: Int): KeyScanResult {
        val from = index(accountId, host, purpose)
        return scan(accountId, host, purpose, from, from + gap, gap)
    }

    /** Looks up every key handed out so far, then on until [gap] unused keys in a row follow the last used one. */
    suspend fun recheckAll(accountId: String, host: String, purpose: Int, gap: Int): KeyScanResult =
        scan(accountId, host, purpose, 0, index(accountId, host, purpose) + gap, gap)

    /**
     * Looks up keys from [from], at least until [until] and then on until [gap] unused keys in a row follow the last
     * used key found, moving the index past it. Stops early if the mint stops answering or after [MAX_SCAN] keys.
     */
    private suspend fun scan(accountId: String, host: String, purpose: Int, from: Long, until: Long, gap: Int): KeyScanResult {
        val mint = wallet.store.mint(host)
            ?: return KeyScanResult(from, until, 0, 0, 0, "unknown host", index(accountId, host, purpose))
        var end = until
        var checked = 0
        var outstanding = 0
        var imported = 0
        var lastUsed = -1L
        var stopped: String? = null
        var index = from
        while (index < end) {
            if (checked == MAX_SCAN) { stopped = "stopped after $MAX_SCAN keys"; break }
            val probe = prober.probe(KeyId(accountId, host, purpose, index), mint)
            checked++
            when (probe) {
                is KeyProbe.Outstanding -> { outstanding++; if (probe.imported) imported++; lastUsed = index }
                is KeyProbe.Spent -> lastUsed = index
                is KeyProbe.Failed -> { stopped = probe.reason; break }
                else -> Unit
            }
            if (lastUsed == index) end = maxOf(end, index + 1 + gap)
            index++
        }
        advanceIndex(accountId, host, purpose, lastUsed)
        return KeyScanResult(from, index, checked, outstanding, imported, stopped, index(accountId, host, purpose))
    }

    suspend fun recheck(id: KeyId): KeyProbe {
        val mint = checkNotNull(wallet.store.mint(id.host)) { "unknown host ${id.host}" }
        return prober.probe(id, mint).also {
            if (it is KeyProbe.Outstanding || it is KeyProbe.Spent) advanceIndex(id.accountId, id.host, id.purpose, id.index)
        }
    }

    /** Moves the tab's index just past [lastUsed] if it is not already past it. Never lowers it. */
    private suspend fun advanceIndex(accountId: String, host: String, purpose: Int, lastUsed: Long) {
        if (lastUsed + 1 <= index(accountId, host, purpose)) return
        if (purpose == NotePurpose.LightningAddress.value) wallet.store.setAddressScanFrom(accountId, host, lastUsed + 1)
        else wallet.store.setIndex(accountId, host, purpose, lastUsed + 1)
    }

    private companion object {
        const val MAX_LISTED = 500L
        const val MAX_SCAN = 1_000
    }
}
