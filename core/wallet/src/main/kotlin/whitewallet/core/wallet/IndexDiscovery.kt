package whitewallet.core.wallet

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import whitewallet.core.model.Mint
import whitewallet.core.model.OutputRef
import whitewallet.lnurlcash.spendDomain

/**
 * [stoppedEarly] says why a scan ended before its gap; [refused] when the mint refused a lookup rather than not answering, or presented
 * another signing key than the pinned one ([keyChanged], with [MINT_KEY_CHANGED]).
 */
data class DiscoveryResult(
    val next: Long,
    val checked: Int,
    val imported: Int,
    val stoppedEarly: String?,
    val refused: Boolean = false,
    val keyChanged: Boolean = false,
)

/** The state of one key set's index on one mint, for the developer key page. */
data class IndexHealth(val next: Long, val highestUsed: Long, val lastVerifiedAtMillis: Long?) {
    /** The counter would hand out a key the wallet knows is used. */
    val behind: Boolean
        get() = next <= highestUsed
}

/**
 * One refresh or scan, as far as how many lookups each mint gets at once: [WalletSettings.scanLookupsAtOnce], until one of several gets no
 * answer, perhaps one too many for the mint. That mint is asked one at a time for the rest of the run, on every chain and account; another
 * mint isn't. Nothing is kept: the next run tries them at once again.
 */
internal class LookupRun(private val atOnce: Int) {
    private val oneAtATime: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun atOnce(host: String): Int = if (host in oneAtATime) 1 else atOnce

    fun oneAtATime(host: String) {
        oneAtATime += host
    }
}

/**
 * Finds the right index for a key set ([KeySet], LUD-26 recovery): start past the highest key the wallet knows is used, look keys up by `p`
 * until a gap limit of consecutive ones are unused (20 by default), and set the index right after the last used one. Lookups import and
 * sync notes on the way (through [KeyProber]). The index never goes back: keys the wallet handed out (in an invoice, say) may be in use at
 * the mint though a lookup calls them unknown. A lookup the mint refuses stops the scan, as no answer does: the key may be used.
 */
internal class IndexDiscovery(private val wallet: Wallet) {
    private val prober = KeyProber(wallet)

    /**
     * The highest index of [set] that the wallet holds (or held) a note at on [host] itself, or -1. Mints on one domain share their keys
     * and their counter, but each holds its own notes: what is known at another mint of the domain says nothing about the keys below it
     * here, so a scan of this mint doesn't start past them.
     */
    suspend fun highestKnownUsed(accountId: String, host: String, set: KeySet): Long =
        wallet.store.notes(accountId).first().filter { it.host == host && set.holds(it, host) }.maxOfOrNull { it.index ?: -1 } ?: -1

    /**
     * Scans [set] of [accountId] on [host] from [from] (by default past the highest key the wallet holds a note at there), [gap] unused
     * keys in a row ending it. [run] says how many lookups go out at once, and learns of a mint that gets one at a time. When the scan
     * stops early, the index is set past what it found until then, so the next one goes on from there; it is marked verified only when the
     * scan came to its end. A mint whose signing key changed ends it with [DiscoveryResult.keyChanged], not an exception.
     */
    suspend fun discover(
        accountId: String,
        host: String,
        set: KeySet,
        gap: Int = wallet.settings.scanStopAfterUnusedKeys,
        run: LookupRun = LookupRun(wallet.settings.scanLookupsAtOnce),
        from: Long? = null,
        onProbe: (index: Long, KeyProbe) -> Unit = { _, _ -> },
    ): DiscoveryResult {
        val current = wallet.store.indexOf(accountId, host, set)
        val mint = wallet.mintOf(accountId, host) ?: return DiscoveryResult(current, 0, 0, "unknown host $host")
        var lastUsed = from?.let { it - 1 } ?: highestKnownUsed(accountId, host, set)
        var index = lastUsed + 1
        // Stopped early: what it found until then stays found, so the next look goes on past it. The index never goes back.
        val kept = suspend {
            val next = maxOf(lastUsed + 1, wallet.store.indexOf(accountId, host, set))
            if (next > current) wallet.store.setIndexOf(accountId, host, set, next)
            next
        }
        var unused = 0
        var checked = 0
        var imported = 0
        while (unused < gap) {
            val atOnce = run.atOnce(host)
            // Several lookups go out together, never past the end of the gap; their answers are applied in key order, so the scan finds
            // and stops at the same keys as one at a time would.
            val ids = (0 until minOf(atOnce, gap - unused)).map { set.key(accountId, host, index + it) }
            val answers = coroutineScope { ids.map { async { prober.lookup(it, mint) } }.awaitAll() }
            for ((id, answer) in ids.zip(answers)) {
                val probe =
                    try {
                        prober.apply(id, mint, answer)
                    } catch (e: MintKeyChangedException) {
                        // Nothing more is asked of this mint until its key matches.
                        return DiscoveryResult(kept(), checked, imported, MINT_KEY_CHANGED, refused = true, keyChanged = true)
                    }
                // One of several lookups failed, perhaps one too many for the mint: that key again, alone, before giving up.
                if (probe is KeyProbe.Failed && atOnce > 1) {
                    run.oneAtATime(host)
                    break
                }
                checked++
                onProbe(index, probe)
                when (probe) {
                    is KeyProbe.Outstanding -> {
                        lastUsed = index
                        unused = 0
                        if (probe.imported) imported++
                    }
                    is KeyProbe.Spent -> {
                        lastUsed = index
                        unused = 0
                    }
                    is KeyProbe.Refused ->
                        if (probe.pending) {
                            lastUsed = index
                            unused = 0
                        } else {
                            return DiscoveryResult(kept(), checked, imported, stoppedEarly = probe.reason ?: "refused", refused = true)
                        }
                    is KeyProbe.Unused -> unused++
                    is KeyProbe.Failed -> return DiscoveryResult(kept(), checked, imported, probe.reason)
                }
                index++
            }
        }
        val next = maxOf(lastUsed + 1, wallet.store.indexOf(accountId, host, set))
        wallet.store.setIndexOf(accountId, host, set, next)
        wallet.markIndexVerified(accountId, host, set)
        return DiscoveryResult(next, checked, imported, null)
    }

