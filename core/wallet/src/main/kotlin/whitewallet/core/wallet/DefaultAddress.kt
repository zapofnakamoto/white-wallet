package whitewallet.core.wallet

import java.security.MessageDigest

/**
 * The seed-derived default address. A mint maps names to keys but has no lookup from a key to its names, so a restored wallet can't ask
 * which addresses it had. Instead, the name a new account suggests first is derived from its recovery phrase and the mint (a few
 * candidates, in case one is taken). Restoring tries these names and picks up the one that is still registered to the wallet; any other
 * address has to be written down at backup.
 */
internal object DefaultAddress {
    const val CANDIDATES = 3

    /** The derived names for [keys]' mint: of the account's first share, so each account of a wallet has its own. */
    fun names(keys: AccountKeys): List<String> = names(keys.share(0).cx1())

    /** The derived names for a `cx1`, in the order onboarding offers them. */
    fun names(cx1: String): List<String> =
        (0 until CANDIDATES).map { k ->
            UsernameSuggestions.derived(
                MessageDigest.getInstance("SHA-256").digest("white-wallet/default-address/$k/$cx1".encodeToByteArray())
            )
        }
}
