package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.flow.first
import whitewallet.core.model.KeyCounter
import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.AddressProof
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.client.RegistrationOutcome
import whitewallet.lnurlcash.client.RegistrationRejection
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.spendDomain

/** Whether a Lightning address on a mint points at an account's keys. */
sealed interface AddressStatus {
    /** Registered to the account: the mint accepts the account's proof for it (or publishes its legacy `cx1`, LUD-26 `text/cpub`). */
    data object Ours : AddressStatus

    /** Registered, but ownership cannot be confirmed: the mint publishes no legacy `cx1`, or the address is still on the legacy branch. */
    data object Unconfirmed : AddressStatus

    /** Nobody has this name on the mint. */
    data object Available : AddressStatus

    /** Someone else has it (or it is one of the mint's own reserved names). */
    data object Taken : AddressStatus

    data class Invalid(val reason: String) : AddressStatus

    data class CheckFailed(val reason: String) : AddressStatus
}

/** [status], and for [AddressStatus.Ours] on a LUD-26 mint the share whose proof the mint accepted. */
internal data class Ownership(val status: AddressStatus, val share: Long? = null)

/**
 * Looks a username up at the mint's LUD-16 endpoint. On a LUD-26 mint (luds 92e430f) a registered name is ours if the mint accepts the
 * account's proof for one of its shares: an update to the share it already holds, which the mint refuses with `cx1 already used` for the
 * holder and `Invalid ownership signature.` for anyone else, and never applies. On a legacy 2f17578 mint it compares the published
 * `text/cpub` with the account's branch (tag: mint).
 */
internal class AddressCheck(private val wallet: Wallet) {
    /** [accountId] is the account whose keys count as "ours", or null for an account that does not exist yet. */
    suspend fun check(username: String, host: String, accountId: String?): AddressStatus = ownership(username, host, accountId).status

    suspend fun ownership(username: String, host: String, accountId: String?, also: Long? = null): Ownership {
        val name = Onboarding.normalizeUsername(username) ?: return Ownership(AddressStatus.Invalid(Onboarding.USERNAME_RULE))
        val h = Onboarding.normalizeHost(host) ?: return Ownership(AddressStatus.Invalid("not a valid host"))
        val url = Lnurl.lightningAddressUrl("$name@$h") ?: return Ownership(AddressStatus.Invalid(Onboarding.USERNAME_RULE))
        val (response, dialect) =
            try {
                wallet.client.get(url) to wallet.dialect(h)
            } catch (e: IOException) {
                return Ownership(AddressStatus.CheckFailed(e.message ?: "network error"))
            } catch (e: ProtocolViolation) {
                return Ownership(AddressStatus.CheckFailed(e.message ?: "unexpected response"))
            }
        val pay =
            response as? LnurlResponse.PayRequest
                ?: return Ownership(
                    if (response is LnurlResponse.Error) AddressStatus.Available else AddressStatus.CheckFailed("unexpected response")
                )
        if (dialect == MintDialect.Legacy) return Ownership(wallet.legacy.status(pay, h, accountId))
        if (accountId == null || wallet.keysIfSeeded(accountId, h) == null) return Ownership(AddressStatus.Taken)
        val recorded = wallet.store.addresses(accountId).first().firstOrNull { it.username == name && it.host == h }
        // Moved to a share once this wallet migrates it (tag: keys); until then the mint can't be asked without moving it.
        if (recorded != null && recorded.share == null) return Ownership(AddressStatus.Unconfirmed)
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
        return (0 until handedOut + wallet.limits.sharesAhead).toList()
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
                        else -> return Ownership(AddressStatus.Unconfirmed)
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
