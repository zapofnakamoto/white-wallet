package whitewallet.core.wallet

import kotlinx.coroutines.flow.first
import whitewallet.core.model.Account
import whitewallet.core.model.Mint
import whitewallet.core.model.Profile
import whitewallet.core.model.RegisteredAddress
import whitewallet.lnurlcash.AddressProof
import whitewallet.lnurlcash.client.RegistrationOutcome
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.spendDomain
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID

sealed interface OnboardResult {
    /**
     * The address is saved. [confirmed] is true when the mint publishes our own `cx1` for it, which proves
     * payments to it reach this wallet; false when the mint accepted it but does not expose a `cx1` to check.
     */
    data class Ready(val profile: Profile, val confirmed: Boolean) : OnboardResult

    /** Someone else has this name. [suggestions] are alternatives the user can pick from. */
    data class UsernameTaken(val suggestions: List<String>) : OnboardResult

    data class UsernameInvalid(val reason: String) : OnboardResult

    /** The mint refused the registration for another reason, e.g. registration is turned off. */
    data class RegistrationFailed(val reason: String?) : OnboardResult

    data class MintInvalid(val reason: String) : OnboardResult

    data class NetworkError(val reason: String) : OnboardResult

    /** The account already holds [limit] addresses on this mint (see [Wallet.addAddress]). Nothing changed. */
    data class LimitReached(val limit: Int) : OnboardResult

    /** The wallet already holds [limit] accounts, the most this app allows ([Wallet.maxAccounts]). Nothing changed. */
    data class AccountLimitReached(val limit: Int) : OnboardResult
}

/** One of an account's Lightning addresses, whether it is the primary one, and its last check (null: not checked). */
data class AddressEntry(val address: RegisteredAddress, val primary: Boolean, val status: AddressStatus?)

sealed interface UnregisterResult {
    /** The mint freed the name; it is gone from the account. */
    data object Unregistered : UnregisterResult

    /** The name no longer points at this account (someone else holds it); removed from the account. */
    data object NotOurs : UnregisterResult

    /** Refused: the primary address can't be unregistered. Make another address primary first. */
    data object IsPrimary : UnregisterResult

    /** The mint kept the name; nothing changed. */
    data class Failed(val reason: String) : UnregisterResult

    /** Not known whether it worked; the address stays listed until a check shows it is free. */
    data class NetworkError(val reason: String) : UnregisterResult
}

sealed interface ConnectResult {
    data class Connected(val mint: Mint) : ConnectResult
    data class Failed(val result: OnboardResult) : ConnectResult
}

/** The outcome of [Wallet.createAccount]. */
sealed interface CreateAccountResult {
    /** The account exists and is active. */
    data class Created(val accountId: String) : CreateAccountResult

    /** The wallet already holds [limit] accounts, the most this app allows ([Wallet.maxAccounts]). Nothing changed. */
    data class AccountLimitReached(val limit: Int) : CreateAccountResult
    data class MintInvalid(val reason: String) : CreateAccountResult
    data class NetworkError(val reason: String) : CreateAccountResult
}

internal class Onboarding(private val wallet: Wallet) {
    private val addresses = AddressCheck(wallet)

    /**
     * Claims [username] on [host] and confirms it by looking the address up afterwards. Never registers a different
     * name than the one asked for. A new account (see [Wallet.onboard]) gets a fresh recovery phrase, which is
     * discarded again unless the claim succeeds.
     */
    suspend fun onboard(username: String, host: String, newAccountName: String?): OnboardResult {
        val name = normalizeUsername(username) ?: return OnboardResult.UsernameInvalid(USERNAME_RULE)
        val h = normalizeHost(host) ?: return OnboardResult.MintInvalid("not a valid host")
        val existing = wallet.store.currentAccountId()
        val creating = newAccountName != null || existing == null
        if (creating && wallet.store.accounts.first().size >= wallet.maxAccounts) return OnboardResult.AccountLimitReached(wallet.maxAccounts)
        val accountId = if (creating) UUID.randomUUID().toString() else existing
        if (creating) wallet.createSeed(accountId)
        val result = claim(name, h, accountId, creating, newAccountName?.trim()?.ifEmpty { null } ?: DEFAULT_ACCOUNT_NAME)
        // Kept for the next attempt, so the seed-derived names offered stay the same.
        if (creating && result !is OnboardResult.Ready) wallet.discardSeed(accountId, keepForRetry = true)
        return result
    }

