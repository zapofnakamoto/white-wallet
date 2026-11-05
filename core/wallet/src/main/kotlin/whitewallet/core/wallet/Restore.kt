package whitewallet.core.wallet

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first
import whitewallet.core.model.Account
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.spendDomain

sealed interface RestoreResult {
    /** The account exists and is active, with every note found on the mint; it has no address yet. */
    data class Restored(val accountId: String, val notes: Int, val balanceMsat: Long) : RestoreResult

    data class InvalidPhrase(val problem: PhraseCheck) : RestoreResult

    /** This recovery phrase is already an account of this wallet. */
    data class AlreadyInWallet(val accountName: String) : RestoreResult

    /** The wallet already holds [limit] accounts, the most this app allows ([Wallet.maxAccounts]). Nothing changed. */
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
            if (wallet.seeds.load(summary.account.id) == words) return RestoreResult.AlreadyInWallet(summary.account.name)
        }
        if (accounts.size >= wallet.maxAccounts) return RestoreResult.AccountLimitReached(wallet.maxAccounts)
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
        val stopped: suspend (DiscoveryResult) -> RestoreResult? = { found ->
            found.stoppedEarly?.let { reason ->
                wallet.store.deleteAccount(accountId)
                wallet.discardSeed(accountId)
                if (found.refused) RestoreResult.MintInvalid(reason) else RestoreResult.NetworkError(reason)
            }
        }
        // The account's LUD-26 chains, then the legacy 2f17578 purposes (tag: keys), which hold what it had before the upgrade.
        for (set in listOf(KeySet.Wallet, KeySet.Change) + KeySet.LEGACY) {
            stopped(discovery.discover(accountId, h, set))?.let {
                return it
            }
        }
        // Address payments, one share per address: shares are handed out in order, so the scan ends after a few unused ones. A legacy
        // mint never registered one (tag: mint).
        val dialect =
            try {
                wallet.dialect(h)
            } catch (e: IOException) {
                wallet.store.deleteAccount(accountId)
                wallet.discardSeed(accountId)
                return RestoreResult.NetworkError(e.message ?: "network error")
            }
        var share = 0L
        var unused = if (dialect == MintDialect.Legacy) AddressCheck.RESTORE_SHARES.toInt() else 0
        var used = -1L
        while (unused < AddressCheck.RESTORE_SHARES) {
            val found = discovery.discover(accountId, h, KeySet.share(share))
            stopped(found)?.let {
                return it
            }
            if (found.next > 0) {
                used = share
                unused = 0
            } else unused++
            share++
        }
        if (used >= 0) wallet.store.setKeyIndex(accountId, spendDomain(h), KeyCounter.Shares, used + 1)
        wallet.activeBeforeRestore = wallet.store.currentAccountId()
        wallet.store.setActiveAccount(accountId)
        val notes = wallet.store.notes(accountId).first().filter { it.state == NoteState.Outstanding }
        return RestoreResult.Restored(accountId, notes.size, notes.sumOf { it.amountMsat })
    }
}
