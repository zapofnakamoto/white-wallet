package whitewallet.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import whitewallet.core.designsystem.CheckAll
import whitewallet.core.designsystem.Moment
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.wallet.ScanProgress
import whitewallet.core.wallet.ScanResult

// The onboarding module's success screens, each in what it can say (docs/DEVELOPMENT.md, "Screenshots"). Made-up data only;
// every text is the screen's own.

private const val MINT = "mint.example"

/** A mint added: what the phrase held there, or nothing. */
internal val mintAddedStates =
    listOf(
        AddMintState(
            step = AddMintStep.Result,
            host = MINT,
            progress = ScanProgress(emptyList()),
            found = listOf(FoundAccount("", 21_000_000)),
        ),
        AddMintState(step = AddMintStep.Result, host = MINT, progress = ScanProgress(emptyList())),
    )

@Composable
private fun MintAdded(state: AddMintState) {
    // The account's name is the user's: here the one the app gives a first account.
    val main = stringResource(R.string.default_account_name)
    AddMintScreen(state.copy(found = state.found.map { it.copy(name = main) }), AddMintActions())
}

@WalletPreviews @Moment @Composable private fun MintAddedFoundPreview() = MintAdded(mintAddedStates[0])

@WalletPreviews @Moment @Composable private fun MintAddedNonePreview() = MintAdded(mintAddedStates[1])

/** A scan that got through: what it found, or nothing. */
internal val scanDoneStates =
    listOf(ScanState(result = ScanResult(foundMsat = 21_000_000, stoppedAt = emptyList())), ScanState(result = ScanResult(0, emptyList())))

@WalletPreviews @Moment @CheckAll @Composable private fun ScanDoneFoundPreview() = ScanScreen(scanDoneStates[0], onBack = {}, onScan = {})

@WalletPreviews @Moment @Composable private fun ScanDoneNonePreview() = ScanScreen(scanDoneStates[1], onBack = {}, onScan = {})

/** An account added: with what it already held, or empty. */
internal val accountCreatedStates =
    listOf(CreateState(created = true, accountId = "1", foundMsat = 21_000_000), CreateState(created = true, accountId = "1"))

@Composable
private fun AccountCreated(state: CreateState) {
    val name = stringResource(R.string.new_account_default_name, 2)
    AccountNameScreen(state.copy(createdName = name), defaultName = name, onBack = {}, onCreate = {})
}

@WalletPreviews @Moment @Composable private fun AccountCreatedFoundPreview() = AccountCreated(accountCreatedStates[0])

@WalletPreviews @Moment @Composable private fun AccountCreatedNonePreview() = AccountCreated(accountCreatedStates[1])

/** A Lightning address ready, from Settings. */
internal val addressAddedState =
    OnboardingState(
        username = "satoshi",
        host = MINT,
        canChangeHost = false,
        mode = OnboardingMode.AddAddress,
        claimed = Claimed("satoshi@$MINT", confirmed = true),
    )

@WalletPreviews
@Moment
@Composable
private fun AddressAddedPreview() =
    OnboardingScreen(
        addressAddedState,
        onBack = null,
        onUsernameChange = {},
        mint = MintActions({}, {}),
        onSuggestion = {},
        onStart = {},
        onContinue = {},
    )

/** A wallet's setup ended: the first wallet with its address, an added one without, and a restore with what it found. */
internal val walletReadyStates =
    listOf(
        WalletReadyState() to "satoshi@$MINT",
        WalletReadyState(number = 2, several = true) to null,
        WalletReadyState(foundMsat = 21_000_000, backedUp = true) to null,
    )

@Composable private fun WalletReady(state: Pair<WalletReadyState, String?>) = WalletReadyScreen(state.first, state.second, onOpen = {})

@WalletPreviews @Moment @Composable private fun WalletReadyWithAddressPreview() = WalletReady(walletReadyStates[0])

@WalletPreviews @Moment @Composable private fun WalletReadyAddedPreview() = WalletReady(walletReadyStates[1])

@WalletPreviews @Moment @Composable private fun WalletReadyRestoredPreview() = WalletReady(walletReadyStates[2])