    /**
     * Registers [username] as another address of the active account on its home mint: non-primary, unless it is the
     * account's first.
     */
    suspend fun addAddress(username: String, limit: Int, host: String? = null): OnboardResult {
        val name = normalizeUsername(username) ?: return OnboardResult.UsernameInvalid(USERNAME_RULE)
        val accountId = wallet.store.currentAccountId() ?: return OnboardResult.MintInvalid("no account")
        val home = wallet.store.account(accountId)?.host ?: return OnboardResult.MintInvalid("no account")
        val h = host?.let { normalizeHost(it) ?: return OnboardResult.MintInvalid("not a valid host") } ?: home
        val onHost = wallet.store.addresses(accountId).first().filter { it.host == h }
        if (onHost.none { it.username == name } && onHost.size >= limit) return OnboardResult.LimitReached(limit)
        // The primary address is always on the home mint; one on another mint is an extra way to get paid.
        val first = h == home && wallet.store.currentProfile(accountId) == null
        return claim(name, h, accountId, creating = false, accountName = "", primary = first)
    }

    /** See [Wallet.createAccount]. The new recovery phrase is kept only if the mint answers as one. */
    suspend fun createAccount(host: String, name: String): CreateAccountResult {
        val h = normalizeHost(host) ?: return CreateAccountResult.MintInvalid("not a valid host")
        if (wallet.store.accounts.first().size >= wallet.maxAccounts) return CreateAccountResult.AccountLimitReached(wallet.maxAccounts)
        when (val connected = connectMint(h)) {
            is ConnectResult.Connected -> Unit
            is ConnectResult.Failed -> return when (val r = connected.result) {
                is OnboardResult.NetworkError -> CreateAccountResult.NetworkError(r.reason)
                is OnboardResult.MintInvalid -> CreateAccountResult.MintInvalid(r.reason)
                else -> CreateAccountResult.MintInvalid(r.toString())
            }
        }
        val accountId = UUID.randomUUID().toString()
        wallet.createSeed(accountId)
        wallet.store.saveAccount(Account(accountId, name.trim().ifEmpty { DEFAULT_ACCOUNT_NAME }, wallet.now(), h))
        wallet.store.setActiveAccount(accountId)
        return CreateAccountResult.Created(accountId)
    }

    /**
     * Frees [address] at its mint with an unregister proof. Whatever the mint answers, a lookup afterwards
     * decides: only a name that is gone (or no longer ours) leaves the account.
     */
    suspend fun unregister(accountId: String, address: RegisteredAddress): UnregisterResult {
        val primary = wallet.store.currentProfile(accountId)
        if (primary?.username == address.username && primary.host == address.host) return UnregisterResult.IsPrimary
        val branch = wallet.branch(accountId, address.host)
        val sig = AddressProof.sign(branch, AddressProof.Action.Unregister, spendDomain(address.host), address.username)
        val outcome = wallet.client.unregister(address.host, address.username, sig)
        val remove = suspend {
            wallet.store.deleteAddress(accountId, address.username, address.host)
            wallet.setAddressStatus(accountId, address.address, null)
        }
        return when (val after = addresses.check(address.username, address.host, accountId)) {
            AddressStatus.Available -> { remove(); UnregisterResult.Unregistered }
            AddressStatus.Taken -> { remove(); UnregisterResult.NotOurs }
            AddressStatus.Ours, AddressStatus.Unconfirmed -> {
                wallet.setAddressStatus(accountId, address.address, after)
                UnregisterResult.Failed((outcome as? RegistrationOutcome.Rejected)?.reason ?: "the address is still registered")
            }
            is AddressStatus.Invalid -> UnregisterResult.Failed(after.reason)
            is AddressStatus.CheckFailed -> UnregisterResult.NetworkError(after.reason)
        }
    }

    private suspend fun claim(name: String, h: String, accountId: String, creating: Boolean, accountName: String, primary: Boolean = true): OnboardResult {
        when (val connected = connectMint(h)) {
            is ConnectResult.Failed -> return connected.result
            is ConnectResult.Connected -> Unit
        }
        val ready = suspend { confirmed: Boolean -> ready(accountId, creating, accountName, name, h, confirmed, primary) }

        // Check first: the name may already be ours (an earlier attempt whose answer was lost) or someone else's.
        when (val before = addresses.check(name, h, accountId)) {
            AddressStatus.Ours -> return ready(true)
            AddressStatus.Taken, AddressStatus.Unconfirmed -> return taken(name, accountId, h)
            is AddressStatus.Invalid -> return OnboardResult.UsernameInvalid(before.reason)
            is AddressStatus.CheckFailed -> return OnboardResult.NetworkError(before.reason)
            AddressStatus.Available -> Unit
        }

        val branch = wallet.branch(accountId, h)
        val sig = AddressProof.sign(branch, AddressProof.Action.Register, spendDomain(h), name)
        when (val outcome = wallet.client.register(h, name, branch.cx1(), sig)) {
            is RegistrationOutcome.Rejected -> {
                // Lost a race for the name, or the mint refuses registrations. Look again to tell which.
                return if (addresses.check(name, h, accountId) == AddressStatus.Taken) taken(name, accountId, h) else OnboardResult.RegistrationFailed(outcome.reason)
            }
            // The answer was lost; the lookup below shows whether it landed.
            is RegistrationOutcome.Ambiguous, RegistrationOutcome.Registered -> Unit
        }

        return when (val after = addresses.check(name, h, accountId)) {
            AddressStatus.Ours -> ready(true)
            AddressStatus.Unconfirmed -> ready(false)
            AddressStatus.Taken -> taken(name, accountId, h)
            AddressStatus.Available -> OnboardResult.RegistrationFailed("the address was not registered")
            is AddressStatus.Invalid -> OnboardResult.UsernameInvalid(after.reason)
            is AddressStatus.CheckFailed -> OnboardResult.NetworkError(after.reason)
        }
    }

