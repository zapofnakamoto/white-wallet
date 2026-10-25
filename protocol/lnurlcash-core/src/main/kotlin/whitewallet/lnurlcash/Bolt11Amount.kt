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
        return "${msat * 10}p"
    }

    /** Decodes an amount to msat, or null if it is malformed or not a whole number of msat. */
    public fun decode(value: String): Long? {
        val match = pattern.matchEntire(value) ?: return null
        val number = match.groupValues[1].toLongOrNull() ?: return null
        return when (val suffix = match.groupValues[2]) {
            "" -> Math.multiplyExact(number, MSAT_PER_BTC)
            "p" -> if (number % 10 == 0L) number / 10 else null
            else -> Math.multiplyExact(number, multipliers.first { it.first == suffix[0] }.second)
        }
    }
}
