package whitewallet.brand.ui

/**
 * A page of the app whose body a brand may wrap ([BrandDesign.Body]). New pages may be added in later releases; a brand's `when` needs an
 * `else` branch that calls the default.
 */
@JvmInline
value class Page(val id: String) {
    companion object {
        /** The history tab: status [whitewallet.brand.WalletStatus.Empty] without any entry, else [whitewallet.brand.WalletStatus.Idle]. */
        val History = Page("history")

        /**
         * The notes tab: status [whitewallet.brand.WalletStatus.Empty] without notes, [whitewallet.brand.WalletStatus.WaitingForRecipient]
         * while handed-out notes wait, else [whitewallet.brand.WalletStatus.Idle].
         */
        val Notes = Page("notes")

        /** The archive of received notes: [whitewallet.brand.WalletStatus.Empty] with none, else [whitewallet.brand.WalletStatus.Idle]. */
        val Archive = Page("archive")

        /**
         * Receive: [whitewallet.brand.WalletStatus.Empty] while nothing can be received (no mint, or no home mint), else what the receive
         * header's status is (waiting for a payment, or a problem).
         */
        val Receive = Page("receive")

        /** The wallet's mints (Settings): [whitewallet.brand.WalletStatus.Empty] with none, else [whitewallet.brand.WalletStatus.Idle]. */
        val Mints = Page("mints")

        /** One account's mints: [whitewallet.brand.WalletStatus.Empty] with none, else [whitewallet.brand.WalletStatus.Idle]. */
        val AccountMints = Page("account.mints")

        /**
         * The account's Lightning addresses: [whitewallet.brand.WalletStatus.Empty] with none, else [whitewallet.brand.WalletStatus.Idle].
         */
        val Addresses = Page("addresses")

        /**
         * Changing the home mint and moving money to another mint: [whitewallet.brand.WalletStatus.Empty] while there is no mint to pick,
         * else [whitewallet.brand.WalletStatus.Idle].
         */
        val MoneyMove = Page("money.move")
    }
}
