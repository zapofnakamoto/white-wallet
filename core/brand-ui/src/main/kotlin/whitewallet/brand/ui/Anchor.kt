package whitewallet.brand.ui

/**
 * A named place in the app where a brand may show its own content ([BrandDesign.Decoration]). New anchors may be
 * added in later releases; a brand's `when` needs an `else` branch that calls the default.
 */
@JvmInline
value class Anchor(val id: String) {
    companion object {
        /** Above the onboarding form. Default: the brand logo (`ic_brand_logo`). */
        val OnboardingHeader = Anchor("onboarding.header")

        /** The app's name on the onboarding screen. Default: the name as a headline. */
        val OnboardingTitle = Anchor("onboarding.title")

        /** Above the balance on the wallet tab. Default: nothing. */
        val HomeHeader = Anchor("home.header")

        /** The title of the wallet tab's top bar. Default: the app's name. */
        val HomeTitle = Anchor("home.title")

        /** Above the address or invoice on the Receive screen. Default: nothing. */
        val ReceiveHeader = Anchor("receive.header")

        /**
         * The picture on the screen that says a payment arrived (status [whitewallet.brand.WalletStatus.PaymentReceived],
         * with the amount so far; it grows when more arrives). Default: a check mark that draws itself.
         */
        val ReceiveSuccess = Anchor("receive.success")

        /** Above a payment once it is understood: its review, while it is paid, and an unfinished result. Default: nothing. */
        val SendHeader = Anchor("send.header")

        /**
         * The picture on the screen that says a payment went out (status [whitewallet.brand.WalletStatus.Sent]).
         * Default: a check mark that draws itself, as at [ReceiveSuccess].
         */
        val SendSuccess = Anchor("send.success")

        /** Above the result of claiming a note. Default: nothing. */
        val ClaimResult = Anchor("claim.result")

        /**
         * Above a handed-out note's amount: while it waits for its recipient (status
         * [whitewallet.brand.WalletStatus.WaitingForRecipient]), once redeemed ([whitewallet.brand.WalletStatus.Sent])
         * and once taken back ([whitewallet.brand.WalletStatus.Idle]). Default: nothing.
         */
        val NoteShared = Anchor("note.shared")

        /** On the app lock screen. Default: the brand logo. */
        val AppLock = Anchor("app.lock")

        /** Above the text of an empty history. Default: nothing. */
        val EmptyHistory = Anchor("history.empty")
    }
}
