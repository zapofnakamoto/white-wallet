package whitewallet.core.model

/**
 * The accounts on the phone for the account switcher (`Wallet.accountChoice`): [activeId] is the one in use, [walletNames] the names the
 * user gave wallets (by wallet id; an unnamed one goes by its number), and [shown] whether the account's mark shows at all.
 */
data class AccountChoice(
    val accounts: List<AccountSummary>,
    val activeId: String?,
    val walletNames: Map<String, String>,
    val shown: Boolean,
) {
    val active: AccountSummary?
        get() = accounts.firstOrNull { it.account.id == activeId }

    /** How many wallets (recovery phrases) the accounts belong to. */
    val walletCount: Int
        get() = accounts.distinctBy { it.account.walletId }.size

    /**
     * The active account's wallet as the how-manieth, from 1, in the accounts' order: what an unnamed wallet is called by; 0 without one.
     */
    val activeWalletNumber: Int
        get() = accounts.map { it.account.walletId }.distinct().indexOf(active?.account?.walletId) + 1
}
