package whitewallet.core.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Why the account can't receive yet: its wallet has no home mint. */
enum class NoHome {
    /** Its wallet has no mint at all: add one. */
    NoMint,

    /** Its wallet has mints, but its home mint was removed: pick one of them. */
    PickHome,
}

/** What the active account lacks to receive, or null when it has a home mint (or there is no account). */
val Wallet.noHome: Flow<NoHome?>
    get() =
        combine(activeAccount, homeHost, hasMint) { active, home, hasMint ->
                when {
                    active == null || home != null -> null
                    hasMint -> NoHome.PickHome
                    else -> NoHome.NoMint
                }
            }
            .distinctUntilChanged()
