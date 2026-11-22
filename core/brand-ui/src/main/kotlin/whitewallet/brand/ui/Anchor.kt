package whitewallet.brand.ui

/**
 * A named place in the app where a brand may show its own content ([BrandDesign.Decoration]). New anchors may be added in later releases; a
 * brand's `when` needs an `else` branch that calls the default.
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

        /**
         * The picture on the home screen's loading screen, shown instead of the balance, notes and history while the wallet works on
         * something that changes them for more than a moment (status [whitewallet.brand.WalletStatus.Working], with the task). The app says
         * what it does below it. Default: a spinner, filling up while the notes are checked.
         */
        val Loading = Anchor("home.loading")

        /**
         * The picture of a page that is still reading what it shows, centred on the otherwise empty page. It comes up only when that takes
         * more than a moment and then stays for a short time, so it never blinks; most pages are never seen loading. Default: a spinner.
         */
        val PageLoading = Anchor("page.loading")

        /** The title of the wallet tab's top bar. Default: the app's name. */
        val HomeTitle = Anchor("home.title")

        /** Above the address or invoice on the Receive screen. Default: nothing. */
        val ReceiveHeader = Anchor("receive.header")

        /**
         * The picture on the screen that says a payment arrived (status [whitewallet.brand.WalletStatus.PaymentReceived], with the amount
         * so far and how many payments it is; both grow when more arrives). Default: a check mark that draws itself.
         */
        val ReceiveSuccess = Anchor("receive.success")

        /** Above a payment once it is understood: its review, while it is paid, and an unfinished result. Default: nothing. */
        val SendHeader = Anchor("send.header")

        /**
         * The picture on the screen that says a payment went out (status [whitewallet.brand.WalletStatus.Sent]). Default: a check mark that
         * draws itself, as at [ReceiveSuccess].
         */
        val SendSuccess = Anchor("send.success")

        /**
         * The picture on the screen that says a mint was added, outside a wallet's setup (status [whitewallet.brand.WalletStatus.Done],
         * with what the wallet's phrase held there). Default: a check mark that draws itself, as at [ReceiveSuccess].
         */
        val MintAdded = Anchor("mint.added")

        /**
         * The picture on the screen that says a Lightning address is ready, outside a wallet's setup, once the app checked that it reaches
         * this wallet (status [whitewallet.brand.WalletStatus.Done], 0). Default: a check mark that draws itself, as at [ReceiveSuccess].
         */
        val AddressAdded = Anchor("address.added")

        /**
         * The picture on the screen that says an account was added (status [whitewallet.brand.WalletStatus.Done], with what it already
         * held). Default: a check mark that draws itself, as at [ReceiveSuccess].
         */
        val AccountCreated = Anchor("account.created")

        /**
         * The picture on the screen that ends a wallet's setup, the first wallet's or an added one's, created or restored (status
         * [whitewallet.brand.WalletStatus.Done], with what the wallet holds by then). It shows the address too where one was added in the
         * setup, in place of [AddressAdded]'s screen. Default: a check mark that draws itself, as at [ReceiveSuccess].
         */
        val WalletReady = Anchor("wallet.ready")

        /**
         * The picture on the screen that says a scan for notes got through (status [whitewallet.brand.WalletStatus.Done], with what it
         * found). Default: a check mark that draws itself, as at [ReceiveSuccess].
         */
        val ScanDone = Anchor("scan.done")

        /**
         * The picture on the screen that says the user now uses a mint's new signing key, where they accepted it while adding the mint
         * (status [whitewallet.brand.WalletStatus.Done], 0). On the mint's own page a short message says so instead. Default: a check mark
         * that draws itself, as at [ReceiveSuccess].
         */
        val MintKeyAccepted = Anchor("mint.key_accepted")

        /**
         * The picture on the screen that says a note was claimed, or a link received over Lightning (status
         * [whitewallet.brand.WalletStatus.PaymentReceived], with the amount). Default: a check mark that draws itself, as at
         * [ReceiveSuccess].
         */
        val NoteClaimed = Anchor("note.claimed")

        /**
         * The picture on the screen that says the user took back a note they had handed out, from its link (status
         * [whitewallet.brand.WalletStatus.Done], with the amount back). Default: a check mark that draws itself, as at [ReceiveSuccess].
         */
        val NoteTakenBack = Anchor("note.taken_back")

        /**
         * The picture on the screen that says money moved where the user asked, all of it: to another account, to another mint or home
         * (status [whitewallet.brand.WalletStatus.Done], with what arrived). A move that left some behind keeps its own screen. Default: a
         * check mark that draws itself, as at [ReceiveSuccess].
         */
        val MoneyMoved = Anchor("money.moved")

        /**
         * The picture on the screen that says the account has a new home mint (status [whitewallet.brand.WalletStatus.Done], with what
         * moved there along with it, 0 if nothing did). Default: a check mark that draws itself, as at [ReceiveSuccess].
         */
        val HomeChanged = Anchor("home.changed")

        /**
         * The picture on the screen that says the user wrote down their recovery phrase and checked it (status
         * [whitewallet.brand.WalletStatus.Done], 0). Default: a check mark that draws itself, as at [ReceiveSuccess].
         */
        val BackupDone = Anchor("backup.done")

        /**
         * Above the result of claiming a note, or taking one back, that isn't a success: kept for later, waiting, refused. Default:
         * nothing.
         */
        val ClaimResult = Anchor("claim.result")

        /**
         * Above a handed-out note's amount: while it waits for its recipient (status [whitewallet.brand.WalletStatus.WaitingForRecipient]),
         * once spent ([whitewallet.brand.WalletStatus.Sent]) and once taken back ([whitewallet.brand.WalletStatus.Idle]). Default: nothing.
         */
        val NoteShared = Anchor("note.shared")

        /**
         * Above the total of the notes view: status [whitewallet.brand.WalletStatus.Empty] without notes,
         * [whitewallet.brand.WalletStatus.WaitingForRecipient] while handed-out notes wait, else [whitewallet.brand.WalletStatus.Idle].
         * Default: nothing.
         */
        val NotesHeader = Anchor("notes.header")

        /** Above the text of an empty notes view. Default: nothing. */
        val NotesEmpty = Anchor("notes.empty")

        /** On the app lock screen. Default: the brand logo. */
        val AppLock = Anchor("app.lock")

        /** Above the text of an empty history. Default: nothing. */
        val EmptyHistory = Anchor("history.empty")
    }
}
