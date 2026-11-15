package whitewallet.core.wallet

import whitewallet.core.model.Account
import whitewallet.core.model.AccountSummary

/**
 * An account to receive money into before the wallet adds another ([Wallet.accountToUseFirst]). [hidesLater]: an account after it holds
 * money already, which a restore from the recovery phrase alone doesn't find until this one has received some.
 */
data class UnusedAccount(val account: Account, val hidesLater: Boolean)

/**
 * BIP-44's account rule, for a restore that stops after [gap] unused accounts in a row (`scan.stop.after.unused.accounts`): an account is
 * used once it holds or held a note ([AccountSummary.used]). The next account takes the lowest free index, and may be added only while
 * fewer than [gap] accounts right before it are unused, or a restore would stop before it; with a gap of 1, the one before it must be used.
 * A wallet that has [gap] unused accounts in a row with a used one after them (made before this rule) is told first, by the last of the
 * first such run: a restore misses every account after it until it is used. Null while an account may be added. [accounts]: one wallet's.
 */
internal fun accountToUseFirst(accounts: List<AccountSummary>, gap: Int): UnusedAccount? {
    val byIndex = accounts.associateBy { it.account.index }
    var run = 0
    var last: Account? = null
    for (index in 0..(byIndex.keys.maxOrNull() ?: -1)) {
        // An index no account has here (one removed from this phone) says nothing about its keys: it neither starts nor ends a run.
        val summary = byIndex[index]
        when {
            summary == null -> run = 0
            !summary.used -> {
                run++
                last = summary.account
            }
            run >= gap -> return UnusedAccount(checkNotNull(last), hidesLater = true)
            else -> run = 0
        }
    }
    val next = generateSequence(0L) { it + 1 }.first { it !in byIndex }
    if (next < gap) return null
    val before = (next - gap until next).map { checkNotNull(byIndex[it]) }
    return if (before.none { it.used }) UnusedAccount(before.last().account, hidesLater = false) else null
}
