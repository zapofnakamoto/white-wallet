package whitewallet.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import whitewallet.core.wallet.AppDefaults

class ServiceOptionsTest {
    private val defaults = AppDefaults("mint.example", allowServiceChange = true, localDevServices = listOf("localhost:8111", "localhost:8112"))

    @Test
    fun `every local mint is offered in developer mode, none otherwise`() {
        assertEquals(
            listOf(ServiceOption.Suggested("mint.example"), ServiceOption.LocalDev("localhost:8111"), ServiceOption.LocalDev("localhost:8112"), ServiceOption.Custom),
            serviceOptions(defaults, developerMode = true),
        )
        assertEquals(listOf(ServiceOption.Suggested("mint.example"), ServiceOption.Custom), serviceOptions(defaults, developerMode = false))
    }
}
