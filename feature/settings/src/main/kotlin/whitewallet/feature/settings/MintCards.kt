package whitewallet.feature.settings

import whitewallet.core.wallet.AccountMint
import whitewallet.core.wallet.MintInfo

/**
 * One mint of the wallet in use as the Mints page shows it, with the account in use's money there. [trusted] never while its key doesn't
 * match ([keyChanged]); [answering] it answered the last time the wallet asked, or wasn't asked.
 */
data class MintCard(
    val host: String,
    val isHome: Boolean,
    val balanceMsat: Long,
    val noteCount: Int,
    val addresses: Int,
    val trusted: Boolean,
    val keyChanged: Boolean,
    val answering: Boolean,
) {
    /** The account uses it: its home, or money, notes or an address there. Otherwise the card says it isn't used in this account. */
    val used: Boolean
        get() = isHome || balanceMsat > 0 || noteCount > 0 || addresses > 0
}

/**
 * Every mint of the wallet ([mints]) and every one the account holds something on ([held]), once each: the home first, then the mints with
 * money, most first, then the rest; by host where that leaves a tie. Trust is the wallet's; money, notes and addresses the account's.
 */
fun mintCards(mints: List<MintInfo>, held: List<AccountMint>, keyChanged: Set<String>): List<MintCard> {
    val wallet = mints.associateBy { it.host }
    val account = held.associateBy { it.host }
    return (mints.map { it.host } + held.map { it.host })
        .distinct()
        .map { host ->
            val info = wallet[host]
            val mine = account[host]
            val changed = host in keyChanged
            MintCard(
                host,
                isHome = mine?.isHome ?: info?.isHome ?: false,
                balanceMsat = mine?.balanceMsat ?: 0,
                noteCount = mine?.noteCount ?: 0,
                addresses = mine?.addresses ?: 0,
                trusted = !changed && info?.trusted == true,
                keyChanged = changed,
                answering = mine?.answering ?: true,
            )
        }
        .sortedWith(compareBy<MintCard>({ !it.isHome }, { -it.balanceMsat }, { it.host }))
}
