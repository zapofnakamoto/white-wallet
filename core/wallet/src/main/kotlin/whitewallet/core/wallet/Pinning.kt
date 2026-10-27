package whitewallet.core.wallet

import fr.acinq.bitcoin.PublicKey
import whitewallet.core.model.Service

/** Thrown when a service presents a different `mintPubkey` than the one pinned on first contact. */
class MintKeyChangedException(val host: String) : Exception("the signing key of $host changed")

internal object Pinning {
    /** Pins [presented] for [service] on first sight; afterward it must match. Returns the pinned key. */
    suspend fun check(wallet: Wallet, service: Service, presented: PublicKey): PublicKey {
        val pinned = service.mintPubkey
        if (pinned == null) {
            wallet.store.saveService(service.copy(mintPubkey = presented.toHex()))
            return presented
        }
        if (pinned != presented.toHex()) throw MintKeyChangedException(service.host)
        return presented
    }
}
