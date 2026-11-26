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
import whitewallet.lnurlcash.client.RegistrationOutcome
import whitewallet.lnurlcash.client.RegistrationRejection
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.ServiceRefused
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

    /** The name breaks the rule ([AddressProblem.Name]). */
    data object UsernameInvalid : OnboardResult

    /**
     * The mint refused the registration for another reason, e.g. registration is turned off, or it didn't register the name. [mintReason]
     * is the mint's own words for it, if it gave any.
     */
    data class RegistrationFailed(val mintReason: String? = null) : OnboardResult

    /** The mint can't be used, for [refusal]; [mintReason] is the mint's own words for it, if it gave any (often English). */
    data class MintInvalid(val refusal: MintRefusal, val mintReason: String? = null) : OnboardResult

    data class NetworkError(val reason: String) : OnboardResult

    /** The account already holds [limit] addresses on this mint (see [Wallet.addAddress]). Nothing changed. */
    data class LimitReached(val limit: Int) : OnboardResult
}

/** One of an account's Lightning addresses, whether it is the primary one, and its last check (null: not checked). */
data class AddressEntry(val address: RegisteredAddress, val primary: Boolean, val status: AddressStatus?)

sealed interface UnregisterResult {
    /** The mint freed the name; it is gone from the account. */
    data object Unregistered : UnregisterResult

    /** The name no longer points at this account (someone else holds it); removed from the account. */
    data object NotOurs : UnregisterResult

    /** The mint kept the name; nothing changed. [mintReason] is the mint's own words for refusing, if it gave any. */
    data class Failed(val mintReason: String? = null) : UnregisterResult

    /** Not known whether it worked; the address stays listed until a check shows it is free. */
    data object NetworkError : UnregisterResult
}

/**
 * Why a mint can't be used ([OnboardResult.MintInvalid], [MintPreview.Invalid]). Never shown as it is: each screen says it in the user's
 * words.
 */
enum class MintRefusal {
    /** What was given isn't a host name. */
    NotAHost,

    /** There is no account to use it with. */
    NoAccount,

    /** No mint was given and the account has no home mint. */
    NoMint,

    /** It presents another signing key than the one pinned, or than the one it showed a moment ago. */
    KeyChanged,

    /** It publishes no signing key: the wallet uses no such mint (a note from one comes in over Lightning). */
    WithoutSigningKey,

    /** It is the account's home mint already. */
    AlreadyHome,

    /** It answers, but not as an LNURLcash mint the wallet can read. */
    NotAMint,

    /** It refused what it was asked ([OnboardResult.MintInvalid.mintReason]: its words, if any). */
    Refused,
}

/** A mint's answer the wallet can't act on: its refusal, in its own words, or one that isn't a mint's answer. */
internal fun ProtocolViolation.mintInvalid(): OnboardResult.MintInvalid =
    if (this is ServiceRefused) OnboardResult.MintInvalid(MintRefusal.Refused, reason) else OnboardResult.MintInvalid(MintRefusal.NotAMint)

/** What an address that can't be looked up means for adding it: a name that breaks the rule, or a mint that isn't a host. */
private fun AddressStatus.Invalid.result(): OnboardResult =
    when (problem) {
        AddressProblem.Name -> OnboardResult.UsernameInvalid
        AddressProblem.Host -> OnboardResult.MintInvalid(MintRefusal.NotAHost)
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

    /** Looking for what the account already holds stopped at the wallet's mint [host] ([result]). Nothing changed. */
    data class ScanFailed(val host: String, val result: OnboardResult) : CreateAccountResult

    /** The wallet already holds [limit] accounts, or the app [limit] wallets, the most it allows. Nothing changed. */
    data class AccountLimitReached(val limit: Int) : CreateAccountResult

    /** [first] has to receive money before another account is added ([Wallet.accountToUseFirst]). Nothing changed. */
    data class UseFirst(val first: UnusedAccount) : CreateAccountResult
}

/**
 * The outcome of [Wallet.scan]: what it added to the wallet's money ([foundMsat]: notes the phone didn't know), and the mints where it
 * stopped before its end ([stoppedAt]), none if it got through everywhere.
 */
data class ScanResult(val foundMsat: Long, val stoppedAt: List<String>)

/** The outcome of [Wallet.removeAccount]. */
sealed interface RemoveAccountResult {
    /** The account is gone from this phone; [nowActive] is the account of its wallet in use instead. */
    data class Removed(val nowActive: String) : RemoveAccountResult

