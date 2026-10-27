package whitewallet.core.wallet

import java.security.MessageDigest
import whitewallet.lnurlcash.CashBranch

/**
 * The seed-derived default address. A mint maps names to keys but has no lookup from a key to its names, so a
 * restored wallet can't ask which addresses it had. Instead, the name a new account suggests first is derived from
 * its recovery phrase and the mint (a few candidates, in case one is taken). Restoring tries these names and
 * picks up the one that is still registered to the wallet; any other address has to be written down at backup.
 */
internal object DefaultAddress {
    const val CANDIDATES = 3

    /** The derived names for [branch]'s mint, in the order onboarding offers them. */
    fun names(branch: CashBranch): List<String> = (0 until CANDIDATES).map { k ->
        UsernameSuggestions.derived(MessageDigest.getInstance("SHA-256").digest("white-wallet/default-address/$k/${branch.cx1()}".encodeToByteArray()))
    }
}
