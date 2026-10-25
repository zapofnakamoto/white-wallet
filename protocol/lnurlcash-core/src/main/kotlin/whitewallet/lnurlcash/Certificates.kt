package whitewallet.lnurlcash

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey

/** `cs1` certificates: recoverable ECDSA by the service's `mintPubkey`, as LUD-13 signs. */
public object Certificates {
    public fun message(amountMsat: Long, q: ByteVector32): String = "LNURLcash:$amountMsat:${q.toHex()}"

    public fun digest(message: String): ByteArray =
        Crypto.sha256(Crypto.sha256("Lightning Signed Message:$message".encodeToByteArray()))

    /** True if [certificate] recovers to [mintPubkey] for note [q] at the certificate's own amount. */
    public fun verify(certificate: Cs1, q: ByteVector32, mintPubkey: PublicKey): Boolean {
        val sig = certificate.signature.toByteArray()
        val recid = sig[64].toInt()
        if (recid !in 0..3) return false
        return runCatching {
            Crypto.recoverPublicKey(ByteVector64(sig.copyOfRange(0, 64)), digest(message(certificate.amountMsat, q)), recid)
        }.getOrNull() == mintPubkey
    }

    /** Signs a certificate. Wallets never do this; it exists for the fake service used in tests. */
    public fun sign(amountMsat: Long, q: ByteVector32, mintKey: PrivateKey): Cs1 {
        val digest = digest(message(amountMsat, q))
        val compact = Crypto.sign(digest, mintKey)
        val recid = (0..3).first { runCatching { Crypto.recoverPublicKey(compact, digest, it) }.getOrNull() == mintKey.publicKey() }
        return Cs1(amountMsat, ByteVector(compact.toByteArray() + recid.toByte()))
    }
}
