package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.legacy.LegacyCertificates
import whitewallet.lnurlcash.spendDomain

/**
 * Whether [certificate] is the mint's signature over [q] at its amount: over the message bound to the mint's domain (LUD-25 at luds
 * 92e430f), or, from a mint on the legacy draft 2f17578, over the one without it (tag: mint).
 */
internal fun certifies(certificate: Cs1, host: String, q: ByteVector32, mintPubkey: PublicKey): Boolean =
    Certificates.verify(certificate, spendDomain(host), q, mintPubkey) || LegacyCertificates.verify(certificate, q, mintPubkey)
