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
                "ReceiveSuccessPreview",
                "SendSuccessPreview",
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
