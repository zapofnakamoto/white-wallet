package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey

/**
 * `cs1` certificates: recoverable ECDSA by the service's `mintPubkey`, as LUD-13 signs, over the mint's domain, the amount and `Q`, so a
 * certificate never vouches for a note at another mint.
 */
public object Certificates {
    public fun message(domain: String, amountMsat: Long, q: ByteVector32): String = "LNURLcash:$domain:$amountMsat:${q.toHex()}"

    public fun digest(message: String): ByteArray = Crypto.sha256(Crypto.sha256("Lightning Signed Message:$message".encodeToByteArray()))

    /** True if [certificate] recovers to [mintPubkey] for note [q] at [domain] and the certificate's own amount. */
    public fun verify(certificate: Cs1, domain: String, q: ByteVector32, mintPubkey: PublicKey): Boolean =
        verifyMessage(certificate, message(domain, certificate.amountMsat, q), mintPubkey)

    /** Signs a certificate. Wallets never do this; it exists for the fake service used in tests. */
    public fun sign(domain: String, amountMsat: Long, q: ByteVector32, mintKey: PrivateKey): Cs1 =
        signMessage(amountMsat, message(domain, amountMsat, q), mintKey)

    internal fun verifyMessage(certificate: Cs1, message: String, mintPubkey: PublicKey): Boolean {
        val sig = certificate.signature.toByteArray()
        val recid = sig[64].toInt()
        if (recid !in 0..3) return false
        return runCatching { Crypto.recoverPublicKey(ByteVector64(sig.copyOfRange(0, 64)), digest(message), recid) }.getOrNull() ==
            mintPubkey
    }

    internal fun signMessage(amountMsat: Long, message: String, mintKey: PrivateKey): Cs1 {
        val digest = digest(message)
        val compact = Crypto.sign(digest, mintKey)
        val recid = (0..3).first { runCatching { Crypto.recoverPublicKey(compact, digest, it) }.getOrNull() == mintKey.publicKey() }
        return Cs1(amountMsat, ByteVector(compact.toByteArray() + recid.toByte()))
    }
}
