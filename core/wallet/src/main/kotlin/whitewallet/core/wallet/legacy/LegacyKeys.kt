/*
 * Legacy: LUD-26 draft 2f17578 (tag: keys). Removable once no wallet or recovery phrase can hold notes or addresses from before; see
 * protocol/SPEC.md, "Removing legacy 2f17578".
 */
package whitewallet.core.wallet.legacy

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import whitewallet.core.model.KeyScheme
import whitewallet.core.wallet.KeySet
import whitewallet.lnurlcash.Spend
import whitewallet.lnurlcash.legacy.LegacyBranch
import whitewallet.lnurlcash.legacy.LegacyPurpose

fun KeySet.Companion.legacy(purpose: LegacyPurpose) = KeySet(KeyScheme.Legacy, purpose.value)

val KeySet.Companion.LegacyWallet: KeySet
    get() = legacy(LegacyPurpose.Wallet)

val KeySet.Companion.LegacyChange: KeySet
    get() = legacy(LegacyPurpose.Change)

val KeySet.Companion.LegacyAddress: KeySet
    get() = legacy(LegacyPurpose.LightningAddress)

/** Every purpose, as restore scans them. */
val KeySet.Companion.LEGACY: List<KeySet>
    get() = listOf(LegacyWallet, LegacyChange, LegacyAddress)

/** One account's branch for a host as stored, port included. Holds secret keys, like the account's other keys. */
internal class LegacyKeys(val branch: LegacyBranch) {
    fun cx1(): String = branch.cx1()

    fun branchKey(): PrivateKey = branch.branchKey()

    fun pubkey(set: KeySet, index: Long): ByteVector32 = branch.notePubkey(purpose(set), index)

    fun spend(set: KeySet, index: Long, domain: String): Spend.KeyPath = branch.spend(purpose(set), index, domain)

    private fun purpose(set: KeySet) = LegacyPurpose.entries.first { it.value == set.purpose }

    companion object {
        fun fromSeed(seed: ByteArray, host: String) = LegacyKeys(LegacyBranch.fromSeed(seed, host))
    }
}
