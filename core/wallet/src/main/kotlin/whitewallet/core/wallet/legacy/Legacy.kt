/*
 * Legacy: LUD-25/26 draft 2f17578. The one door to everything old: production code reaches it through Wallet.legacy and nothing else.
 * Each member says what it waits for; see protocol/SPEC.md, "Removing legacy 2f17578".
 */
package whitewallet.core.wallet.legacy

import java.io.IOException
import kotlinx.coroutines.flow.first
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.wallet.AccountKeys
import whitewallet.core.wallet.AddressCheck
import whitewallet.core.wallet.AddressScanner
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.wallet.DefaultAddress
import whitewallet.core.wallet.KeySet
import whitewallet.core.wallet.NoteRotator
import whitewallet.core.wallet.OnboardResult
import whitewallet.core.wallet.Onboarding
import whitewallet.core.wallet.Wallet
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.AddressProof
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.client.RegistrationOutcome
import whitewallet.lnurlcash.client.legacy.legacyRegister
import whitewallet.lnurlcash.client.legacy.legacyUnregister
import whitewallet.lnurlcash.legacy.LegacyAddressProof
import whitewallet.lnurlcash.protocol.legacy.LegacyCpub
import whitewallet.lnurlcash.spendDomain

internal class Legacy(private val wallet: Wallet, scanner: AddressScanner, private val addresses: AddressCheck, rotator: NoteRotator) {
    val migration = LegacyMigration(wallet, scanner, addresses, rotator)

    // ---- tag: keys

    /** What a restore looks through besides the account's own chains: what the wallet's first account had before the upgrade. */
    val restoreSets: List<KeySet>
        get() = KeySet.LEGACY

    /**
     * Whether the wallet's old keys are [accountId]'s. They derive from the phrase alone, so all its accounts would reach the same notes:
     * they are the first account's, the one that was there before wallets held several.
     */
    suspend fun ownsKeys(accountId: String): Boolean = (wallet.accountOf(accountId)?.index ?: 0L) == 0L

    /** The purpose-2 keys to scan for [accountId]'s payments: while any address is still on the branch, or the account has none. */
    suspend fun addressSets(accountId: String, on: List<RegisteredAddress>): List<KeySet> =
        if ((on.isEmpty() || on.any { it.share == null }) && ownsKeys(accountId)) listOf(KeySet.LegacyAddress) else emptyList()

    /** The names a wallet made before the upgrade suggested, to find them again. */
    suspend fun names(accountId: String, host: String): List<String> =
        if (ownsKeys(accountId)) wallet.keysIfSeeded(accountId, host)?.let { DefaultAddress.names(it.legacy.cx1()) }.orEmpty()
        else emptyList()

    /** After a restore: such a name still the account's. One registered before the mint moved to LUD-26 is proven by moving it. */
    suspend fun recoverableUsername(accountId: String, host: String): String? =
        names(accountId, host).firstOrNull { name ->
            when (addresses.check(name, host, accountId)) {
                AddressStatus.Ours -> true
                AddressStatus.Taken -> wallet.dialect(host) == MintDialect.Current && migration.move(accountId, name, host) != null
                else -> false
            }
        }

    /** A name registered before the mint moved to LUD-26 is proven by moving it to a share; the share, or null. */
    suspend fun takeBack(name: String, host: String, accountId: String): Long? =
        try {
            if (wallet.dialect(host) == MintDialect.Current) migration.move(accountId, name, host) else null
        } catch (e: IOException) {
            null
        }

    /** The proof to unregister an address still on the branch from a LUD-26 mint: the key on file is the branch key. */
    fun unregisterProof(keys: AccountKeys, domain: String, username: String): String =
        AddressProof.sign(AddressProof.unregisterMessage(domain, username), keys.legacy.branchKey())

    /** The branch `cx1` of an address not on a share: what another app could watch before. */
    suspend fun watchOnlyKey(accountId: String, host: String): String? =
        wallet.store
            .addresses(accountId)
            .first()
            .takeIf { all -> all.any { it.host == host && it.share == null } }
            ?.let {
                wallet.keysIfSeeded(accountId, host)?.legacy?.cx1()
            }

    // ---- tag: mint

    /** The published `text/cpub` names the account's branch, or not. */
    suspend fun status(pay: LnurlResponse.PayRequest, host: String, accountId: String?): AddressStatus {
        val cpub = LegacyCpub.cx1(pay) ?: return AddressStatus.Unconfirmed
        val ours = accountId?.takeIf { ownsKeys(it) }?.let { wallet.keysIfSeeded(it, host)?.legacy?.cx1() }
        return if (ours != null && cpub.equals(ours, ignoreCase = true)) AddressStatus.Ours else AddressStatus.Taken
    }

    /** Whether [transfer] pays this account's own branch. */
    suspend fun paysOwnBranch(transfer: LegacyTransfer, accountId: String, host: String): Boolean =
        transfer.branch.cx1().equals(wallet.keys(accountId, host).legacy.cx1(), ignoreCase = true)

    /** Registers [name] for the account's branch `cx1` at `/p/`. */
    suspend fun claim(
        onboarding: Onboarding,
        name: String,
        h: String,
        accountId: String,
        ready: suspend (Boolean, Long?) -> OnboardResult,
    ): OnboardResult {
        if (!ownsKeys(accountId)) return OnboardResult.MintTooOld
        val branch = wallet.keys(accountId, h).legacy.branch
        val sig = LegacyAddressProof.sign(branch, LegacyAddressProof.Action.Register, spendDomain(h), name)
        when (val outcome = wallet.client.legacyRegister(h, name, branch.cx1(), sig)) {
            is RegistrationOutcome.Rejected -> {
                // Lost a race for the name, or the mint refuses registrations. Look again to tell which.
                return if (addresses.check(name, h, accountId) == AddressStatus.Taken) onboarding.taken(name, accountId, h)
                else OnboardResult.RegistrationFailed(outcome.reason)
            }
            // The answer was lost; the lookup below shows whether it landed.
            is RegistrationOutcome.Ambiguous,
            is RegistrationOutcome.AwaitingFee,
            RegistrationOutcome.Registered -> Unit
        }
        return onboarding.settle(addresses.check(name, h, accountId), name, h, accountId) { confirmed -> ready(confirmed, null) }
    }

    /** Unregisters [address] from a mint still on the old protocol. */
    suspend fun unregister(keys: AccountKeys, address: RegisteredAddress): RegistrationOutcome {
        val domain = spendDomain(address.host)
        val sig = LegacyAddressProof.sign(keys.legacy.branch, LegacyAddressProof.Action.Unregister, domain, address.username)
        return wallet.client.legacyUnregister(address.host, address.username, sig)
    }
}
