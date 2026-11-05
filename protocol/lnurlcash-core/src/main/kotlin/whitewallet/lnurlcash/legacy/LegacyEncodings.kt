package whitewallet.lnurlcash.legacy

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.byteVector32
import whitewallet.lnurlcash.Bech32m
import whitewallet.lnurlcash.Certificates
import whitewallet.lnurlcash.Cs1
import whitewallet.lnurlcash.isValidXOnly

/*
 * Legacy: LUD-25/26 draft 2f17578. Removable once every mint the wallet uses runs LUD-26 at 92e430f or later (tag: mint), except
 * LegacyCx1, which LegacyBranch needs (tag: keys); see protocol/SPEC.md, "Removing legacy 2f17578".
 */

/** Legacy 2f17578 `cx1<x(P) || chaincode>`: a watch-only note branch, 64 bytes. */
public object LegacyCx1 {
    private const val HRP = "cx"

    public fun encode(pubkey: ByteVector32, chainCode: ByteVector32): String =
        Bech32m.encode(HRP, pubkey.toByteArray() + chainCode.toByteArray())

    public fun decode(value: String): Pair<ByteVector32, ByteVector32>? {
        val (hrp, data) = Bech32m.decode(value) ?: return null
        if (hrp != HRP || data.size != 64) return null
        val pubkey = data.copyOfRange(0, 32).byteVector32()
        if (!isValidXOnly(pubkey)) return null
        return pubkey to data.copyOfRange(32, 64).byteVector32()
    }
}

/** Legacy 2f17578 `cs1` certificates, over a message without the mint's domain. */
public object LegacyCertificates {
    public fun message(amountMsat: Long, q: ByteVector32): String = "LNURLcash:$amountMsat:${q.toHex()}"

    public fun verify(certificate: Cs1, q: ByteVector32, mintPubkey: PublicKey): Boolean =
        Certificates.verifyMessage(certificate, message(certificate.amountMsat, q), mintPubkey)

    /** Signs a certificate. Wallets never do this; it exists for the fake service used in tests. */
    public fun sign(amountMsat: Long, q: ByteVector32, mintKey: PrivateKey): Cs1 =
        Certificates.signMessage(amountMsat, message(amountMsat, q), mintKey)
}
