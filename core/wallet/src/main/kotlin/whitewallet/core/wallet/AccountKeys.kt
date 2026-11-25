package whitewallet.core.wallet

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import java.util.concurrent.ConcurrentHashMap
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.lnurlcash.NoteAccount
import whitewallet.lnurlcash.Share
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.spendDomain

/**
 * The keys one index counts (LUD-26): the account's wallet chain `S/a'/0`, its change chain `S/a'/1`, or one of its shares `S/a'/2/n'`,
 * whose notes are paid to a Lightning address.
 */
sealed interface KeySet {
    /** The chain below the account, as notes, outputs and key checks store it: 0 wallet, 1 change, 2 shares. */
    val purpose: Int

    /** The share index of a [Share], else null: how notes, outputs and key checks store it. */
    val share: Long?
        get() = (this as? Share)?.n

    fun key(accountId: String, host: String, index: Long) = KeyId(accountId, host, this, index)

    data object Wallet : KeySet {
        override val purpose = 0
    }

    data object Change : KeySet {
        override val purpose = 1
    }

    data class Share(val n: Long) : KeySet {
        override val purpose = 2
    }

    companion object {
        /** The set stored as [purpose] and, for a share, [share]. */
        fun of(purpose: Int, share: Long?): KeySet =
            when (purpose) {
                0 -> Wallet
                1 -> Change
                2 -> Share(checkNotNull(share) { "a share has a share index" })
                else -> throw IllegalArgumentException("no key chain $purpose")
            }

        /** Where a note's key is. */
        fun of(note: Note): KeySet = of(checkNotNull(note.purpose) { "not a key-path note" }, note.share)
    }
}

/** A key-path note's index in its [KeySet]. */
internal val Note.indexInSet: Long
    get() = checkNotNull(index) { "not a key-path note" }

/** The account's own counter on the mint's domain, for the wallet and change chains; shares have none. */
internal val KeySet.counter: KeyCounter?
    get() =
        when (this) {
            KeySet.Wallet -> KeyCounter.Wallet
            KeySet.Change -> KeyCounter.Change
            is KeySet.Share -> null
        }

/** A note paid to one of the account's Lightning addresses. */
internal val Note.isAddressNote: Boolean
    get() = kind == NoteKind.KeyPath && purpose == 2

/**
 * One account's keys at one mint: account `a` of its wallet's LUD-26 root for the mint's domain. Holds secret keys: kept for the life of
 * the process, never written anywhere.
 */
internal class AccountKeys(private val account: NoteAccount) {
    private val shares = ConcurrentHashMap<Long, Share>()

    fun share(n: Long): Share = shares.getOrPut(n) { account.share(n) }

    fun pubkey(set: KeySet, index: Long): ByteVector32 =
        if (set is KeySet.Share) share(set.n).notePubkey(index) else secretKey(set, index).xOnlyPublicKey().value

    fun pubkey(id: KeyId): ByteVector32 = pubkey(id.set, id.index)

    /** The key-path spend of [note] for [domain]. */
    fun spend(note: Note, domain: String): Spend.KeyPath {
        val set = KeySet.of(note)
        val index = checkNotNull(note.index) { "not a key-path note" }
        return Spend.keyPath(secretKey(set, index), domain)
    }

    private fun secretKey(set: KeySet, index: Long): PrivateKey =
        when (set) {
            KeySet.Wallet -> account.walletKey(index)
            KeySet.Change -> account.changeKey(index)
            is KeySet.Share -> share(set.n).noteSecretKey(index)
        }
}

/**
 * Where [set]'s index stands: the next key of the account's wallet or change counter on the mint's domain, or the first key the next scan
 * of a share looks up. Null if a counter was never set (the account has not used that mint domain yet).
 */
internal suspend fun WalletStore.indexOrNull(accountId: String, host: String, set: KeySet): Long? {
    set.counter?.let {
        return keyIndexOrNull(accountId, spendDomain(host), it)
    }
    return shareScanFrom(accountId, host, checkNotNull(set.share))
}

internal suspend fun WalletStore.indexOf(accountId: String, host: String, set: KeySet): Long = indexOrNull(accountId, host, set) ?: 0

internal suspend fun WalletStore.setIndexOf(accountId: String, host: String, set: KeySet, next: Long) {
    set.counter?.let {
        return setKeyIndex(accountId, spendDomain(host), it, next)
    }
    setShareScanFrom(accountId, host, checkNotNull(set.share), next)
}

/** Whether [note] is one of [set]'s keys on [host]'s mint domain (the wallet and change counters span the domain, the rest the host). */
internal fun KeySet.holds(note: Note, host: String): Boolean =
    note.kind == NoteKind.KeyPath &&
        note.purpose == purpose &&
        note.share == share &&
        if (counter != null) spendDomain(note.host) == spendDomain(host) else note.host == host
