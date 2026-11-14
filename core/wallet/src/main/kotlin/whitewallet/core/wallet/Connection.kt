package whitewallet.core.wallet

/** The phone's network connection, as the system knows it (see [Wallet.connection]). */
enum class Connection {
    /** No network that could reach the internet: nothing can be asked, and the screens say so. */
    Offline,

    /**
     * A network the system hasn't found to reach the internet (a hotel's login page, for one, or a network that blocks the system's check):
     * requests are tried, but a mint that doesn't answer over it says nothing about the mint.
     */
    Unverified,

    /** A network the system found to reach the internet: a mint that doesn't answer over it is told as such. */
    Online,
}