    /** It is its wallet's only account: removing it is deleting the wallet. Nothing changed. */
    data object LastAccount : RemoveAccountResult

    /** It still holds something ([use]). Nothing changed. */
    data class InUse(val use: AccountUse) : RemoveAccountResult
}

/** What keeps an account from being removed. */
enum class AccountUse {
    /** A note that isn't spent, or a received note not claimed yet. */
    Money,

    /** An operation not settled yet. */
    OpenOperation,

    /** A received note in the archive: its spend is only on this phone, and would go with the account. */
    ArchivedNotes,
}

/**
 * What removing the active account would meet ([Wallet.accountRemoval]): what stands against it ([blocked], null if nothing), the money it
 * holds, and its Lightning addresses, which stay registered at their mints and would no longer be looked at.
 */
data class AccountRemoval(val blocked: RemoveAccountResult?, val heldMsat: Long, val addresses: List<String>)

/** The outcome of [Wallet.abandonSetup]. */
sealed interface AbandonResult {
    /** The wallet is gone from this device; [remains]: another account is active now. */
    data class Abandoned(val remains: Boolean) : AbandonResult

    /** A wallet created here, never backed up, holds something: it stays, and opens as any wallet. */
    data object Refused : AbandonResult
}

/**
 * How far adding a mint ([Wallet.addMint]) or a scan ([Wallet.scan]) has looked: per account index, in order, the keys looked up so far and
 * what the found notes hold. Then, on a scan, the notes the wallet already holds there are looked up: [notesChecked] of [notesToCheck].
 */
data class ScanProgress(val accounts: List<AccountScan>, val notesChecked: Int = 0, val notesToCheck: Int = 0) {
    /** [done]: every key set of the account was looked through. */
    data class AccountScan(val index: Long, val keysChecked: Int, val foundMsat: Long, val done: Boolean)
}

internal class Onboarding(private val wallet: Wallet) {
    private val addresses = AddressCheck(wallet)

    /** Claims [username] on [host] as the active account's primary address and confirms it by looking the address up afterwards. */
    suspend fun claimAddress(username: String, host: String): OnboardResult {
        val name = normalizeUsername(username) ?: return OnboardResult.UsernameInvalid
        val h = normalizeHost(host) ?: return OnboardResult.MintInvalid(MintRefusal.NotAHost)
        val accountId = wallet.store.currentAccountId() ?: return OnboardResult.MintInvalid(MintRefusal.NoAccount)
        return claim(name, h, accountId, primary = true)
    }

    /** See [Wallet.createWallet]: the wallet row, then its phrase and its first account, each before anything of it. */
    suspend fun createWallet(name: String): CreateAccountResult {
        if (!wallet.canAddWallet.first()) return CreateAccountResult.AccountLimitReached(wallet.maxWallets)
        val id = UUID.randomUUID().toString()
        val before = wallet.store.currentAccountId()
        wallet.store.saveWallet(WalletRecord(id, null, wallet.now(), setupStep = SetupStep.Mint, activeBeforeSetup = before))
        wallet.createSeed(id)
        wallet.store.saveAccount(Account(id, name, wallet.now()))
        wallet.store.setActiveAccount(id)
        return CreateAccountResult.Created(id)
    }

