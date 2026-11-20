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
            ),
            Previews.all.map { it.methodName }.toSet(),
        )
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
        val empty = Previews.all.first { it.methodName == "EmptyStatePreview" }
        assertEquals(listOf(600L, 3_000L), Previews.frames(scanDone))
        assertEquals(listOf(3_000L), Previews.frames(empty))
    }

    @Test
    fun fileNamesSayScreenConfigurationAndMoment() {
        val scanDone = Previews.all.first { it.methodName == "ScanDoneFoundPreview" && it.previewInfo.name == "de" }
        assertEquals("ScanDoneFound.de.600ms.png", Previews.fileName(scanDone, 600))
    }
}
