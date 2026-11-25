package whitewallet.core.wallet

import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.flow.first
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState

/**
 * Setting a wallet up in one call, for tests that need one with an address: a new wallet (or, with [inActiveWallet], the active wallet's
 * next account) named [newAccountName] gets [username] on [host], its setup done. Without [newAccountName] and with an active account, only
 * the claim. A new account goes again unless the claim holds (registered, or held for its fee). The app does these steps one screen each.
 */
internal suspend fun Wallet.onboard(
    username: String,
    host: String,
    newAccountName: String? = null,
    inActiveWallet: Boolean = false,
): OnboardResult {
    if (newAccountName == null && store.currentAccountId() != null) return claimAddress(username, host)
    val name = newAccountName ?: "Main"
    val before = store.currentAccountId()
    val created = if (inActiveWallet) createAccount(name) else createWallet(name)
    if (created is CreateAccountResult.AccountLimitReached) return OnboardResult.RegistrationFailed("limit ${created.limit}")
    if (created is CreateAccountResult.ScanFailed) return created.result
    if (created is CreateAccountResult.UseFirst) return OnboardResult.RegistrationFailed("use ${created.first.account.name} first")
    val result = claimAddress(username, host)
    if (result is OnboardResult.Ready || result is OnboardResult.AwaitingFee) {
        setSetupStep(null)
        return result
    }
    if (inActiveWallet) {
        store.deleteAccount(activeId())
        store.setActiveAccount(before)
    } else abandonSetup()
    return result
}

/** A set-up wallet (or, with [inActiveWallet], the active wallet's next account) named [name], on [host] if given: its mint connected. */
internal suspend fun Wallet.createAccount(host: String?, name: String, inActiveWallet: Boolean = false): CreateAccountResult {
    val created = if (inActiveWallet) createAccount(name) else createWallet(name)
    if (created !is CreateAccountResult.Created) return created
    if (!inActiveWallet) setSetupStep(null)
    host?.let { connectMint(it) }
    return created
}

/** Whether the active wallet has a setup step left: restored, and its address not picked yet. */
internal suspend fun Wallet.inSetup(): Boolean = setupStep.first() != null

/**
 * Gives [accountId] a note it held, spent since, on a host no test uses: what makes an account used ([AccountSummary.used]), so the wallet
 * adds another account after it ([accountToUseFirst]), and nothing else.
 */
internal suspend fun WalletStore.useAccount(accountId: String) {
    val q = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
    upsertNotes(listOf(Note(q, accountId, "used.example", NoteKind.KeyPath, 0, 0, 1_000, null, NoteState.Spent, 0)))
}
