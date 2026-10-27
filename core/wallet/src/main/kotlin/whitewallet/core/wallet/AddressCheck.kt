package whitewallet.core.wallet

import java.io.IOException
import whitewallet.lnurl.Lnurl
import whitewallet.lnurl.LnurlResponse
import whitewallet.lnurlcash.protocol.ProtocolViolation

/** Whether a Lightning Address on a mint points at an account's note branch. */
sealed interface AddressStatus {
    /** Registered to the account: the mint publishes its own `cx1` for it (LUD-26 `text/cpub`). */
    data object Ours : AddressStatus

    /** Registered, but the mint does not publish a `cx1`, so ownership cannot be confirmed. */
    data object Unconfirmed : AddressStatus

    /** Nobody has this name on the mint. */
    data object Available : AddressStatus

    /** Someone else has it (or it is one of the mint's own reserved names). */
    data object Taken : AddressStatus

    data class Invalid(val reason: String) : AddressStatus

    data class CheckFailed(val reason: String) : AddressStatus
}

/** Looks a username up at the mint's LUD-16 endpoint and compares its `text/cpub` with the account's branch. */
internal class AddressCheck(private val wallet: Wallet) {
    /** [accountId] is the account whose branch counts as "ours", or null for an account that does not exist yet. */
    suspend fun check(username: String, host: String, accountId: String?): AddressStatus {
        val name = Onboarding.normalizeUsername(username) ?: return AddressStatus.Invalid(Onboarding.USERNAME_RULE)
        val h = Onboarding.normalizeHost(host) ?: return AddressStatus.Invalid("not a valid host")
        val url = Lnurl.lightningAddressUrl("$name@$h") ?: return AddressStatus.Invalid(Onboarding.USERNAME_RULE)
        val response = try {
            wallet.client.get(url)
        } catch (e: IOException) {
            return AddressStatus.CheckFailed(e.message ?: "network error")
        } catch (e: ProtocolViolation) {
            return AddressStatus.CheckFailed(e.message ?: "unexpected response")
        }
        val pay = response as? LnurlResponse.PayRequest
            ?: return if (response is LnurlResponse.Error) AddressStatus.Available else AddressStatus.CheckFailed("unexpected response")
        val cpub = pay.metadataEntries.firstOrNull { it.first == "text/cpub" }?.second?.substringBeforeLast(':')
            ?: return AddressStatus.Unconfirmed
        val ours = wallet.branchIfSeeded(accountId, h)?.cx1()
        return if (ours != null && cpub.equals(ours, ignoreCase = true)) AddressStatus.Ours else AddressStatus.Taken
    }
}
