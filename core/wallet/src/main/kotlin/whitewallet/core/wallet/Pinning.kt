package whitewallet.core.wallet

import fr.acinq.bitcoin.PublicKey
import whitewallet.core.model.Mint

/** Thrown when a mint presents a different `mintPubkey` than the one pinned on first contact. */
class MintKeyChangedException(val host: String) : Exception("the signing key of $host changed")

internal object Pinning {
    /** Pins [presented] for [mint] on first sight; afterward it must match. Returns the pinned key. */
    suspend fun check(wallet: Wallet, mint: Mint, presented: PublicKey): PublicKey {
        val pinned = mint.mintPubkey
        if (pinned == null) {
            wallet.store.saveMint(mint.copy(mintPubkey = presented.toHex()))
            return presented
        }
        if (pinned != presented.toHex()) throw MintKeyChangedException(mint.host)
        return presented
    }
}
