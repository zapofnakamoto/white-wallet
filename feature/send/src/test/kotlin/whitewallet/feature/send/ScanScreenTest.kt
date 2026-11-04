package whitewallet.feature.send

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import whitewallet.core.wallet.IncomingLink

@RunWith(RobolectricTestRunner::class)
class ScanScreenTest {
    @get:Rule val compose = createComposeRule()

    private val scanned = mutableListOf<IncomingLink>()

    /** A scanner that "sees" [text] when its button is tapped. */
    private fun show(text: String) = compose.setContent {
        ScanScreen(
            onBack = {},
            onScanned = { scanned += it },
            scanner = { modifier, onScanned ->
                Box(modifier) { Button(onClick = { onScanned(text) }) { Text("see code") } }
            },
        )
    }

    @Test
    fun aNoteGoesToClaim() {
        show("lnurlw://mint.example/w?k1=" + "ab".repeat(32))
        compose.onNodeWithText("see code").performClick()
        assertTrue(scanned.single() is IncomingLink.Claim)
    }

    @Test
    fun anAddressGoesToSend() {
        show("alice@mint.example")
        compose.onNodeWithText("see code").performClick()
        assertEquals(IncomingLink.Pay("alice@mint.example"), scanned.single())
    }

    @Test
    fun anythingElseSaysSoAndGoesNowhere() {
        show("https://example.com/menu")
        compose.onNodeWithText(text(R.string.scan_unknown)).assertDoesNotExist()
        compose.onNodeWithText("see code").performClick()
        compose.onNodeWithText(text(R.string.scan_unknown)).assertExists()
        assertEquals(emptyList<IncomingLink>(), scanned)
    }
}
