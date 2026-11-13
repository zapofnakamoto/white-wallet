package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import whitewallet.core.model.Mint
import whitewallet.lnurlcash.protocol.InvalidMintPubkey
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.ProtocolViolation

/** Thrown when a mint presents a different `mintPubkey` than the one pinned on first contact. */
class MintKeyChangedException(val host: String) : Exception("the signing key of $host changed")

internal object Pinning {
    /**
     * Pins [presented] for [mint] on first sight; afterward it must match. Returns the pinned key. The wallet uses no mint without a
     * signing key: a missing one (see `NoteInfo.mintPubkey`) is a changed key once pinned, and a protocol violation before.
     */
    suspend fun check(wallet: Wallet, mint: Mint, presented: PublicKey?): PublicKey {
        val pinned = mint.mintPubkey
        if (pinned == null) {
            if (presented == null) throw ProtocolViolation("withdrawRequest has no valid mintPubkey")
            wallet.store.saveMint(mint.copy(mintPubkey = presented.toHex()))
            return presented
        }
        if (presented == null || pinned != presented.toHex()) throw MintKeyChangedException(mint.host)
        return presented
    }

    /**
     * [mint]'s answer had a `mintPubkey` that isn't a key ([e]): no key at all, as [check] takes it. A changed key once pinned; before, the
     * answer stays invalid, and nothing is pinned.
     */
    fun invalidKey(mint: Mint, e: InvalidMintPubkey): Nothing = throw if (mint.mintPubkey != null) MintKeyChangedException(mint.host) else e

    /** Looks [q] up at [mint] by its key alone, and [check]s the key the answer presents: one that isn't a key is none ([invalidKey]). */
    suspend fun lookup(wallet: Wallet, mint: Mint, q: ByteVector32): NoteInfo {
        val info =
            try {
                wallet.client.lookup(mint.withdrawUrl, q)
            } catch (e: InvalidMintPubkey) {
                invalidKey(mint, e)
            }
        check(wallet, mint, info.mintPubkey)
        return info
    }
}
