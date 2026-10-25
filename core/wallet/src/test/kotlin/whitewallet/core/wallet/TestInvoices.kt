package whitewallet.core.wallet

import fr.acinq.bitcoin.Bech32
import fr.acinq.secp256k1.Hex

/**
 * Builds BOLT-11 invoices for tests: well-formed with a valid checksum, but with an all-zero signature, so nothing
 * can ever pay one. Enough for the wallet, which reads the amount, expiry and description hash and leaves the
 * signature to the service.
 */
object TestInvoices {
    fun invoice(
        amountMsat: Long?,
        description: String? = "test",
        descriptionHashHex: String? = null,
        timestampSeconds: Long = System.currentTimeMillis() / 1000,
        expirySeconds: Long? = null,
    ): String {
        val words = mutableListOf<Byte>()
        words += (0 until 7).map { ((timestampSeconds shr (5 * (6 - it))) and 31).toByte() }
        fun tag(type: Int, data: List<Byte>) {
            words += type.toByte()
            words += (data.size / 32).toByte()
            words += (data.size % 32).toByte()
            words += data
        }
        tag(1, Bech32.eight2five(ByteArray(32) { 7 }).toList())
        description?.let { tag(13, Bech32.eight2five(it.encodeToByteArray()).toList()) }
        descriptionHashHex?.let { tag(23, Bech32.eight2five(Hex.decode(it)).toList()) }
        expirySeconds?.let { e -> tag(6, generateSequence(e) { it / 32 }.takeWhile { it > 0 }.map { (it % 32).toByte() }.toList().reversed()) }
        words += List(104) { 0.toByte() }
        return Bech32.encode("lnbcrt" + (amountMsat?.let(::hrpAmount) ?: ""), words.toTypedArray(), Bech32.Encoding.Bech32)
    }

    private fun hrpAmount(msat: Long): String = when {
        msat % 100_000 == 0L -> "${msat / 100_000}u"
        msat % 100 == 0L -> "${msat / 100}n"
        else -> "${msat * 10}p"
    }
}