    /**
     * Looks up the keys of notes the wallet holds ([ids], all on [mint]), several at once as [discover] does and with the same fallback,
     * and applies each answer ([KeyProber.apply]): one burned elsewhere is spent, one the mint doesn't know is set aside. A refusal is that
     * note's alone. False if the mint stopped answering or presented another signing key; the rest weren't looked up.
     */
    suspend fun recheck(ids: List<KeyId>, mint: Mint, run: LookupRun, onProbe: (KeyProbe) -> Unit = {}): Boolean {
        var next = 0
        while (next < ids.size) {
            val atOnce = run.atOnce(mint.host)
            val batch = ids.subList(next, minOf(next + atOnce, ids.size))
            val answers = coroutineScope { batch.map { async { prober.lookup(it, mint) } }.awaitAll() }
            for ((id, answer) in batch.zip(answers)) {
                val probe =
                    try {
                        prober.apply(id, mint, answer)
                    } catch (e: MintKeyChangedException) {
                        return false
                    }
                if (probe is KeyProbe.Failed) {
                    if (atOnce == 1) return false
                    run.oneAtATime(mint.host)
                    break
                }
                onProbe(probe)
                next++
            }
        }
        return true
    }
}

/**
 * Hands out output keys of the account's own LUD-26 wallet and change chains, never one it knows is used: on an account's first use of a
 * mint domain the index is discovered first, and keys the wallet already holds a note at are skipped. Persist before disclose: each index
 * is reserved durably before its key is named in a request.
 */
internal class IndexGuard(private val wallet: Wallet) {
    private val discovery = IndexDiscovery(wallet)

    suspend fun reserve(accountId: String, host: String, set: KeySet): ReservedOutput {
        val counter = checkNotNull(set.counter) { "new keys come from the wallet or change chain, not $set" }
        val domain = spendDomain(host)
        if (wallet.store.keyIndexOrNull(accountId, domain, counter) == null) discover(accountId, host, set)
        val keys = wallet.keys(accountId, host)
        repeat(wallet.settings.newKeyMaxUsedKeysSkipped) {
            val index = wallet.store.reserveKeyIndex(accountId, domain, counter)
            val key = keys.pubkey(set, index)
            if (wallet.store.note(key.toHex()) == null) {
                return ReservedOutput(key, OutputRef(accountId, set.purpose, index, key.toHex(), set.share))
            }
        }
        error("no free key found for $set")
    }

    /**
     * The mint says a key is already in use: the local index may be behind, so look the keys up again, which also finds notes another phone
     * with the same phrase made there. Once per operation: a key held by an unpaid invoice looks unknown, so further refusals only mean
     * taking the next key.
     */
    suspend fun recover(accountId: String, host: String, set: KeySet) = discover(accountId, host, set)

    /** A mint whose key changed gets no output key: its callers stop as they do at any other [MintKeyChangedException]. */
    private suspend fun discover(accountId: String, host: String, set: KeySet) =
        discovery.discover(accountId, host, set).also { if (it.keyChanged) throw MintKeyChangedException(host) }
}
