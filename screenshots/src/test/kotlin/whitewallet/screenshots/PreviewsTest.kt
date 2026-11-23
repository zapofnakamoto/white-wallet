package whitewallet.screenshots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewsTest {
    @Test
    fun findsEveryPreviewOfTheScreenModules() {
        assertEquals(
            setOf(
                "EmptyStatePreview",
                "EmptyStateWithActionPreview",
                "MintAddedFoundPreview",
                "MintAddedNonePreview",
                "ScanDoneFoundPreview",
                "ScanDoneNonePreview",
                "AccountCreatedFoundPreview",
                "AccountCreatedNonePreview",
                "AddressAddedPreview",
                "WalletReadyWithAddressPreview",
                "WalletReadyAddedPreview",
                "WalletReadyRestoredPreview",
                "ReceiveSuccessPreview",
                "SendSuccessPreview",
                "MintKeyAcceptedPreview",
                "NoteClaimedPreview",
                "NoteTakenBackPreview",
                "FundsMovedPreview",
                "MoneyMovedPreview",
                "HomeChangedMovedPreview",
                "HomeChangedNonePreview",
                "BackupDonePreview",
                // Settings
                "SettingsPreview",
                "WalletsPreview",
                "WalletPagePreview",
                "AccountSettingsPreview",
                "AccountNotInUsePreview",
                "MintsPreview",
                "MintDetailsPreview",
                "AccountMintsPreview",
                "AddressesPreview",
                "BackupPreview",
                "NotesSettingsPreview",
                "DeleteWalletPreview",
                "DerivedKeysPreview",
                "MoneyMovePreview",
                "DisplaySettingsPreview",
                "SecuritySettingsPreview",
                "AboutPreview",
                "DeveloperSettingsPreview",
                "LocalRegtestPreview",
                "RequestLogPreview",
                // Everyday screens of the other modules
                "WelcomePreview",
                "OnboardingPreview",
                "RestorePreview",
                "AddMintPreview",
                "ScanForNotesPreview",
                "AccountNamePreview",
                "HomePreview",
                "MoveFundsPreview",
                "NotesPreview",
                "ArchivePreview",
                "SendPreview",
                "CreateNotePreview",
                "SharedNotePreview",
                "QrScanPreview",
                "ReceivePreview",
                "ClaimNotePreview",
                // Problems and waits on the other screens
                "HomeOfflinePreview",
                "HomeUpdateFailedPreview",
                "HomeWorkingPreview",
                "HomeMintNotAnsweringPreview",
                "HomeKeyChangedPreview",
                "HomeUnknownNotesPreview",
                "HomeIncomingPreview",
                "HomeAddressInactivePreview",
                "MoveFundsFailedPreview",
                "MoveFundsMintProblemPreview",
                "MoveFundsPendingPreview",
                "MoveFundsPartlyMovedPreview",
                "MoveFundsPartialPreview",
                "NotesOfflinePreview",
                "NotesNoteProblemsPreview",
                "NotesMintPagePreview",
                "NotesSelectingPreview",
                "NotesOutcomePendingPreview",
                "NotesOutcomeFailedPreview",
                "NoteSheetUnknownPreview",
                "ArchiveRetryingPreview",
                "OnboardingNetworkErrorPreview",
                "OnboardingUsernameTakenPreview",
                "RestoreAlreadyInWalletPreview",
                "AddMintUnreachablePreview",
                "AddMintOfflinePreview",
                "AddMintKeyChangedPreview",
                "AddMintFailedPreview",
                "AddMintScanStoppedPreview",
                "ScanStoppedPreview",
                "ReceiveNoMintPreview",
                "ReceiveInvoicePreview",
                "ReceiveInvoiceExpiredPreview",
                "ReceiveInvoiceProblemPreview",
                "ReceiveInvoiceOfflinePreview",
                "ReceiveAddressMintSilentPreview",
                "ReceiveAddressKeyChangedPreview",
                "SendPendingPreview",
                "SendPendingInvoicePreview",
                "SendFailedPreview",
                "SendProblemPreview",
                "SendOfflinePreview",
                "SendQuoteProblemPreview",
                "SendKeyChangedPreview",
                "CreateNoteProblemPreview",
                "CreateNotePendingPreview",
                "CreateNoteOfflinePreview",
                "SharedNoteSpentPreview",
                "SharedNoteTakenBackEarlierPreview",
                "SharedNoteTakeBackPendingPreview",
                "SharedNoteUnverifiedPreview",
                "SharedNoteUnknownPreview",
                "SharedNoteTakeBackFailedPreview",
                "SharedNoteReclaimConfirmPreview",
                "SharedNoteNotFoundPreview",
                "MoneyMoveErrorPreview",
                "MoneyMoveOfflinePreview",
                "MoneyMovePendingPreview",
                "MoneyMoveFailedPreview",
                "BackupWrongWordPreview",
                "DerivedKeysRenewFailedPreview",
                "DerivedKeysLookupRefusedPreview",
                "DerivedKeysKeyChangedPreview",
                "MintDetailsUnreachablePreview",
                "MintDetailsKeyChangedPreview",
                "MintsKeyChangedPreview",
                "AddressesProblemPreview",
                "NotesSettingsCollectProblemPreview",
                "DeleteWalletUnresolvedPreview",
                // Every other way a claim or a take back ends
                "ClaimSpentPreview",
                "ClaimRetryingPreview",
                "ClaimRefusedPreview",
                "ClaimNothingReceivedPreview",
                "ClaimKeptPreview",
                "ClaimLockedPreview",
                "ClaimKeyChangedPreview",
                "ClaimLightningOnlyPreview",
                "ClaimPendingPreview",
                "TakeBackSpentPreview",
                "TakeBackFailedPreview",
                "TakeBackWhenKnownPreview",
            ),
            Previews.all.map { it.methodName }.toSet(),
        )
    }

    @Test
    fun noTwoModulesNameAPreviewAlike() {
        // Files are named by the preview function: two alike would draw over each other.
        Previews.all
            .groupBy { it.methodName }
            .forEach { (name, previews) -> assertEquals(name, 1, previews.map { it.declaringClass }.distinct().size) }
    }

    @Test
    fun everyPreviewComesInEachConfiguration() {
        Previews.all
            .groupBy { it.methodName }
            .forEach { (name, configurations) ->
                assertEquals(name, setOf("light", "dark", "large-font", "de"), configurations.map { it.previewInfo.name }.toSet())
            }
    }

    @Test
    fun theCheckSetIsLightPlusEveryConfigurationOfTheMarkedOnes() {
        val check = Previews.all.filter { Previews.selected(it, "check") }
        assertEquals(Previews.all.count { it.previewInfo.name == "light" } + 3 * 2, check.size)
        assertTrue(Previews.all.all { Previews.selected(it, "full") })
    }

    @Test
    fun aSuccessScreenIsAlsoCapturedWhileItMoves() {
        val scanDone = Previews.all.first { it.methodName == "ScanDoneFoundPreview" }
        val mintAdded = Previews.all.first { it.methodName == "MintAddedFoundPreview" }
        val empty = Previews.all.first { it.methodName == "EmptyStatePreview" }
        assertEquals(listOf(600L, 3_000L), Previews.frames(scanDone, "full"))
        assertEquals(listOf(600L, 3_000L), Previews.frames(mintAdded, "full"))
        assertEquals(listOf(3_000L), Previews.frames(empty, "full"))
        // The small set: only the marked success screen also while it moves.
        assertEquals(listOf(600L, 3_000L), Previews.frames(scanDone, "check"))
        assertEquals(listOf(3_000L), Previews.frames(mintAdded, "check"))
    }

    @Test
    fun fileNamesSayScreenConfigurationAndMoment() {
        val scanDone = Previews.all.first { it.methodName == "ScanDoneFoundPreview" && it.previewInfo.name == "de" }
        assertEquals("ScanDoneFound.de.600ms.png", Previews.fileName(scanDone, 600))
    }
}
