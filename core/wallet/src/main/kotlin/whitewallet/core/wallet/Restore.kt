package whitewallet.core.wallet

import java.util.UUID
import kotlinx.coroutines.flow.first
import whitewallet.core.model.Account
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.NoteState
import whitewallet.core.model.SetupStep
import whitewallet.core.model.WalletRecord
import whitewallet.lnurlcash.spendDomain

sealed interface RestoreResult {
    /** The wallet exists and its first account is active, with every note found on the mint; it has no address yet. */
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
 * unused ones, which imports every note and sets the indexes past the last used key. Without a mint, only the phrase comes in, as a wallet
 * without a mint; adding one later runs the same scan there ([scanAddedMint]). Lightning addresses can't be recovered this way (the mint
 * maps names to keys, not keys to names); the account is left without one, and the user enters an address they had or picks a new one in
 * the wallet's setup ([SetupStep.Address]). Only lookups are sent, so a restore that fails half way changes nothing at the mint and is
 * undone locally.
 */
internal class Restore(private val wallet: Wallet) {
    private val discovery = IndexDiscovery(wallet)
    private val onboarding = Onboarding(wallet)

    suspend fun restore(phrase: String, host: String?, accountName: String): RestoreResult {
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
        val h = host?.let { Onboarding.normalizeHost(it) ?: return RestoreResult.MintInvalid("not a valid host") }
        val accountId = UUID.randomUUID().toString()

        // The wallet and its first account are saved before anything of theirs (the mint, what the scan finds), and removed on failure.
        val before = wallet.store.currentAccountId()
        val step = if (h == null) SetupStep.Mint else SetupStep.Address
        wallet.store.saveWallet(WalletRecord(accountId, null, wallet.now(), setupStep = step, activeBeforeSetup = before))
        wallet.seeds.save(accountId, words)
        // The user had the phrase written down; no need to ask them to back it up again.
        wallet.seeds.setBackupVerified(accountId)
        wallet.store.saveAccount(Account(accountId, accountName, wallet.now()))
        val undo = suspend {
            wallet.siblings(accountId).forEach { wallet.store.deleteAccount(it.id) }
            wallet.discardSeed(accountId)
        }
        val failed: suspend (Failure) -> RestoreResult = { failure ->
            undo()
            if (failure.refused) RestoreResult.MintInvalid(failure.reason) else RestoreResult.NetworkError(failure.reason)
        }
        when (val connected = h?.let { onboarding.connectMint(accountId, it) }) {
            null,
            is ConnectResult.Connected -> Unit
            is ConnectResult.Failed -> {
                undo()
                return when (val r = connected.result) {
                    is OnboardResult.NetworkError -> RestoreResult.NetworkError(r.reason)
                    is OnboardResult.MintInvalid -> RestoreResult.MintInvalid(r.reason)
                    else -> RestoreResult.MintInvalid(r.toString())
                }
            }
        }
        h?.let { scanWallet(accountId, it) }
            ?.let {
                return failed(it)
            }
        wallet.store.setActiveAccount(accountId)
        val notes = wallet.store.notes(accountId).first().filter { it.state == NoteState.Outstanding }
        return RestoreResult.Restored(accountId, notes.size, notes.sumOf { it.amountMsat })
    }

    /**
     * Finds what the wallet [walletId], which exists already, holds on the mint at [host]: what a restore does on its mint, here for one
     * the wallet adds later. Null once every account was looked through; otherwise why the scan stopped. What it found until then stays.
     */
    suspend fun scanAddedMint(walletId: String, host: String, onProgress: (ScanProgress) -> Unit = {}): OnboardResult? =
        scanWallet(walletId, host, onProgress)?.let {
            if (it.refused) OnboardResult.MintInvalid(it.reason) else OnboardResult.NetworkError(it.reason)
        }

    /**
     * Finds what every account of the wallet [walletId] holds on [host]: each account it has, then further ones up to a gap of ones never
     * used (BIP-44). An account the wallet has counts as used, and created ones before a used one stay, to keep its index. A created one's
     * home is [host], where it was found. Null once every account was looked through; otherwise why the scan stopped, and the created
     * accounts that hold nothing are gone again.
     */
    private suspend fun scanWallet(walletId: String, host: String, onProgress: (ScanProgress) -> Unit = {}): Failure? {
        val accounts = wallet.store.accounts.first().map { it.account }.filter { it.walletId == walletId }
        val byIndex = accounts.associateBy { it.index }
        val progress = mutableListOf<ScanProgress.AccountScan>()
        val last = accounts.maxOf { it.index }
        val creatable = minOf(wallet.maxAccounts, wallet.settings.scanMaxAccountsChecked).toLong()
        val unused = mutableListOf<String>()
        var index = 0L
        while (index <= last || (index < creatable && unused.size < wallet.settings.scanStopAfterUnusedAccounts)) {
            val had = byIndex[index]
            val account =
                had
                    ?: Account(UUID.randomUUID().toString(), wallet.accountName(index), wallet.now(), walletId, index, host)
                        .takeIf { index < creatable }
                        ?.also { wallet.store.saveAccount(it) }
            val at = index++
            if (account == null) continue
            progress += ScanProgress.AccountScan(at, 0, 0, done = false)
            val report = { probe: KeyProbe ->
                val now = progress.last()
                progress[progress.lastIndex] =
                    now.copy(
                        keysChecked = now.keysChecked + 1,
                        foundMsat = now.foundMsat + ((probe as? KeyProbe.Outstanding)?.amountMsat ?: 0),
                    )
                onProgress(ScanProgress(progress.toList()))
            }
            val found = scan(account.id, host, report)
            progress[progress.lastIndex] = progress.last().copy(done = true)
            onProgress(ScanProgress(progress.toList()))
            when (found) {
                is Scan.Failed -> {
                    val created = if (had == null) unused + account.id else unused
                    created.filter { wallet.store.notes(it).first().isEmpty() }.forEach { wallet.store.deleteAccount(it) }
                    return found.failure
                }
                is Scan.Done -> if (found.used || had != null) unused.clear() else unused += account.id
            }
        }
        unused.forEach { wallet.store.deleteAccount(it) }
        return null
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
    private suspend fun scan(accountId: String, h: String, report: (KeyProbe) -> Unit = {}): Scan {
        val older = if (wallet.legacy.ownsKeys(accountId)) wallet.legacy.restoreSets else emptyList()
        var used = false
        for (set in listOf(KeySet.Wallet, KeySet.Change) + older) {
            val found = discovery.discover(accountId, h, set, onProbe = report)
            found.stoppedEarly?.let {
                return Scan.Failed(Failure(it, found.refused))
            }
            used = used || found.next > 0
        }
        // Address payments, one share per address: shares are handed out in order, and an address may never have been paid, so the scan
        // ends after a gap of unused ones.
        var share = 0L
        var unused = 0
        var last = -1L
        while (unused < wallet.settings.scanStopAfterUnusedShares) {
            val found = discovery.discover(accountId, h, KeySet.share(share), onProbe = report)
            found.stoppedEarly?.let {
                return Scan.Failed(Failure(it, found.refused))
            }
            if (found.next > 0) {
                last = share
                unused = 0
            } else unused++
            share++
        }
        // Only forward: an existing account may have handed out shares that were never paid.
        if ((wallet.store.keyIndexOrNull(accountId, spendDomain(h), KeyCounter.Shares) ?: 0) <= last) {
            wallet.store.setKeyIndex(accountId, spendDomain(h), KeyCounter.Shares, last + 1)
        }
        return Scan.Done(used || last >= 0)
    }
}
