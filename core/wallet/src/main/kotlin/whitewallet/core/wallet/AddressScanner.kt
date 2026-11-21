package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.ActivityKind
import whitewallet.core.model.KeyCounter
import whitewallet.lnurlcash.spendDomain

/**
 * Finds notes the mint auto-minted for Lightning address payments (LUD-26: a share's notes) with the walker a restore uses
 * ([IndexDiscovery.discover]): from where the share's last scan stopped, several lookups at once, until
 * [WalletSettings.scanStopAfterUnusedKeys] unused keys in a row. A lookup the mint refuses or doesn't answer ends that share's scan
 * quietly, keeping what it found; a signing key other than the pinned one ends it too ([RefreshResult.keyChanged]). Each payment found is a
 * "Received" row.
 */
internal class AddressScanner(private val wallet: Wallet) {
    private val discovery = IndexDiscovery(wallet)
    private val log = ActivityLog(wallet)

    /** Every address key set of the account on [host]: the share of each of its addresses there, and every share it handed out. */
    suspend fun scanAll(accountId: String, host: String, run: LookupRun): RefreshResult {
        val addresses = wallet.store.addresses(accountId).first().filter { it.host == host }
        // Every share handed out, not only those of known addresses: a restored phone doesn't know its names, and they still get paid.
        val handedOut = wallet.store.keyIndexOrNull(accountId, spendDomain(host), KeyCounter.Shares) ?: 0
        val sets = (addresses.map { it.share } + (0 until handedOut)).distinct().map(KeySet::Share)
        return sets.fold(RefreshResult(0, 0)) { total, set -> total + scan(accountId, host, set, run) }
    }

    /** One share's notes on [host], from where its last scan stopped. */
    suspend fun scan(accountId: String, host: String, set: KeySet.Share, run: LookupRun): RefreshResult {
        val mint = wallet.mintOf(accountId, host) ?: return RefreshResult(0, 0)
        var count = 0
        var sumMsat = 0L
        val scanned =
            discovery.discover(accountId, host, set, run = run, from = wallet.store.indexOf(accountId, host, set)) { index, probe ->
                if (probe is KeyProbe.Outstanding && probe.imported) {
                    // Each address key is paid once, so its note's Q identifies the payment. Its row goes in with the note: a refresh
                    // stopped later would find the note held and never write it.
                    val q = wallet.keys(accountId, host).pubkey(set, index).toHex()
                    log.record(
                        id = "recv:$q",
                        accountId,
                        kind = ActivityKind.Received,
                        probe.amountMsat,
                        mint.host,
                        certified = probe.certified,
                    )
                    count++
                    sumMsat += probe.amountMsat
                }
            }
        return RefreshResult(count, sumMsat, if (scanned.keyChanged) setOf(host) else emptySet())
    }
}
