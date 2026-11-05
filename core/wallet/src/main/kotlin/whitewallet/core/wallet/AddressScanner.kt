package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.ActivityKind

/**
 * Finds notes the mint auto-minted for Lightning address payments (LUD-26: a share's notes; legacy 2f17578: purpose 2) by looking up each
 * derived key by `p`, stopping after [GAP_LIMIT] consecutive unused indices, or at a lookup the mint refuses or doesn't answer.
 */
internal class AddressScanner(private val wallet: Wallet) {
    private val prober = KeyProber(wallet)
    private val log = ActivityLog(wallet)

    /**
     * Every address key set of the account on [host]: the share of each of its addresses there, and the legacy purpose 2 while any address
     * is still on the legacy branch, or the account has none (tag: keys).
     */
    suspend fun scanAll(accountId: String, host: String): RefreshResult {
        val addresses = wallet.store.addresses(accountId).first().filter { it.host == host }
        val sets = addresses.mapNotNull { it.share }.distinct().map(KeySet::share).toMutableList()
        if ((addresses.isEmpty() || addresses.any { it.share == null }) && wallet.ownsLegacyKeys(accountId)) sets += LEGACY_ADDRESSES
        return sets.fold(RefreshResult(0, 0)) { total, set ->
            val r = scan(accountId, host, set)
            RefreshResult(total.newNotes + r.newNotes, total.receivedMsat + r.receivedMsat)
        }
    }

    suspend fun scan(accountId: String, host: String, set: KeySet = LEGACY_ADDRESSES): RefreshResult {
        require(set.isAddress)
        val mint = wallet.store.mint(host) ?: return RefreshResult(0, 0)
        val start = wallet.store.indexOf(accountId, host, set)
        var index = start
        var gap = 0
        var lastUsed = start - 1
        var found = 0
        var received = 0L
        while (gap < GAP_LIMIT) {
            when (val probe = prober.probe(set.key(accountId, host, index), mint)) {
                is KeyProbe.Outstanding -> {
                    if (probe.imported) {
                        found++
                        received += probe.amountMsat
                        // Each address key is paid once, so its note's Q identifies the payment.
                        val q = wallet.keys(accountId, host).pubkey(set, index).toHex()
                        log.record(
                            id = "recv:$q",
                            accountId,
                            kind = ActivityKind.Received,
                            probe.amountMsat,
                            host,
                            certified = probe.certified,
                        )
                    }
                    lastUsed = index
                    gap = 0
                }
                is KeyProbe.Spent -> {
                    lastUsed = index
                    gap = 0
                }
                is KeyProbe.Refused ->
                    if (probe.pending) {
                        lastUsed = index
                        gap = 0
                    } else break
                is KeyProbe.Unused -> gap++
                is KeyProbe.Failed -> break
            }
            index++
        }
        if (lastUsed + 1 > start) wallet.store.setIndexOf(accountId, host, set, lastUsed + 1)
        return RefreshResult(found, received)
    }

    companion object {
        const val GAP_LIMIT = 20

        /** Legacy 2f17578 (tag: keys): every address of an account on a mint shares purpose 2. */
        val LEGACY_ADDRESSES = KeySet.LegacyAddress
    }
}
