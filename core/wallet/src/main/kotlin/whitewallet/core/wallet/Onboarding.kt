package whitewallet.core.wallet

import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.flow.first
import whitewallet.core.model.Account
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.Mint
import whitewallet.core.model.RegisteredAddress
import whitewallet.core.model.SetupStep
import whitewallet.core.model.WalletRecord
import whitewallet.lnurl.Bolt11
import whitewallet.lnurlcash.AddressProof
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.client.RegistrationOutcome
import whitewallet.lnurlcash.client.RegistrationRejection
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.spendDomain

sealed interface OnboardResult {
    /**
     * The address is saved. [confirmed] is true when the mint publishes our own `cx1` for it, which proves payments to it reach this
     * wallet; false when the mint accepted it but does not expose a `cx1` to check.
     */
    data class Ready(val address: RegisteredAddress, val confirmed: Boolean) : OnboardResult

    /**
     * The mint holds the name until [address]'s fee is paid: its invoice for [amountMsat], from another wallet or with
     * [Wallet.payAddressFee]. A refresh finishes the registration once it is paid, and drops the address if the invoice expires unpaid.
     */
    data class AwaitingFee(val address: RegisteredAddress, val amountMsat: Long) : OnboardResult

    /** The mint asks [amountMsat] for the address, more than it advertises or the app allows ([maxMsat]). Nothing is held here. */
    data class FeeRefused(val amountMsat: Long, val maxMsat: Long) : OnboardResult

    /** Someone else has this name. [suggestions] are alternatives the user can pick from. */
    data class UsernameTaken(val suggestions: List<String>) : OnboardResult

    data class UsernameInvalid(val reason: String) : OnboardResult

    /** The mint refused the registration for another reason, e.g. registration is turned off. */
    data class RegistrationFailed(val reason: String?) : OnboardResult

    data class MintInvalid(val reason: String) : OnboardResult

    data class NetworkError(val reason: String) : OnboardResult

    /** The account already holds [limit] addresses on this mint (see [Wallet.addAddress]). Nothing changed. */
    data class LimitReached(val limit: Int) : OnboardResult

    /** A legacy 2f17578 mint knows one address key per recovery phrase, the first account's: another account can't get an address there. */
    data object MintTooOld : OnboardResult
}

/** One of an account's Lightning addresses, whether it is the primary one, and its last check (null: not checked). */
data class AddressEntry(val address: RegisteredAddress, val primary: Boolean, val status: AddressStatus?)

sealed interface UnregisterResult {
    /** The mint freed the name; it is gone from the account. */
    data object Unregistered : UnregisterResult

    /** The name no longer points at this account (someone else holds it); removed from the account. */
    data object NotOurs : UnregisterResult

    /** The mint kept the name; nothing changed. */
    data class Failed(val reason: String) : UnregisterResult

    /** Not known whether it worked; the address stays listed until a check shows it is free. */
    data class NetworkError(val reason: String) : UnregisterResult
}

/** Why [Wallet.addMint] refused a mint that presents another signing key than the one pinned, or than the one it showed. */
const val MINT_KEY_CHANGED = "the signing key of this host changed"

sealed interface ConnectResult {
    data class Connected(val mint: Mint) : ConnectResult

    data class Failed(val result: OnboardResult) : ConnectResult
}

/** The outcome of [Wallet.createWallet] and [Wallet.createAccount]. */
sealed interface CreateAccountResult {
    /** The account exists and is active. */
    data class Created(val accountId: String) : CreateAccountResult

    /** The wallet already holds [limit] accounts, or the app [limit] wallets, the most it allows. Nothing changed. */
    data class AccountLimitReached(val limit: Int) : CreateAccountResult
}

/** The outcome of [Wallet.abandonSetup]. */
sealed interface AbandonResult {
    /** The wallet is gone from this device; [remains]: another account is active now. */
    data class Abandoned(val remains: Boolean) : AbandonResult

    /** A wallet created here, never backed up, holds something: it stays, and opens as any wallet. */
    data object Refused : AbandonResult
}

/**
 * How far adding a mint has looked ([Wallet.addMint]): per account index, in order, the keys looked up so far and what the found notes
 * hold.
 */
data class ScanProgress(val accounts: List<AccountScan>) {
    /** [done]: every key set of the account was looked through. */
    data class AccountScan(val index: Long, val keysChecked: Int, val foundMsat: Long, val done: Boolean)
}

