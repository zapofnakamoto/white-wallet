package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.legacy.LegacyCertificates
import whitewallet.lnurlcash.spendDomain

/**
 * Whether [certificate] is the mint's signature over [q] at its amount: over the message bound to the mint's domain (LUD-25 at luds
 * 92e430f), or, from a mint on the legacy draft 2f17578, over the one without it (tag: mint). Never for a mint without a signing key.
 */
internal fun certifies(certificate: Cs1, host: String, q: ByteVector32, mintPubkey: PublicKey?): Boolean =
    mintPubkey != null &&
        (Certificates.verify(certificate, spendDomain(host), q, mintPubkey) || LegacyCertificates.verify(certificate, q, mintPubkey))

/**
 * The signing key [accountId]'s wallet pinned for [host]: what a mint's certificate is checked against, never the key an answer presents
 * (which may be one the user never accepted). Null if none is pinned.
 */
internal suspend fun Wallet.pinnedKey(accountId: String, host: String): PublicKey? =
    mintOf(accountId, host)?.mintPubkey?.let { runCatching { PublicKey.fromHex(it) }.getOrNull() }
