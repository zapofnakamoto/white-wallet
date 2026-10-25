package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.NoteKind
import whitewallet.lnurlcash.NotePurpose

data class DiscoveryResult(val next: Long, val checked: Int, val imported: Int, val stoppedEarly: String?)

/** The state of one purpose's index on one service, for the developer key page. */
data class IndexHealth(val next: Long, val highestUsed: Long, val lastVerifiedAtMillis: Long?) {
    /** The counter would hand out a key the wallet knows is used. */
    val behind: Boolean get() = next <= highestUsed
}

/**
 * Finds the right index for a purpose (LUD-26 recovery): start past the highest key the wallet knows is used, look
 * keys up by `p` until a gap limit of consecutive ones are unused (20 by default), and set the index right after the
 * last used one. Lookups import and sync notes on the way (through [KeyProber]).
 */
internal class IndexDiscovery(private val wallet: Wallet) {
    private val prober = KeyProber(wallet)

    /** The highest index of [purpose] on [host] that the wallet holds (or held) a note at, or -1. */
    suspend fun highestKnownUsed(accountId: String, host: String, purpose: Int): Long = wallet.store.notes(accountId).first()
        .filter { it.host == host && it.purpose == purpose && it.kind == NoteKind.KeyPath }
        .maxOfOrNull { it.index ?: -1 } ?: -1

    suspend fun discover(accountId: String, host: String, purpose: Int, gap: Int = AddressScanner.GAP_LIMIT): DiscoveryResult {
        val current = current(accountId, host, purpose)
        val service = wallet.store.service(host) ?: return DiscoveryResult(current, 0, 0, "unknown host $host")
        var lastUsed = highestKnownUsed(accountId, host, purpose)
        var index = lastUsed + 1
        var unused = 0
        var checked = 0
        var imported = 0
        while (unused < gap) {
            val probe = prober.probe(KeyId(accountId, host, purpose, index), service)
            checked++
            when (probe) {
                is KeyProbe.Outstanding -> { lastUsed = index; unused = 0; if (probe.imported) imported++ }
                is KeyProbe.Spent -> { lastUsed = index; unused = 0 }
                is KeyProbe.Unused, is KeyProbe.Refused -> unused++
                is KeyProbe.Failed -> return DiscoveryResult(current(accountId, host, purpose), checked, imported, probe.reason)
            }
            index++
        }
        val next = lastUsed + 1
        if (purpose == NotePurpose.LightningAddress.value) wallet.store.setAddressScanFrom(accountId, host, next)
        else wallet.store.setIndex(accountId, host, purpose, next)
        wallet.markIndexVerified(accountId, host, purpose)
        return DiscoveryResult(next, checked, imported, null)
    }

    private suspend fun current(accountId: String, host: String, purpose: Int) =
        if (purpose == NotePurpose.LightningAddress.value) wallet.store.addressScanFrom(accountId, host)
        else wallet.store.currentIndex(accountId, host, purpose)
}

/**
 * Hands out output keys for the wallet's own purposes (0 wallet, 1 change), never one it knows is used:
 * on an account's first use of a service the index is discovered first, and keys the wallet already holds a note
 * at are skipped. Persist before disclose: each index is reserved durably before its key is named in a request.
 */
internal class IndexGuard(private val wallet: Wallet) {
    private val discovery = IndexDiscovery(wallet)

    suspend fun reserve(accountId: String, host: String, purpose: NotePurpose): ReservedOutput {
        if (wallet.store.currentIndexOrNull(accountId, host, purpose.value) == null) discovery.discover(accountId, host, purpose.value)
        val branch = wallet.branch(accountId, host)
        repeat(MAX_SKIPS) {
            val index = wallet.store.reserveIndex(accountId, host, purpose.value)
            val key = branch.notePubkey(purpose, index)
            if (wallet.store.note(key.toHex()) == null) {
                return ReservedOutput(key, whitewallet.core.model.OutputRef(accountId, purpose.value, index, key.toHex()))
            }
        }
        error("no free key found for purpose ${purpose.value}")
    }

    /** The service says a key is already in use: the local index is behind, so find the real one. */
    suspend fun recover(accountId: String, host: String, purpose: NotePurpose) = discovery.discover(accountId, host, purpose.value)

    private companion object {
        const val MAX_SKIPS = 10_000
    }
}