internal class Onboarding(private val wallet: Wallet) {
    private val addresses = AddressCheck(wallet)

    /** Claims [username] on [host] as the active account's primary address and confirms it by looking the address up afterwards. */
    suspend fun claimAddress(username: String, host: String): OnboardResult {
        val name = normalizeUsername(username) ?: return OnboardResult.UsernameInvalid(USERNAME_RULE)
        val h = normalizeHost(host) ?: return OnboardResult.MintInvalid("not a valid host")
        val accountId = wallet.store.currentAccountId() ?: return OnboardResult.MintInvalid("no account")
        return claim(name, h, accountId, primary = true)
    }

    /** See [Wallet.createWallet]: the wallet row, then its phrase and its first account, each before anything of it. */
    suspend fun createWallet(name: String): CreateAccountResult {
        if (!wallet.canAddWallet.first()) return CreateAccountResult.AccountLimitReached(wallet.maxWallets)
        val id = UUID.randomUUID().toString()
        val before = wallet.store.currentAccountId()
        wallet.store.saveWallet(WalletRecord(id, name, wallet.now(), setupStep = SetupStep.Mint, activeBeforeSetup = before))
        wallet.createSeed(id)
        wallet.store.saveAccount(Account(id, name, wallet.now()))
        wallet.store.setActiveAccount(id)
        return CreateAccountResult.Created(id)
    }

    /** See [Wallet.createAccount]. */
    suspend fun createAccount(name: String): CreateAccountResult {
        val active = wallet.activeId()
        if (wallet.siblings(active).size >= wallet.maxAccounts) return CreateAccountResult.AccountLimitReached(wallet.maxAccounts)
        val account = sibling(UUID.randomUUID().toString(), name)
        wallet.store.saveAccount(account)
        wallet.store.setActiveAccount(account.id)
        return CreateAccountResult.Created(account.id)
    }

    /**
     * The active wallet's next account: its lowest free index, so a restore, which stops at the first unused one, finds no gap. It starts
     * with the active account's home mint.
     */
    private suspend fun sibling(accountId: String, name: String): Account {
        val active = wallet.activeId()
        val siblings = wallet.siblings(active)
        val used = siblings.map { it.index }.toSet()
        val index = generateSequence(0L) { it + 1 }.first { it !in used }
        return Account(accountId, name, wallet.now(), siblings.first().walletId, index, wallet.homeOf(active))
    }

    /**
     * Registers [username] as another address of the active account, on [host] or else its home mint: the primary one only if the account
     * has none.
     */
    suspend fun addAddress(username: String, limit: Int, host: String? = null): OnboardResult {
        val name = normalizeUsername(username) ?: return OnboardResult.UsernameInvalid(USERNAME_RULE)
        val accountId = wallet.store.currentAccountId() ?: return OnboardResult.MintInvalid("no account")
        val h =
            host?.let { normalizeHost(it) ?: return OnboardResult.MintInvalid("not a valid host") }
                ?: wallet.homeOf(accountId)
                ?: return OnboardResult.MintInvalid("no mint")
        val onHost = wallet.store.addresses(accountId).first().filter { it.host == h }
        if (onHost.none { it.username == name } && onHost.size >= limit) return OnboardResult.LimitReached(limit)
        return claim(name, h, accountId, primary = false)
    }

    /**
     * Frees [address] at its mint with an unregister proof. Whatever the mint answers, a lookup afterwards decides: only a name that is
     * gone (or no longer ours) leaves the account.
     */
    suspend fun unregister(accountId: String, address: RegisteredAddress): UnregisterResult {
        val outcome =
            try {
                unregisterAt(accountId, address)
            } catch (e: IOException) {
                return UnregisterResult.NetworkError(e.message ?: "network error")
            }
        val remove = suspend {
            wallet.store.deleteAddress(accountId, address.username, address.host)
            wallet.setAddressStatus(accountId, address.address, null)
        }
        return when (val after = addresses.check(address.username, address.host, accountId)) {
            AddressStatus.Available -> {
                remove()
                UnregisterResult.Unregistered
            }
            AddressStatus.Taken -> {
                remove()
                UnregisterResult.NotOurs
            }
            AddressStatus.Ours,
            AddressStatus.Unconfirmed -> {
                wallet.setAddressStatus(accountId, address.address, after)
                UnregisterResult.Failed((outcome as? RegistrationOutcome.Rejected)?.reason ?: "the address is still registered")
            }
            is AddressStatus.Invalid -> UnregisterResult.Failed(after.reason)
            is AddressStatus.CheckFailed -> UnregisterResult.NetworkError(after.reason)
        }
    }

