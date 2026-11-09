package whitewallet.core.wallet

import kotlinx.coroutines.flow.first

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
