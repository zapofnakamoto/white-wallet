package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import java.util.concurrent.ConcurrentHashMap
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.wallet.legacy.LegacyKeys
import whitewallet.lnurlcash.NoteAccount
import whitewallet.lnurlcash.Share
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.spendDomain

/**
 * The keys one index counts: an account's LUD-26 wallet or change chain (`S/a'/0`, `S/a'/1`), one of its shares (`S/a'/2/n'`), or a purpose
 * of the legacy 2f17578 branch. Under both schemes purpose 0 is the wallet, 1 change and 2 Lightning addresses.
 */
data class KeySet(val scheme: KeyScheme, val purpose: Int, val share: Long? = null) {
    init {
        require(purpose in 0..2)
        require((share != null) == (scheme == KeyScheme.Bip32 && purpose == 2)) { "a share, and only a share, has a share index" }
    }

    /** Notes paid to a Lightning address. */
    val isAddress: Boolean
        get() = purpose == ADDRESS

    /** The account's own counter on the mint's domain, for the wallet and change chains; shares and legacy keys have none. */
    internal val counter: KeyCounter?
        get() =
            when {
                scheme != KeyScheme.Bip32 -> null
                purpose == WALLET -> KeyCounter.Wallet
                purpose == CHANGE -> KeyCounter.Change
                else -> null
            }

    fun key(accountId: String, host: String, index: Long) = KeyId(accountId, host, purpose, index, scheme, share)

    companion object {
        private const val WALLET = 0
        private const val CHANGE = 1
        private const val ADDRESS = 2

        val Wallet = KeySet(KeyScheme.Bip32, WALLET)
        val Change = KeySet(KeyScheme.Bip32, CHANGE)

        fun share(n: Long) = KeySet(KeyScheme.Bip32, ADDRESS, n)

        /** Where a note's key is. */
        fun of(note: Note): KeySet = KeySet(note.scheme, checkNotNull(note.purpose) { "not a key-path note" }, note.share)
    }
}

/** A note paid to one of the account's Lightning addresses, under either scheme. */
internal val Note.isAddressNote: Boolean
    get() = kind == NoteKind.KeyPath && purpose == 2

val KeyId.set: KeySet
    get() = KeySet(scheme, purpose, share)

/**
 * One account's keys at one mint: account `a` of its wallet's LUD-26 root for the mint's domain, and the legacy 2f17578 branch for the host
 * as stored (tag: keys). Holds secret keys: kept for the life of the process, like the branch was, never written anywhere.
 */
internal class AccountKeys(private val account: NoteAccount, val legacy: LegacyKeys) {
    private val shares = ConcurrentHashMap<Long, Share>()

    fun share(n: Long): Share = shares.getOrPut(n) { account.share(n) }

    fun pubkey(set: KeySet, index: Long): ByteVector32 =
        when (set.scheme) {
            KeyScheme.Bip32 ->
                if (set.isAddress) share(checkNotNull(set.share)).notePubkey(index) else secretKey(set, index).xOnlyPublicKey().value
            KeyScheme.Legacy -> legacy.pubkey(set, index)
        }

    fun pubkey(id: KeyId): ByteVector32 = pubkey(id.set, id.index)

    /** The key-path spend of [note] for [domain]. */
    fun spend(note: Note, domain: String): Spend.KeyPath {
        val set = KeySet.of(note)
        val index = checkNotNull(note.index) { "not a key-path note" }
        return when (set.scheme) {
            KeyScheme.Bip32 -> Spend.keyPath(secretKey(set, index), domain)
            KeyScheme.Legacy -> legacy.spend(set, index, domain)
        }
    }

    private fun secretKey(set: KeySet, index: Long): PrivateKey =
        when {
            set.isAddress -> share(checkNotNull(set.share)).noteSecretKey(index)
            set.purpose == 0 -> account.walletKey(index)
            else -> account.changeKey(index)
        }
}

/**
 * Where [set]'s index stands: the next key of the account's wallet or change counter on the mint's domain, or the first key the next scan
 * of a share or of a legacy purpose looks up. Null if a counter was never set (the account has not used that mint domain yet).
 */
internal suspend fun WalletStore.indexOrNull(accountId: String, host: String, set: KeySet): Long? {
    set.counter?.let {
        return keyIndexOrNull(accountId, spendDomain(host), it)
    }
    return when {
        set.scheme == KeyScheme.Bip32 -> shareScanFrom(accountId, host, checkNotNull(set.share))
        set.isAddress -> addressScanFrom(accountId, host)
        else -> currentIndexOrNull(accountId, host, set.purpose)
    }
}

internal suspend fun WalletStore.indexOf(accountId: String, host: String, set: KeySet): Long = indexOrNull(accountId, host, set) ?: 0

internal suspend fun WalletStore.setIndexOf(accountId: String, host: String, set: KeySet, next: Long) {
    set.counter?.let {
        return setKeyIndex(accountId, spendDomain(host), it, next)
    }
    when {
        set.scheme == KeyScheme.Bip32 -> setShareScanFrom(accountId, host, checkNotNull(set.share), next)
        set.isAddress -> setAddressScanFrom(accountId, host, next)
        else -> setIndex(accountId, host, set.purpose, next)
    }
}

/** Whether [note] is one of [set]'s keys on [host]'s mint domain (the wallet and change counters span the domain, the rest the host). */
internal fun KeySet.holds(note: Note, host: String): Boolean =
    note.kind == NoteKind.KeyPath &&
        note.scheme == scheme &&
        note.purpose == purpose &&
        note.share == share &&
        if (counter != null) spendDomain(note.host) == spendDomain(host) else note.host == host