    /** Sends the unregistration [address]'s mint speaks; a lookup afterwards decides what happened. */
    private suspend fun unregisterAt(accountId: String, address: RegisteredAddress): RegistrationOutcome {
        val h = address.host
        val domain = spendDomain(h)
        val keys = wallet.keys(accountId, h)
        return when (wallet.dialect(h)) {
            MintDialect.Current -> {
                val sig =
                    address.share?.let { AddressProof.unregister(keys.share(it), domain, address.username) }
                        ?: wallet.legacy.unregisterProof(keys, domain, address.username)
                wallet.client.unregister(h, address.username, sig)
            }
            MintDialect.Legacy -> wallet.legacy.unregister(keys, address)
        }
    }

    /** Claims [name] on [h] for the account: as its primary address with [primary], else only if it has none. */
    private suspend fun claim(name: String, h: String, accountId: String, primary: Boolean): OnboardResult {
        when (val connected = connectMint(wallet.walletOf(accountId), h)) {
            is ConnectResult.Failed -> return connected.result
            is ConnectResult.Connected -> Unit
        }
        val ready = suspend { confirmed: Boolean, share: Long? ->
            ready(accountId, name, host = h, confirmed, share, primary)
        }

        // Check first: the name may already be ours (an earlier attempt whose answer was lost) or someone else's.
        val before = addresses.ownership(name, h, accountId)
        when (before.status) {
            AddressStatus.Ours -> return ready(true, before.share)
            AddressStatus.Taken,
            AddressStatus.Unconfirmed ->
                return wallet.legacy.takeBack(name, h, accountId)?.let { ready(true, it) } ?: taken(name, accountId, h)
            is AddressStatus.Invalid -> return OnboardResult.UsernameInvalid(before.status.reason)
            is AddressStatus.CheckFailed -> return OnboardResult.NetworkError(before.status.reason)
            AddressStatus.Available -> Unit
        }
        val dialect =
            try {
                wallet.dialect(h)
            } catch (e: IOException) {
                return OnboardResult.NetworkError(e.message ?: "network error")
            }
        return when (dialect) {
            MintDialect.Current -> {
                val held = wallet.store.addresses(accountId).first().firstOrNull { it.username == name && it.host == h }
                val invoice = held?.feeInvoice?.let(Bolt11::decode)?.takeUnless { it.isExpired(wallet.now()) }
                if (held != null && invoice?.amountMsat != null) return OnboardResult.AwaitingFee(held, invoice.amountMsat!!)
                claimShare(name, h, accountId, ready) { n, pr -> hold(pr, n, name, h, accountId) }
            }
            MintDialect.Legacy -> wallet.legacy.claim(this, name, h, accountId, ready)
        }
    }

    /**
     * Registers [name] for the account's next share (LUD-26 POST). The share is reserved durably before its `cx1` goes out, so a lost
     * answer is settled by asking the mint about that share.
     */
    private suspend fun claimShare(
        name: String,
        h: String,
        accountId: String,
        ready: suspend (Boolean, Long?) -> OnboardResult,
        hold: suspend (Long, String) -> OnboardResult,
    ): OnboardResult {
        val domain = spendDomain(h)
        val keys = wallet.keys(accountId, h)
        for (attempt in 0 until wallet.settings.addressRegistrationMaxSharesTried) {
            val n = wallet.store.reserveKeyIndex(accountId, domain, KeyCounter.Shares)
            val share = keys.share(n)
            when (val outcome = wallet.client.register(h, name, share.cx1(), AddressProof.register(share, domain, name))) {
                is RegistrationOutcome.Rejected ->
                    when (outcome.kind) {
                        // Another phone with this phrase registered that share already: take the next one.
                        RegistrationRejection.Cx1AlreadyUsed -> continue
                        else -> {
                            // The mint refused the name, not the share: the next address gets this share, so a restore meets no gap.
                            giveBackShare(accountId, domain, n)
                            return if (addresses.check(name, h, accountId) == AddressStatus.Taken) taken(name, accountId, h)
                            else OnboardResult.RegistrationFailed(outcome.reason)
                        }
                    }
                is RegistrationOutcome.AwaitingFee -> return hold(n, outcome.invoice)
                // The answer may have been lost; the lookup below shows whether it landed.
                is RegistrationOutcome.Ambiguous,
                RegistrationOutcome.Registered -> {
                    val after = addresses.ownership(name, h, accountId, also = n)
                    return settle(after.status, name, h, accountId) { confirmed -> ready(confirmed, after.share ?: n) }
                }
            }
        }
        return OnboardResult.RegistrationFailed("no free share")
    }

