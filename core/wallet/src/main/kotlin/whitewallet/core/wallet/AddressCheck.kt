package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.flow.first
import whitewallet.core.model.KeyCounter
import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.AddressProof
import whitewallet.lnurlcash.client.RegistrationOutcome
import whitewallet.lnurlcash.client.RegistrationRejection
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.spendDomain

/** Whether a Lightning address on a mint points at an account's keys. */
sealed interface AddressStatus {
    /** Registered to the account: the mint accepts the account's proof for it. */
    data object Ours : AddressStatus

    /** Registered, but ownership cannot be confirmed: the mint answers the proof neither way (one of its own names, a held one). */
    data object Unconfirmed : AddressStatus

    /** Nobody has this name on the mint. */
    data object Available : AddressStatus

    /** Someone else has it (or it is one of the mint's own reserved names). */
    data object Taken : AddressStatus

    /** Not an address the app can look up, for [problem]. */
    data class Invalid(val problem: AddressProblem) : AddressStatus

    data class CheckFailed(val reason: String) : AddressStatus
}

/** Why an address can't be looked up. Never shown as it is: each screen says it in the user's words. */
enum class AddressProblem {
    /** The name breaks the rule: lowercase letters, digits, `.`, `-` or `_`, and at most 32 of them for a name to register. */
    Name,

    /** The mint's host isn't a host name. */
    Host,
}

/** [status], and for [AddressStatus.Ours] the share whose proof the mint accepted. */
internal data class Ownership(val status: AddressStatus, val share: Long? = null)

/**
 * Looks a username up at the mint's LUD-16 endpoint. A registered name is ours if the mint accepts the account's proof for one of its
 * shares (LUD-26): an update to the share it already holds, which the mint refuses with `cx1 already used` for the holder and `Invalid
 * ownership signature.` for anyone else, and never applies.
 */
internal class AddressCheck(private val wallet: Wallet) {
    /** [accountId] is the account whose keys count as "ours", or null for an account that does not exist yet. */
    suspend fun check(username: String, host: String, accountId: String?): AddressStatus = ownership(username, host, accountId).status

    suspend fun ownership(username: String, host: String, accountId: String?, also: Long? = null): Ownership {
        // Any length: the address may be one the account holds already. A name to register is held to its rule before it gets here.
        val name = Onboarding.heldUsername(username) ?: return Ownership(AddressStatus.Invalid(AddressProblem.Name))
        val h = Onboarding.normalizeHost(host) ?: return Ownership(AddressStatus.Invalid(AddressProblem.Host))
        val url = Lnurl.lightningAddressUrl("$name@$h") ?: return Ownership(AddressStatus.Invalid(AddressProblem.Name))
        val response =
            try {
                wallet.client.get(url)
            } catch (e: IOException) {
                return Ownership(AddressStatus.CheckFailed(e.message ?: "network error"))
            } catch (e: ProtocolViolation) {
                return Ownership(AddressStatus.CheckFailed(e.message ?: "unexpected response"))
            }
        if (response !is LnurlResponse.PayRequest) {
            return Ownership(
                if (response is LnurlResponse.Error) AddressStatus.Available else AddressStatus.CheckFailed("unexpected response")
            )
        }
        if (accountId == null || wallet.keysIfSeeded(accountId, h) == null) return Ownership(AddressStatus.Taken)
        val recorded = wallet.store.addresses(accountId).first().firstOrNull { it.username == name && it.host == h }
        return proveOwner(name, h, accountId, listOfNotNull(recorded?.share, also) + candidates(accountId, h))
    }

    /** The share of the account the mint holds [name] on, or null if it isn't on one (an address moved earlier, whose answer was lost). */
    suspend fun provenShare(name: String, host: String, accountId: String): Long? =
        proveOwner(name, host, accountId, candidates(accountId, host)).takeIf { it.status == AddressStatus.Ours }?.share

    /**
     * The account's shares on [host]'s domain that may hold a name: those it handed out, and a few past them that another phone with the
     * same phrase (or this one before a restore) may have handed out. Mints on one domain count shares together.
     */
    private suspend fun candidates(accountId: String, host: String): List<Long> {
        val handedOut = wallet.store.keyIndexOrNull(accountId, spendDomain(host), KeyCounter.Shares) ?: 0
        return (0 until handedOut + wallet.settings.addressCheckExtraSharesTried).toList()
    }

    private suspend fun proveOwner(name: String, host: String, accountId: String, shares: List<Long>): Ownership {
        val keys = wallet.keys(accountId, host)
        for (n in shares.distinct()) {
            val share = keys.share(n)
            val (sig, newsig) = AddressProof.update(share.proofKey(), share, spendDomain(host), name)
            when (val outcome = wallet.client.updateRegistration(host, name, share.cx1(), sig, newsig)) {
                // Only the holder's proof gets this far: the share is the one on file, which the mint never takes twice.
                is RegistrationOutcome.Rejected ->
                    when (outcome.kind) {
                        RegistrationRejection.Cx1AlreadyUsed -> return Ownership(AddressStatus.Ours, n)
                        RegistrationRejection.InvalidSignature -> continue
                        // Published, yet not a registration: one of the mint's own names.
                        RegistrationRejection.UnknownUser -> return Ownership(AddressStatus.Unconfirmed)
                        RegistrationRejection.RateLimited -> return Ownership(AddressStatus.CheckFailed(outcome.reason ?: "rate limited"))
                        RegistrationRejection.AlreadyRegistered,
                        RegistrationRejection.Other -> return Ownership(AddressStatus.Unconfirmed)
                    }
                is RegistrationOutcome.Ambiguous -> return Ownership(AddressStatus.CheckFailed(outcome.cause))
                // Accepted: the name was ours and is on this share now (the mint should have refused an update to the share on file).
                RegistrationOutcome.Registered -> return Ownership(AddressStatus.Ours, n)
                is RegistrationOutcome.AwaitingFee -> return Ownership(AddressStatus.Unconfirmed)
            }
        }
        return Ownership(AddressStatus.Taken)
    }
}
