package whitewallet.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.core.model.MintScan
import whitewallet.core.wallet.MintInfo
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