    /** Share [n], reserved for a registration the mint refused, is the next one handed out again; claims hold the wallet's lock. */
    private suspend fun giveBackShare(accountId: String, domain: String, n: Long) {
        if (wallet.store.keyIndexOrNull(accountId, domain, KeyCounter.Shares) == n + 1) {
            wallet.store.setKeyIndex(accountId, domain, KeyCounter.Shares, n)
        }
    }

    /**
     * Keeps the name the mint holds for [invoice], if it asks what it advertises and the app allows. A held address is not registered yet:
     * a refresh finishes it when it is paid (see [settleHeld]).
     */
    private suspend fun hold(
        invoice: String,
        share: Long,
        name: String,
        h: String,
        accountId: String,
    ): OnboardResult {
        val advertised =
            try {
                wallet.client.servicePayRequest(h).fee?.addressMsat ?: 0
            } catch (e: IOException) {
                return OnboardResult.NetworkError(e.message ?: "network error")
            } catch (e: ProtocolViolation) {
                return OnboardResult.MintInvalid(e.message ?: "unexpected response")
            }
        val amount = Bolt11.decode(invoice)?.amountMsat ?: return OnboardResult.RegistrationFailed("the fee invoice can't be read")
        val max = minOf(advertised, wallet.maxAddressFeeMsat ?: advertised)
        if (amount > max) return OnboardResult.FeeRefused(amount, max)
        val address = RegisteredAddress(name, h, wallet.now(), share, invoice)
        wallet.store.saveAddress(accountId, address)
        return OnboardResult.AwaitingFee(address, amount)
    }

    /**
     * Asks the mint about a held [address]: registered means the fee was paid, so it becomes a full address (the primary one if the account
     * has none); free with its invoice expired means the hold is over, and the name can be registered again.
     */
    suspend fun settleHeld(accountId: String, address: RegisteredAddress) {
        val after = addresses.ownership(address.username, address.host, accountId, also = address.share)
        when (after.status) {
            AddressStatus.Ours ->
                ready(accountId, address.username, address.host, true, after.share ?: address.share, primary = false, activate = false)
            AddressStatus.Available ->
                if (address.feeInvoice?.let(Bolt11::decode)?.isExpired(wallet.now()) != false) {
                    wallet.store.deleteAddress(accountId, address.username, address.host)
                }
            else -> Unit
        }
    }

    /** What a registration came to, by the lookup after it. */
    internal suspend fun settle(
        after: AddressStatus,
        name: String,
        h: String,
        accountId: String,
        ready: suspend (confirmed: Boolean) -> OnboardResult,
    ): OnboardResult =
        when (after) {
            AddressStatus.Ours -> ready(true)
            AddressStatus.Unconfirmed -> ready(false)
            AddressStatus.Taken -> taken(name, accountId, h)
            AddressStatus.Available -> OnboardResult.RegistrationFailed("the address was not registered")
            is AddressStatus.Invalid -> OnboardResult.UsernameInvalid(after.reason)
            is AddressStatus.CheckFailed -> OnboardResult.NetworkError(after.reason)
        }

