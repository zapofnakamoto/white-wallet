package whitewallet.core.wallet

import fr.acinq.bitcoin.MnemonicCode
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.legacy.LegacyKeys
import whitewallet.lnurlcash.ServiceRoot
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.spendDomain

/** [words]' keys at [host] for account [account] of their wallet, as the wallet derives them. */
internal fun testKeys(words: List<String>, host: String, account: Long = 0): AccountKeys {
    val seed = MnemonicCode.toSeed(words, "")
    return AccountKeys(ServiceRoot.fromSeed(seed, host).account(account), LegacyKeys.fromSeed(seed, host))
}

/** The key-path spend of key [index] of [set] for [host]'s mint. */
internal fun AccountKeys.spend(set: KeySet, index: Long, host: String): Spend.KeyPath {
    val q = pubkey(set, index).toHex()
    val note =
        Note(q, "", host, NoteKind.KeyPath, set.purpose, index, 0, null, NoteState.Outstanding, 0, scheme = set.scheme, share = set.share)
    return spend(note, spendDomain(host))
}
