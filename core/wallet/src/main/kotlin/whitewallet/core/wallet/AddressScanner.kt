package whitewallet.core.wallet

import whitewallet.core.model.ActivityKind
import whitewallet.lnurlcash.NotePurpose

/**
 * Finds notes the mint auto-minted for Lightning address payments (LUD-26 purpose 2) by looking up each derived
 * key by `p`, stopping after [GAP_LIMIT] consecutive unused indices, or at a lookup the mint refuses or doesn't answer.
 */
internal class AddressScanner(private val wallet: Wallet) {
    private val prober = KeyProber(wallet)
    private val log = ActivityLog(wallet)

    suspend fun scan(accountId: String, host: String): RefreshResult {
        val mint = wallet.store.mint(host) ?: return RefreshResult(0, 0)
        val purpose = NotePurpose.LightningAddress.value
        val start = wallet.store.addressScanFrom(accountId, host)
        var index = start
        var gap = 0
        var lastUsed = start - 1
        var found = 0
        var received = 0L
        while (gap < GAP_LIMIT) {
            when (val probe = prober.probe(KeyId(accountId, host, purpose, index), mint)) {
                is KeyProbe.Outstanding -> {
                    if (probe.imported) {
                        found++
                        received += probe.amountMsat
                        // Each address key is paid once, so its note's Q identifies the payment.
                        val q = wallet.branch(accountId, host).notePubkey(NotePurpose.LightningAddress, index).toHex()
                        log.record("recv:$q", accountId, ActivityKind.Received, probe.amountMsat, host, certified = probe.certified)
                    }
                    lastUsed = index
                    gap = 0
                }
                is KeyProbe.Spent -> { lastUsed = index; gap = 0 }
                is KeyProbe.Refused -> if (probe.pending) { lastUsed = index; gap = 0 } else break
                is KeyProbe.Unused -> gap++
                is KeyProbe.Failed -> break
            }
            index++
        }
        if (lastUsed + 1 > start) wallet.store.setAddressScanFrom(accountId, host, lastUsed + 1)
        return RefreshResult(found, received)
    }

    companion object {
        const val GAP_LIMIT = 20
    }
}