    /**
     * Connects the wallet [walletId] to the mint at [host]: fetches what it is and pins its key for that wallet on first contact (only
     * [expectedKey], if given). The wallet's first mint becomes its home mint.
     */
    suspend fun connectMint(walletId: String, host: String, expectedKey: String? = null): ConnectResult {
        val h = normalizeHost(host) ?: return ConnectResult.Failed(OnboardResult.MintInvalid("not a valid host"))
        return try {
            val pay = wallet.client.servicePayRequest(h)
            val discovered = runCatching { wallet.client.withdrawDiscovery(h).mintPubkey }.getOrNull()
            val existing = wallet.store.mint(walletId, h)
            val pinned = existing?.mintPubkey
            if (
                pinned != null && discovered != null && pinned != discovered.toHex() ||
                    expectedKey != null && expectedKey != discovered?.toHex()
            ) {
                return ConnectResult.Failed(OnboardResult.MintInvalid(MINT_KEY_CHANGED))
            }
            val mint =
                Mint(
                    walletId = walletId,
                    host = h,
                    withdrawUrl = pay.withdrawUrl,
                    mintPubkey = pinned ?: discovered?.toHex(),
                    minSendableMsat = pay.pay.minSendable,
                    maxSendableMsat = pay.pay.maxSendable,
                    feeBaseMsat = pay.fee?.baseMsat,
                    feePpm = pay.fee?.ppm,
                )
            wallet.store.saveMint(mint)
            // An account of the wallet without a home takes this one.
            wallet.store.accounts
                .first()
                .map { it.account }
                .filter { it.walletId == walletId && it.homeHost == null }
                .forEach {
                    wallet.store.saveAccount(it.copy(homeHost = h))
                }
            ConnectResult.Connected(mint)
        } catch (e: ProtocolViolation) {
            ConnectResult.Failed(OnboardResult.MintInvalid(e.message ?: "not an LNURLcash server"))
        } catch (e: IOException) {
            ConnectResult.Failed(OnboardResult.NetworkError(e.message ?: "network error"))
        }
    }

    /** Saves [name]@[host] as a registered address of the account: its primary one with [primary], or if it has none. */
    private suspend fun ready(
        accountId: String,
        name: String,
        host: String,
        confirmed: Boolean,
        share: Long?,
        primary: Boolean,
        activate: Boolean = true,
    ): OnboardResult.Ready {
        val known = wallet.store.addresses(accountId).first().firstOrNull { it.username == name && it.host == host }
        val address = RegisteredAddress(name, host, known?.addedAtMillis ?: wallet.now(), share ?: known?.share)
        if (known == null || known.feeInvoice != null || (share != null && known.share != share)) {
            wallet.store.saveAddress(accountId, address)
        }
        // A restored account learns how many shares it handed out from the ones it proves.
        if (share != null && (wallet.store.keyIndexOrNull(accountId, spendDomain(host), KeyCounter.Shares) ?: 0) <= share) {
            wallet.store.setKeyIndex(accountId, spendDomain(host), KeyCounter.Shares, share + 1)
        }
        if (primary || wallet.store.currentPrimaryAddress(accountId) == null) wallet.store.setPrimaryAddress(accountId, name, host)
        if (activate) wallet.store.setActiveAccount(accountId)
        wallet.setAddressStatus(accountId, address.address, if (confirmed) AddressStatus.Ours else AddressStatus.Unconfirmed)
        return OnboardResult.Ready(address, confirmed)
    }

    /** A taken seed-derived name suggests the next derived ones first, so a restore can still find the address. */
    internal suspend fun taken(name: String, accountId: String, h: String): OnboardResult {
        val derived = wallet.keysIfSeeded(accountId, h)?.let(DefaultAddress::names).orEmpty()
        val next =
            listOf(derived, wallet.legacy.names(accountId, h)).firstOrNull { name in it }?.dropWhile { it != name }?.drop(1).orEmpty()
        return OnboardResult.UsernameTaken((next + suggestions(name, wallet.random)).distinct().take(3))
    }

    companion object {
        const val USERNAME_RULE = "use 1-32 lowercase letters, digits, '.', '-' or '_'"
        private val USERNAME = Regex("^[a-z0-9._-]{1,32}$")
        private val HOST = Regex("^[a-z0-9.-]+(:[0-9]{1,5})?$")

        fun normalizeUsername(value: String): String? = value.trim().lowercase().takeIf(USERNAME::matches)

        fun normalizeHost(value: String): String? =
            value.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/').takeIf(HOST::matches)

        /** Alternatives to a taken name: two numbered variants and one fresh random name. */
        fun suggestions(name: String, random: SecureRandom): List<String> {
            val base = name.take(28)
            return listOf("$base${10 + random.nextInt(90)}", "$base${1000 + random.nextInt(9000)}", UsernameSuggestions.random(random))
                .distinct()
        }
    }
}
