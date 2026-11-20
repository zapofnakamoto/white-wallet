package whitewallet.screenshots

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import whitewallet.core.designsystem.LocalAppName
import whitewallet.core.designsystem.WalletPreviews
import whitewallet.feature.onboarding.CreateState
import whitewallet.feature.onboarding.WelcomeScreen

// Screens that show the brand's own resources, which only this module (with the brand's res/) holds, not the screen's module.

/** The first screen, with the brand's logo, as MainActivity passes it. */
@WalletPreviews
@Composable
private fun WelcomePreview() =
    WelcomeScreen(
        LocalAppName.current,
        logo = { Image(painterResource(R.drawable.ic_brand_logo), contentDescription = null) },
        CreateState(),
        onCreate = {},
        onRestore = {},
    )