    /** See [Wallet.createAccount]: [scan] looks for what the saved account already holds, and says why it stopped if it did. */
    suspend fun createAccount(name: String, scan: suspend (Account) -> CreateAccountResult.ScanFailed?): CreateAccountResult {
        val active = wallet.activeId()
        if (wallet.siblings(active).size >= wallet.maxAccounts) return CreateAccountResult.AccountLimitReached(wallet.maxAccounts)
        val walletId = wallet.walletOf(active)
        val accounts = wallet.store.accounts.first().filter { it.account.walletId == walletId }
        accountToUseFirst(accounts, wallet.settings.scanStopAfterUnusedAccounts)?.let {
            return CreateAccountResult.UseFirst(it)
        }
        val account = sibling(UUID.randomUUID().toString(), name)
        wallet.store.saveAccount(account)
        // A scan that stopped leaves no account: one that shows less than it holds would hide money. What it found is still at the
        // mint, and the next try finds it again.
        scan(account)?.let {
            wallet.store.deleteAccount(account.id)
            return it
        }
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
        val name = normalizeUsername(username) ?: return OnboardResult.UsernameInvalid
        val accountId = wallet.store.currentAccountId() ?: return OnboardResult.MintInvalid(MintRefusal.NoAccount)
        val h =
            host?.let { normalizeHost(it) ?: return OnboardResult.MintInvalid(MintRefusal.NotAHost) }
                ?: wallet.homeOf(accountId)
                ?: return OnboardResult.MintInvalid(MintRefusal.NoMint)
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
                return UnregisterResult.NetworkError
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
                UnregisterResult.Failed((outcome as? RegistrationOutcome.Rejected)?.reason)
            }
            is AddressStatus.Invalid -> UnregisterResult.Failed()
            is AddressStatus.CheckFailed -> UnregisterResult.NetworkError
        }
    }

    /** Asks [address]'s mint to free it; a lookup afterwards decides what happened. */
    private suspend fun unregisterAt(accountId: String, address: RegisteredAddress): RegistrationOutcome {
        val h = address.host
        val sig = AddressProof.unregister(wallet.keys(accountId, h).share(address.share), spendDomain(h), address.username)
        return wallet.client.unregister(h, address.username, sig)
    }

    /** Claims [name] on [h] for the account: as its primary address with [primary], else only if it has none. */
    private suspend fun claim(name: String, h: String, accountId: String, primary: Boolean): OnboardResult {
        when (val connected = connectMint(wallet.walletOf(accountId), h)) {
            is ConnectResult.Failed -> return connected.result
            is ConnectResult.Connected -> homeIfNone(wallet.walletOf(accountId), connected.mint.host)
        }
        val ready = suspend { confirmed: Boolean, share: Long ->
            ready(accountId, name, host = h, confirmed, share, primary)
        }

        // Check first: the name may already be ours (an earlier attempt whose answer was lost) or someone else's.
        val before = addresses.ownership(name, h, accountId)
        when (before.status) {
            AddressStatus.Ours -> return ready(true, checkNotNull(before.share))
            AddressStatus.Taken,
            AddressStatus.Unconfirmed -> return taken(name, accountId, h)
            is AddressStatus.Invalid -> return before.status.result()
            is AddressStatus.CheckFailed -> return OnboardResult.NetworkError(before.status.reason)
            AddressStatus.Available -> Unit
        }
        val held = wallet.store.addresses(accountId).first().firstOrNull { it.username == name && it.host == h }
        val invoice = held?.feeInvoice?.let(Bolt11::decode)?.takeUnless { it.isExpired(wallet.now()) }
        val fee = invoice?.amountMsat
        if (held != null && fee != null) return OnboardResult.AwaitingFee(held, fee)
        return claimShare(name, h, accountId, ready) { n, pr -> hold(pr, n, name, h, accountId) }
    }

    /**
     * Registers [name] for the account's next share (LUD-26 POST). The share is reserved durably before its `cx1` goes out, so a lost
     * answer is settled by asking the mint about that share.
     */
    private suspend fun claimShare(
        name: String,
        h: String,
        accountId: String,
        ready: suspend (Boolean, Long) -> OnboardResult,
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
                        RegistrationRejection.AlreadyRegistered,
                        RegistrationRejection.UnknownUser,
                        RegistrationRejection.InvalidSignature,
                        RegistrationRejection.RateLimited,
                        RegistrationRejection.Other -> {
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
        return OnboardResult.RegistrationFailed()
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
                return e.mintInvalid()
            }
        val amount = Bolt11.decode(invoice)?.amountMsat ?: return OnboardResult.RegistrationFailed()
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
            is AddressStatus.CheckFailed,
            is AddressStatus.Invalid,
            AddressStatus.Taken,
            AddressStatus.Unconfirmed -> Unit
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
            AddressStatus.Available -> OnboardResult.RegistrationFailed()
            is AddressStatus.Invalid -> after.result()
            is AddressStatus.CheckFailed -> OnboardResult.NetworkError(after.reason)
        }

    /**
     * Connects the wallet [walletId] to the mint at [host]: fetches what it is and pins its key for that wallet on first contact (only
     * [expectedKey], if given). Nobody's home mint by that alone: where the user chose the mint for the wallet, [homeIfNone] follows. A new
     * mint is connected only once it presented its signing key: one that publishes none is never used ([MintRefusal.WithoutSigningKey]).
     */
    suspend fun connectMint(walletId: String, host: String, expectedKey: String? = null): ConnectResult {
        val h = normalizeHost(host) ?: return ConnectResult.Failed(OnboardResult.MintInvalid(MintRefusal.NotAHost))
        return try {
            val pay = wallet.client.servicePayRequest(h)
            val existing = wallet.store.mint(walletId, h)
            val pinned = existing?.mintPubkey
            // A mint the wallet has already keeps working without it. A new one is added only with its key (a failure to ask ends here).
            val discovery =
                if (existing == null) wallet.client.withdrawDiscovery(h)
                else runSuspendCatching { wallet.client.withdrawDiscovery(h) }.getOrNull()
            val discovered = discovery?.mintPubkey
            if (existing == null && discovered == null)
                return ConnectResult.Failed(OnboardResult.MintInvalid(MintRefusal.WithoutSigningKey))
            if (pinned != null && discovery != null) wallet.sawKey(walletId, h, changed = pinned != discovered?.toHex())
            // A pinned mint that answers without a valid key changed it as much as one presenting another.
            if (
                pinned != null && discovery != null && pinned != discovered?.toHex() ||
                    expectedKey != null && expectedKey != discovered?.toHex()
            ) {
                return ConnectResult.Failed(OnboardResult.MintInvalid(MintRefusal.KeyChanged))
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
                    // A mint met here for the first time was never scanned.
                    scanStartedAtMillis = existing?.scanStartedAtMillis,
                    scanFinishedAtMillis = existing?.scanFinishedAtMillis,
                )
            wallet.store.saveMint(mint)
            ConnectResult.Connected(mint)
        } catch (e: ProtocolViolation) {
            ConnectResult.Failed(e.mintInvalid())
        } catch (e: IOException) {
            ConnectResult.Failed(OnboardResult.NetworkError(e.message ?: "network error"))
        }
    }

    /**
     * The mint at [host], which the user chose for the wallet [walletId] (in its setup, a restore, by adding it), becomes the home mint of
     * each of its accounts that has none: the wallet's first mint is its home. Never for a mint the wallet only met because a note is there
     * (a note claimed from it or moved to it): a home mint is trusted by default.
     */
    suspend fun homeIfNone(walletId: String, host: String) {
        wallet.store.accounts
            .first()
            .map { it.account }
            .filter { it.walletId == walletId && it.homeHost == null }
            .forEach {
                wallet.store.saveAccount(it.copy(homeHost = host))
            }
    }

    /** Saves [name]@[host] as a registered address of the account: its primary one with [primary], or if it has none. */
    private suspend fun ready(
        accountId: String,
        name: String,
        host: String,
        confirmed: Boolean,
        share: Long,
        primary: Boolean,
        activate: Boolean = true,
    ): OnboardResult.Ready {
        val known = wallet.store.addresses(accountId).first().firstOrNull { it.username == name && it.host == host }
        val address = RegisteredAddress(name, host, known?.addedAtMillis ?: wallet.now(), share)
        if (known == null || known.feeInvoice != null || known.share != share) {
            wallet.store.saveAddress(accountId, address)
        }
        // A restored account learns how many shares it handed out from the ones it proves.
        if ((wallet.store.keyIndexOrNull(accountId, spendDomain(host), KeyCounter.Shares) ?: 0) <= share) {
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
        val next = if (name in derived) derived.dropWhile { it != name }.drop(1) else emptyList()
        return OnboardResult.UsernameTaken((next + suggestions(name, wallet.random)).distinct().take(3))
    }

    companion object {
        private val USERNAME = Regex("^[a-z0-9._-]{1,32}$")
        private val HELD_USERNAME = Regex("^[a-z0-9._-]+$")
        private val HOST = Regex("^[a-z0-9.-]+(:[0-9]{1,5})?$")

        /** A name the wallet registers: at most 32 characters, as the mint it was written for allows. */
        fun normalizeUsername(value: String): String? = value.trim().lowercase().takeIf(USERNAME::matches)

        /**
         * A name the wallet may hold already and looks up: of any length. Lightning addresses have no limit (LUD-16), and an address found
         * again may have been registered by another wallet, at a mint that allows more.
         */
        fun heldUsername(value: String): String? = value.trim().lowercase().takeIf(HELD_USERNAME::matches)

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
