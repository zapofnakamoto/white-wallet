package whitewallet.core.wallet

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A mint as the user knows it: its host and the signing key it presents (lowercase hex, compressed). */
data class MintKey(val host: String, val pubkey: String)

/**
 * The user's trust choices, on this device and for all accounts. Trust always names a key: if a mint's key changes,
 * it is no longer trusted (and the wallet stops operating with it anyway, see [Pinning]).
 * [untrusted] records mints the user untrusted, although they are trusted by default (the home mint, the brand's).
 */
interface MintTrustStore {
    val trusted: Flow<Set<MintKey>>
    val untrusted: Flow<Set<MintKey>>
    suspend fun setTrusted(mint: MintKey, trusted: Boolean)

    /** Drops the user's choice for [mint] either way, as if it was never made. */
    suspend fun forget(mint: MintKey)

    class InMemory : MintTrustStore {
        override suspend fun forget(mint: MintKey) {
            trusted.update { it - mint }
            untrusted.update { it - mint }
        }

        override val trusted = MutableStateFlow(emptySet<MintKey>())
        override val untrusted = MutableStateFlow(emptySet<MintKey>())

        override suspend fun setTrusted(mint: MintKey, trusted: Boolean) {
            if (trusted) {
                this.trusted.update { it + mint }
                untrusted.update { it - mint }
            } else {
                this.trusted.update { it - mint }
                untrusted.update { it + mint }
            }
        }
    }
}

/** Why a mint is trusted without the user saying so. */
enum class TrustDefault {
    /** The home mint of one of this wallet's accounts. */
    Home,

    /** Shipped as trusted by the brand (`mints.trusted`), with this key. */
    Brand,
}

/** One mint as the device-wide Mints page shows it: nothing about any one account's money. */
data class MintInfo(
    val host: String,
    /** The pinned signing key; null if the wallet never got one (then it can't be trusted). */
    val pubkey: String?,
    val trusted: Boolean,
    /** Trusted unless the user says otherwise, and why; null if only the user's choice counts. */
    val trustedByDefault: TrustDefault?,
    /** The names of the accounts whose home mint it is. */
    val homeOf: List<String> = emptyList(),
    /** Nothing ties an account to it (see [Wallet.removeMint]), so it can be removed. */
    val removable: Boolean = false,
)

/** One of the active account's mints, as Account → Mints shows it: its home, or where it has money or addresses. */
data class AccountMint(
    val host: String,
    val isHome: Boolean,
    /** The account's money there, and in how many notes. */
    val balanceMsat: Long,
    val noteCount: Int,
    /** The account's Lightning addresses there. */
    val addresses: Int,
    val trusted: Boolean,
)

/** What [Wallet.removeMint] did. */
sealed interface RemoveMintResult {
    data object Removed : RemoveMintResult

    /** Still in use, by at least one account: nothing was removed. */
    data class InUse(val reason: MintUse) : RemoveMintResult
}

/** Why a mint can't be removed, checked in this order. */
enum class MintUse { Home, Notes, Address, OpenOperation }

/** The trust rule: the user's choice for this exact key, else the default (home mint, brand list) for it. */
internal fun isTrusted(mint: MintKey, default: TrustDefault?, trusted: Set<MintKey>, untrusted: Set<MintKey>): Boolean =
    mint in trusted || (default != null && mint !in untrusted)

object TrustedMints {
    /**
     * Parses the brand's `mints.trusted`: comma-separated `host:pubkey` (the key after the last colon, so a host may
     * carry a port). Empty means none. Throws on anything else, so a broken config fails the build's checks early.
     */
    fun parse(config: String): List<MintKey> = config.split(',').map(String::trim).filter(String::isNotEmpty).map { entry ->
        val host = entry.substringBeforeLast(':', "").trim().lowercase()
        val key = entry.substringAfterLast(':').trim().lowercase()
        require(host.isNotEmpty() && KEY.matches(key)) { "mints.trusted: '$entry' is not host:pubkey (66 hex characters, 02 or 03 first)" }
        MintKey(host, key)
    }

    private val KEY = Regex("0[23][0-9a-f]{64}")
}