    suspend fun connectMint(host: String): ConnectResult {
        val h = normalizeHost(host) ?: return ConnectResult.Failed(OnboardResult.MintInvalid("not a valid host"))
        return try {
            val pay = wallet.client.servicePayRequest(h)
            val discovered = runCatching { wallet.client.withdrawDiscovery(h).mintPubkey }.getOrNull()
            val existing = wallet.store.mint(h)
            val pinned = existing?.mintPubkey
            if (pinned != null && discovered != null && pinned != discovered.toHex()) {
                return ConnectResult.Failed(OnboardResult.MintInvalid("the signing key of this host changed"))
            }
            val mint = Mint(
                host = h,
                withdrawUrl = pay.withdrawUrl,
                mintPubkey = pinned ?: discovered?.toHex(),
                minSendableMsat = pay.pay.minSendable,
                maxSendableMsat = pay.pay.maxSendable,
                feeBaseMsat = pay.fee?.baseMsat,
                feePpm = pay.fee?.ppm,
            )
            wallet.store.saveMint(mint)
            ConnectResult.Connected(mint)
        } catch (e: ProtocolViolation) {
            ConnectResult.Failed(OnboardResult.MintInvalid(e.message ?: "not an LNURLcash server"))
        } catch (e: IOException) {
            ConnectResult.Failed(OnboardResult.NetworkError(e.message ?: "network error"))
        }
    }

    private suspend fun ready(
        accountId: String,
        creating: Boolean,
        accountName: String,
        name: String,
        host: String,
        confirmed: Boolean,
        primary: Boolean,
    ): OnboardResult.Ready {
        if (creating) wallet.store.saveAccount(Account(accountId, accountName, wallet.now(), host))
        val profile = Profile(name, host)
        if (primary) {
            // A primary address on another mint moves the account's home there.
            wallet.store.account(accountId)?.takeIf { it.host != host }?.let { wallet.store.saveAccount(it.copy(host = host)) }
            wallet.store.saveProfile(accountId, profile)
            wallet.store.setActiveAccount(accountId)
        } else if (wallet.store.addresses(accountId).first().none { it.username == name && it.host == host }) {
            wallet.store.saveAddress(accountId, RegisteredAddress(name, host, wallet.now()))
        }
        wallet.setAddressStatus(accountId, profile.address, if (confirmed) AddressStatus.Ours else AddressStatus.Unconfirmed)
        return OnboardResult.Ready(profile, confirmed)
    }

    /** A taken seed-derived name suggests the next derived ones first, so a restore can still find the address. */
    private suspend fun taken(name: String, accountId: String, h: String): OnboardResult {
        val derived = wallet.branchIfSeeded(accountId, h)?.let(DefaultAddress::names).orEmpty()
        val next = if (name in derived) derived.dropWhile { it != name }.drop(1) else emptyList()
        return OnboardResult.UsernameTaken((next + suggestions(name, wallet.random)).distinct().take(3))
    }

    companion object {
        const val DEFAULT_ACCOUNT_NAME = "Main"
        const val USERNAME_RULE = "use 1-32 lowercase letters, digits, '.', '-' or '_'"
        private val USERNAME = Regex("^[a-z0-9._-]{1,32}$")
        private val HOST = Regex("^[a-z0-9.-]+(:[0-9]{1,5})?$")

        fun normalizeUsername(value: String): String? = value.trim().lowercase().takeIf(USERNAME::matches)

        fun normalizeHost(value: String): String? =
            value.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/').takeIf(HOST::matches)

        /** Alternatives to a taken name: two numbered variants and one fresh random name. */
        fun suggestions(name: String, random: SecureRandom): List<String> {
            val base = name.take(28)
            return listOf("$base${10 + random.nextInt(90)}", "$base${1000 + random.nextInt(9000)}", UsernameSuggestions.random(random)).distinct()
        }
    }
}
