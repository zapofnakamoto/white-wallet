package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import whitewallet.core.model.Mint
import whitewallet.lnurlcash.protocol.InvalidMintPubkey
import whitewallet.lnurlcash.protocol.NoteInfo
import whitewallet.lnurlcash.protocol.NoteLookupError
import whitewallet.lnurlcash.protocol.ProtocolViolation

/** Thrown when a mint presents a different `mintPubkey` than the one pinned on first contact. */
class MintKeyChangedException(val host: String) : Exception("the signing key of $host changed")

/**
 * A mint whose signing key was compared with the one its wallet pinned, and matched ([Wallet.keyMatches]): the only kind a note is looked
 * up at. Most answers to a lookup carry no key (a note in use, one the mint doesn't know), so the key is compared before asking, not after.
 */
internal class KeyCheckedMint private constructor(val mint: Mint) {
    val host: String
        get() = mint.host

    val withdrawUrl: String
        get() = mint.withdrawUrl

    companion object {
        /** [mint], once its signing key matches the pinned one (or none is pinned yet); null if it doesn't. */
        suspend fun of(wallet: Wallet, mint: Mint): KeyCheckedMint? =
            if (wallet.keyMatches(mint.walletId, mint.host)) KeyCheckedMint(mint) else null
    }
}

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
        val changed = presented == null || pinned != presented.toHex()
        wallet.sawKey(mint.walletId, mint.host, changed)
        if (changed) throw MintKeyChangedException(mint.host)
        return checkNotNull(presented)
    }

    /**
     * [mint]'s answer had a `mintPubkey` that isn't a key ([e]): no key at all, as [check] takes it. A changed key once pinned; before, the
     * answer stays invalid, and nothing is pinned.
     */
    fun invalidKey(mint: Mint, e: InvalidMintPubkey): Nothing = throw if (mint.mintPubkey != null) MintKeyChangedException(mint.host) else e

    /** Looks [q] up at [mint] by its key alone, and [check]s the key the answer presents: one that isn't a key is none ([invalidKey]). */
    suspend fun lookup(wallet: Wallet, mint: KeyCheckedMint, q: ByteVector32): NoteInfo {
        val info =
            try {
                // key-checked: a KeyCheckedMint.
                wallet.client.lookup(mint.withdrawUrl, q)
            } catch (e: InvalidMintPubkey) {
                invalidKey(mint.mint, e)
            } catch (e: NoteLookupError) {
                // Answered 0: it ends a note only from a mint whose key matches.
                if (e.burnedOrPending) check(wallet, mint.mint, e.mintPubkey)
                throw e
            }
        check(wallet, mint.mint, info.mintPubkey)
        return info
    }

    /**
     * Looks [q] up at [withdrawUrl] to settle an open operation of [accountId] on [host]. A mint the account's wallet pinned a key for is
     * asked only while it matches ([MintKeyChangedException] otherwise), at [withdrawUrl], so a mint whose key doesn't match settles
     * nothing.
     */
    suspend fun settling(wallet: Wallet, accountId: String, host: String, withdrawUrl: String, q: ByteVector32): NoteInfo {
        val mint = wallet.mintOf(accountId, host)?.takeIf { it.mintPubkey != null }
        // key-checked: no key is pinned for this host, so there is none to compare.
        if (mint == null) return wallet.client.lookup(withdrawUrl, q)
        val checked = KeyCheckedMint.of(wallet, mint.copy(withdrawUrl = withdrawUrl)) ?: throw MintKeyChangedException(host)
        return lookup(wallet, checked, q)
    }
}
