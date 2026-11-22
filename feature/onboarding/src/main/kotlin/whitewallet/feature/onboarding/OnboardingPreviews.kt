package whitewallet.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.model.MintScan
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.MintPreview
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.ScanResult
import whitewallet.core.wallet.TrustDefault

// Setting up, restoring, adding a mint or an account and scanning, in their everyday state (docs/DEVELOPMENT.md, "Screenshots"). The
// welcome screen shows the brand's logo, which only the brand's resources hold: its preview is in the screenshots module. Made-up data
// only; every text is the screen's own.

private const val MINT = "mint.example"
private val mints = listOf(MintOption.Suggested(MINT), MintOption.Suggested("cash.example"), MintOption.Custom)

/** Picking a Lightning address on the first mint: the name is free. */
internal val onboardingState =
    OnboardingState(
        username = "satoshi",
        host = MINT,
        canChangeHost = true,
        mintOptions = mints,
        selectedMint = mints.first(),
        availability = Availability.Available,
    )

/** A phrase typed in full (BIP-39's own test words). */
internal val restoreState = RestoreState(phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow")

internal val addMintState = AddMintState(mintOptions = mints, selectedMint = mints.first(), host = MINT)

internal val scanState =
    ScanState(mints = listOf(MintInfo(MINT, "02a1b2c3", trusted = true, TrustDefault.Home, isHome = true, scan = MintScan.Never)))

@WalletPreviews
@Composable
private fun OnboardingPreview() =
    OnboardingScreen(
        onboardingState,
        onBack = null,
        onUsernameChange = {},
        mint = MintActions({}, {}),
        onSuggestion = {},
        onStart = {},
        onContinue = {},
    )

@WalletPreviews @Composable private fun RestorePreview() = RestoreScreen(restoreState, onBack = {}, onPhraseChange = {}, onRestore = {})

@WalletPreviews @Composable private fun AddMintPreview() = AddMintScreen(addMintState, AddMintActions())

@WalletPreviews @Composable private fun ScanForNotesPreview() = ScanScreen(scanState, onBack = {}, onScan = {})

@WalletPreviews
@Composable
private fun AccountNamePreview() =
    AccountNameScreen(CreateState(), defaultName = stringResource(R.string.new_account_default_name, 2), onBack = {}, onCreate = {})

/** The mint doesn't answer while the address is registered: the form with its error under it. */
@WalletPreviews
@Composable
private fun OnboardingNetworkErrorPreview() =
    OnboardingScreen(
        onboardingState.copy(error = OnboardingError.Network()),
        onBack = null,
        onUsernameChange = {},
        mint = MintActions({}, {}),
        onSuggestion = {},
        onStart = {},
        onContinue = {},
    )

/** The name is someone else's: the field says so, and offers other names. */
@WalletPreviews
@Composable
private fun OnboardingUsernameTakenPreview() =
    OnboardingScreen(
        onboardingState.copy(availability = Availability.Taken, suggestions = listOf("satoshi1", "satoshi21")),
        onBack = null,
        onUsernameChange = {},
        mint = MintActions({}, {}),
        onSuggestion = {},
        onStart = {},
        onContinue = {},
    )

/** The phrase is a wallet's already: the form says which account holds it. */
@WalletPreviews
@Composable
private fun RestoreAlreadyInWalletPreview() =
    RestoreScreen(
        restoreState.copy(error = RestoreError.AlreadyInWallet("Satoshi")),
        onBack = {},
        onPhraseChange = {},
        onRestore = {},
    )

/** The mint doesn't answer: the error stands under the picked mint. */
@WalletPreviews
@Composable
private fun AddMintUnreachablePreview() = AddMintScreen(addMintState.copy(error = AddMintError.Unreachable), AddMintActions())

/** The phone is offline: the pick says so before anything is tried. */
@WalletPreviews @Composable private fun AddMintOfflinePreview() = AddMintScreen(addMintState.copy(offline = true), AddMintActions())

/** The mint answers with another signing key than the wallet pinned: the key card asks what to do. */
@WalletPreviews
@Composable
private fun AddMintKeyChangedPreview() =
    AddMintScreen(
        addMintState.copy(
            step = AddMintStep.Info,
            preview =
                MintPreview.Ready(
                    host = MINT,
                    description = null,
                    feeBaseMsat = 1_000,
                    feePpm = 1_000,
                    minSendableMsat = 1_000,
                    maxSendableMsat = 1_000_000_000,
                    addressFeeMsat = null,
                    signingKey = "03d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5",
                    keyAnswered = true,
                    knownKey = "02a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
                ),
        ),
        AddMintActions(),
    )

/** Adding the mint failed, nothing was added: again, or back out. */
@WalletPreviews
@Composable
private fun AddMintFailedPreview() =
    AddMintScreen(addMintState.copy(step = AddMintStep.Result, failed = OnboardResult.NetworkError("no answer")), AddMintActions())

/** The mint was added but its scan stopped: again right here, or later. */
@WalletPreviews
@Composable
private fun AddMintScanStoppedPreview() =
    AddMintScreen(
        addMintState.copy(step = AddMintStep.Result, failed = OnboardResult.NetworkError("no answer"), kept = true),
        AddMintActions(),
    )

/** A scan stopped at a mint: what it found until then, that it stopped, and the mint saying so in its row. */
@WalletPreviews
@Composable
private fun ScanStoppedPreview() =
    ScanScreen(
        ScanState(
            mints = listOf(MintInfo(MINT, "02a1b2c3", trusted = true, TrustDefault.Home, isHome = true, scan = MintScan.Unfinished)),
            result = ScanResult(foundMsat = 4_000_000, stoppedAt = listOf(MINT)),
        ),
        onBack = {},
        onScan = {},
    )
