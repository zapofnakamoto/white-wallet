package whitewallet.lnurlcash

/** BOLT-11 human-readable amounts (`10n`, `210u`, ...), as carried in a `cs1` prefix. */
public object Bolt11Amount {
    private const val MSAT_PER_BTC = 100_000_000_000L
    private val multipliers = listOf('m' to 100_000_000L, 'u' to 100_000L, 'n' to 100L)
    private val pattern = Regex("^([1-9][0-9]*)([munp]?)$")

    /** Shortest encoding of [msat], which must be positive. */
    public fun encode(msat: Long): String {
        require(msat > 0) { "amount must be positive" }
        if (msat % MSAT_PER_BTC == 0L) return (msat / MSAT_PER_BTC).toString()
        for ((suffix, unit) in multipliers) {
            if (msat % unit == 0L) return "${msat / unit}$suffix"
        }
        // Ten pico-BTC per msat, written as text: msat * 10 overflows a Long above Long.MAX_VALUE / 10.
        return "${msat}0p"
    }

    /** Decodes an amount to msat, or null if it is malformed or not a whole number of msat. */
    public fun decode(value: String): Long? {
        val match = pattern.matchEntire(value) ?: return null
        val digits = match.groupValues[1]
        val suffix = match.groupValues[2]
        // A pico-BTC amount is a whole number of msat only if it ends in 0, which is dropped as text: its number may not fit a Long.
        if (suffix == "p") return if (digits.endsWith('0')) digits.dropLast(1).toLongOrNull() else null
        val number = digits.toLongOrNull() ?: return null
        // An amount too large for a Long is no amount.
        return try {
            if (suffix.isEmpty()) Math.multiplyExact(number, MSAT_PER_BTC)
            else Math.multiplyExact(number, multipliers.first { it.first == suffix[0] }.second)
        } catch (e: ArithmeticException) {
            null
        }
    }
}
