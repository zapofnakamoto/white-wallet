package whitewallet.core.wallet

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import whitewallet.core.model.OutputRef
import whitewallet.lnurlcash.spendDomain

/** [stoppedEarly] says why a scan ended before its gap; [refused] when the mint refused a lookup rather than not answering. */
data class DiscoveryResult(val next: Long, val checked: Int, val imported: Int, val stoppedEarly: String?, val refused: Boolean = false)

/** The state of one key set's index on one mint, for the developer key page. */
data class IndexHealth(val next: Long, val highestUsed: Long, val lastVerifiedAtMillis: Long?) {
    /** The counter would hand out a key the wallet knows is used. */
    val behind: Boolean
        get() = next <= highestUsed
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

    suspend fun discover(
        accountId: String,
        host: String,
        set: KeySet,
        gap: Int = wallet.settings.scanStopAfterUnusedKeys,
        onProbe: (KeyProbe) -> Unit = {},
    ): DiscoveryResult {
        val current = wallet.store.indexOf(accountId, host, set)
        val mint = wallet.mintOf(accountId, host) ?: return DiscoveryResult(current, 0, 0, "unknown host $host")
        var lastUsed = highestKnownUsed(accountId, host, set)
        var index = lastUsed + 1
        var unused = 0
        var checked = 0
        var imported = 0
        var atOnce = wallet.settings.scanLookupsAtOnce
        while (unused < gap) {
            // Several lookups go out together, never past the end of the gap; their answers are applied in key order, so the scan finds
            // and stops at the same keys as one at a time would.
            val ids = (0 until minOf(atOnce, gap - unused)).map { set.key(accountId, host, index + it) }
            val answers = coroutineScope { ids.map { async { prober.lookup(it, mint) } }.awaitAll() }
            for ((id, answer) in ids.zip(answers)) {
                val probe = prober.apply(id, mint, answer)
                // One of several lookups failed, perhaps one too many for the mint: that key again, alone, before giving up.
                if (probe is KeyProbe.Failed && atOnce > 1) {
                    atOnce = 1
                    break
                }
                checked++
                onProbe(probe)
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
                            return DiscoveryResult(
                                next = wallet.store.indexOf(accountId, host, set),
                                checked,
                                imported,
                                stoppedEarly = probe.reason ?: "refused",
                                refused = true,
                            )
                        }
                    is KeyProbe.Unused -> unused++
                    is KeyProbe.Failed ->
                        return DiscoveryResult(wallet.store.indexOf(accountId, host, set), checked, imported, probe.reason)
                }
                index++
            }
        }
        val next = maxOf(lastUsed + 1, wallet.store.indexOf(accountId, host, set))
        wallet.store.setIndexOf(accountId, host, set, next)
        wallet.markIndexVerified(accountId, host, set)
        return DiscoveryResult(next, checked, imported, null)
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
        if (wallet.store.keyIndexOrNull(accountId, domain, counter) == null) discovery.discover(accountId, host, set)
        val keys = wallet.keys(accountId, host)
        repeat(wallet.settings.newKeyMaxUsedKeysSkipped) {
            val index = wallet.store.reserveKeyIndex(accountId, domain, counter)
            val key = keys.pubkey(set, index)
            if (wallet.store.note(key.toHex()) == null) {
                return ReservedOutput(key, OutputRef(accountId, set.purpose, index, key.toHex(), set.scheme))
            }
        }
        error("no free key found for $set")
    }

    /**
     * The mint says a key is already in use: the local index may be behind, so look the keys up again, which also finds notes another phone
     * with the same phrase made there. Once per operation: a key held by an unpaid invoice looks unknown, so further refusals only mean
     * taking the next key.
     */
    suspend fun recover(accountId: String, host: String, set: KeySet) = discovery.discover(accountId, host, set)
}
