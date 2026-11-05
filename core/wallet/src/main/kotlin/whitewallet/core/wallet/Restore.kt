package whitewallet.core.wallet

import java.util.UUID
import kotlinx.coroutines.flow.first
import whitewallet.core.model.Account
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.spendDomain

sealed interface RestoreResult {
    /** The account exists and is active, with every note found on the mint; it has no address yet. */
    data class Restored(val accountId: String, val notes: Int, val balanceMsat: Long) : RestoreResult

    data class InvalidPhrase(val problem: PhraseCheck) : RestoreResult

    /** This recovery phrase is already an account of this wallet. */
    data class AlreadyInWallet(val accountName: String) : RestoreResult

    /** The app already holds [limit] wallets, the most it allows ([Wallet.maxWallets]). Nothing changed. */
    data class AccountLimitReached(val limit: Int) : RestoreResult

    data class MintInvalid(val reason: String) : RestoreResult

    data class NetworkError(val reason: String) : RestoreResult
}

/**
 * Recovers an account from its recovery phrase alone (LUD-26 recovery): every key of each purpose is looked up on the mint until a gap of
 * unused ones, which imports every note and sets the indexes past the last used key. Lightning addresses can't be recovered this way (the
 * mint maps names to keys, not keys to names); the account is left without one, and the user enters an address they had or picks a new one
 * (see [Wallet.needsAddress]). Only lookups are sent, so a restore that fails half way changes nothing at the mint and is undone locally.
 */
internal class Restore(private val wallet: Wallet) {
    private val discovery = IndexDiscovery(wallet)
    private val onboarding = Onboarding(wallet)

    suspend fun restore(phrase: String, host: String, accountName: String): RestoreResult {
        val words =
            when (val check = RecoveryPhrase.check(phrase)) {
                is PhraseCheck.Valid -> check.words
                else -> return RestoreResult.InvalidPhrase(check)
            }
        val accounts = wallet.store.accounts.first()
        for (summary in accounts) {
            if (wallet.seeds.load(summary.account.walletId) == words) return RestoreResult.AlreadyInWallet(summary.account.name)
        }
        if (!wallet.canAddWallet.first()) return RestoreResult.AccountLimitReached(wallet.maxWallets)
        when (val connected = onboarding.connectMint(host)) {
            is ConnectResult.Connected -> Unit
            is ConnectResult.Failed ->
                return when (val r = connected.result) {
                    is OnboardResult.NetworkError -> RestoreResult.NetworkError(r.reason)
                    is OnboardResult.MintInvalid -> RestoreResult.MintInvalid(r.reason)
                    else -> RestoreResult.MintInvalid(r.toString())
                }
        }
        val h = checkNotNull(Onboarding.normalizeHost(host))

        val accountId = UUID.randomUUID().toString()
        wallet.seeds.save(accountId, words)
        // The user had the phrase written down; no need to ask them to back it up again.
        wallet.seeds.setBackupVerified(accountId)
        wallet.store.saveAccount(Account(accountId, accountName, wallet.now(), h))
        val failed: suspend (Failure) -> RestoreResult = { failure ->
            wallet.siblings(accountId).forEach { wallet.store.deleteAccount(it.id) }
            wallet.discardSeed(accountId)
            if (failure.refused) RestoreResult.MintInvalid(failure.reason) else RestoreResult.NetworkError(failure.reason)
        }
        when (val first = scan(accountId, h, wallet.legacy.restoreSets)) {
            is Scan.Failed -> return failed(first.failure)
            is Scan.Done -> Unit
        }
        // The phrase's further accounts, up to the first that was never used (BIP-44).
        for (index in 1L until minOf(wallet.maxAccounts, MAX_ACCOUNTS).toLong()) {
            val id = UUID.randomUUID().toString()
            wallet.store.saveAccount(Account(id, "$accountName ${index + 1}", wallet.now(), h, walletId = accountId, index = index))
            when (val found = scan(id, h, emptyList())) {
                is Scan.Failed -> return failed(found.failure)
                is Scan.Done ->
                    if (!found.used) {
                        wallet.store.deleteAccount(id)
                        break
                    }
            }
        }
        wallet.activeBeforeRestore = wallet.store.currentAccountId()
        wallet.store.setActiveAccount(accountId)
        val notes = wallet.store.notes(accountId).first().filter { it.state == NoteState.Outstanding }
        return RestoreResult.Restored(accountId, notes.size, notes.sumOf { it.amountMsat })
    }

    private data class Failure(val reason: String, val refused: Boolean)

    private sealed interface Scan {
        /** [used]: some key of the account was ever used. */
        data class Done(val used: Boolean) : Scan

        data class Failed(val failure: Failure) : Scan
    }

    /**
     * Finds what [accountId] holds on [h]. The legacy 2f17578 purposes (tag: keys) hold what the wallet's first account had before the
     * upgrade.
     */
    private suspend fun scan(accountId: String, h: String, older: List<KeySet>): Scan {
        var used = false
        for (set in listOf(KeySet.Wallet, KeySet.Change) + older) {
            val found = discovery.discover(accountId, h, set)
            found.stoppedEarly?.let {
                return Scan.Failed(Failure(it, found.refused))
            }
            used = used || found.next > 0
        }
        // Address payments, one share per address: shares are handed out in order, so the scan ends after a few unused ones.
        var share = 0L
        var unused = 0
        var last = -1L
        while (unused < AddressCheck.RESTORE_SHARES) {
            val found = discovery.discover(accountId, h, KeySet.share(share))
            found.stoppedEarly?.let {
                return Scan.Failed(Failure(it, found.refused))
            }
            if (found.next > 0) {
                last = share
                unused = 0
            } else unused++
            share++
        }
        if (last >= 0) wallet.store.setKeyIndex(accountId, spendDomain(h), KeyCounter.Shares, last + 1)
        return Scan.Done(used || last >= 0)
    }

    private companion object {
        /** Past this a restore stops looking for further accounts, whatever the brand allows. */
        const val MAX_ACCOUNTS = 100
    }
}
