package whitewallet.brand.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable

/**
 * A page of the brand's own (a tutorial, an about page), reached from [entry]. Register one in the brand's code module
 * (Hilt `@IntoSet`). Its content has no access to the wallet; it can read [LocalWalletStatus] and [LocalWalletEvents].
 */
interface BrandScreen {
    /** Stable and unique: lowercase letters, digits and `-`. Part of the screen's route. */
    val id: String

    /** String resource: the page's name, as the entry that opens it shows it. */
    @get:StringRes
    val title: Int

    val entry: BrandScreenEntry

    /** Drawable resource: the mark of its row in Settings, beside the other pages' ones. Null: a generic page icon. */
    @get:DrawableRes
    val icon: Int? get() = null

    /** The whole page, including its top bar; [onBack] leaves it. */
    @Composable
    fun Content(onBack: () -> Unit)
}

enum class BrandScreenEntry {
    /** A row in Settings, above About. */
    Settings,

    /** Shown once, right after setup, before the wallet. Leaving it opens the wallet. */
    AfterOnboarding,

    /** An item in the wallet's top bar menu. */
    HomeMenu,
}
