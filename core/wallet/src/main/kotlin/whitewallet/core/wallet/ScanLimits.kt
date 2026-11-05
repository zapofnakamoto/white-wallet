package whitewallet.core.wallet

/** How far the wallet looks for what a phrase used: the brand's `recovery.*` keys, or their environment overrides (docs/WHITE_LABEL.md). */
data class ScanLimits(
    /**
     * Unused keys in a row that end a scan of a chain or a share (`recovery.keys.gap`). Also how many keys an operation tries while the
     * mint says each is `already in use`, since keys it holds for another phone's unpaid invoices can follow each other.
     */
    val keyGap: Int = 20,
    /** Unused shares in a row that end a restore's look at an account (`recovery.shares.gap`). Each one costs [keyGap] lookups. */
    val shareGap: Int = 20,
    /** Shares past those handed out that an ownership check tries (`recovery.shares.ahead`): each is a PUT to the mint, on every check. */
    val sharesAhead: Int = 3,
    /**
     * Shares a registration tries while the mint says each was registered before, by another phone with the phrase
     * (`recovery.shares.attempts`).
     */
    val shareAttempts: Int = 5,
    /** Unused accounts in a row that end a restore (`recovery.accounts.gap`); 1 is the BIP-44 rule. */
    val accountGap: Int = 1,
    /** Past this a restore stops looking for further accounts, whatever the brand allows (`recovery.accounts.max`). */
    val maxAccounts: Int = 100,
) {
    init {
        require(listOf(keyGap, shareGap, shareAttempts, accountGap, maxAccounts).all { it >= 1 } && sharesAhead >= 0) {
            "scan limits: $this"
        }
    }
}
