package whitewallet.core.wallet

import java.util.UUID
import kotlinx.coroutines.flow.first
import whitewallet.core.model.Account
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.NotePurpose

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
 * Recovers an account from its recovery phrase alone (LUD-26 recovery): every key of each purpose is looked up on
 * the mint until a gap of unused ones, which imports every note and sets the indexes past the last used key.
 * Lightning addresses can't be recovered this way (the mint maps names to keys, not keys to names); the account is
 * left without one, and the user enters an address they had or picks a new one (see [Wallet.needsAddress]).
 * Only lookups are sent, so a restore that fails half way changes nothing at the mint and is undone locally.
 */
internal class Restore(private val wallet: Wallet) {
    private val discovery = IndexDiscovery(wallet)

    suspend fun restore(phrase: String, host: String, accountName: String): RestoreResult {
        val words = when (val check = RecoveryPhrase.check(phrase)) {
            is PhraseCheck.Valid -> check.words
            else -> return RestoreResult.InvalidPhrase(check)
        }
        val accounts = wallet.store.accounts.first()
        for (summary in accounts) {
            if (wallet.seeds.load(summary.account.id) == words) return RestoreResult.AlreadyInWallet(summary.account.name)
        }
        if (accounts.size >= wallet.maxAccounts) return RestoreResult.AccountLimitReached(wallet.maxAccounts)
        when (val connected = Onboarding(wallet).connectMint(host)) {
            is ConnectResult.Connected -> Unit
            is ConnectResult.Failed -> return when (val r = connected.result) {
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
        for (purpose in NotePurpose.entries) {
            val found = discovery.discover(accountId, h, purpose.value)
            found.stoppedEarly?.let { reason ->
                wallet.store.deleteAccount(accountId)
                wallet.discardSeed(accountId)
                return if (found.refused) RestoreResult.MintInvalid(reason) else RestoreResult.NetworkError(reason)
            }
        }
        wallet.activeBeforeRestore = wallet.store.currentAccountId()
        wallet.store.setActiveAccount(accountId)
        val notes = wallet.store.notes(accountId).first().filter { it.state == NoteState.Outstanding }
        return RestoreResult.Restored(accountId, notes.size, notes.sumOf { it.amountMsat })
    }
}
