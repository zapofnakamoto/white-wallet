package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.flow.first
import whitewallet.core.model.NoteState
import whitewallet.core.model.Service
import whitewallet.lnurlcash.protocol.ProtocolViolation

/** What the service details page shows about one service (brief §6): stored facts, checked live where possible. */
data class ServiceDetails(
    val host: String,
    /** As last stored (fees, limits, the pinned signing key); null if the wallet never connected to it. */
    val service: Service?,
    val reachability: Reachability,
    /** The service's own one-line description, from its pay request metadata. */
    val description: String?,
    /** The active account's money held there, and in how many notes. */
    val balanceMsat: Long,
    val noteCount: Int,
    val isHome: Boolean,
    /** The signing key the service presents now, when it is not the pinned one ([Reachability.KeyChanged]). */
    val presentedKey: String? = null,
) {
    enum class Reachability {
        Reachable,
        Unreachable,

        /** Answers, but isn't a usable LNURLcash service (any more). */
        Invalid,

        /**
         * Presents a different signing key than the one pinned on first contact: operations are blocked until the user
         * accepts the new key ([Wallet.acceptMintKey]).
         */
        KeyChanged,
    }
}

internal class ServiceInspector(private val wallet: Wallet) {
    suspend fun details(host: String): ServiceDetails {
        val accountId = wallet.activeId()
        val h = Onboarding.normalizeHost(host)
            ?: return ServiceDetails(host, null, ServiceDetails.Reachability.Invalid, null, 0, 0, isHome = false)
        var description: String? = null
        var presented: String? = null
        val reachability = try {
            val pay = wallet.client.servicePayRequest(h)
            description = pay.pay.metadataEntries.firstOrNull { it.first == "text/plain" && !it.second.startsWith("Mint fees:") }?.second
            val pinned = wallet.store.service(h)?.mintPubkey
            presented = runCatching { wallet.client.withdrawDiscovery(h).mintPubkey?.toHex() }.getOrNull()
            if (pinned != null && presented != null && pinned != presented) {
                ServiceDetails.Reachability.KeyChanged
            } else {
                // Stores fresh fees and limits (and pins the key if it wasn't yet).
                if (Onboarding(wallet).connectService(h) is ConnectResult.Connected) ServiceDetails.Reachability.Reachable else ServiceDetails.Reachability.Invalid
            }
        } catch (e: ProtocolViolation) {
            ServiceDetails.Reachability.Invalid
        } catch (e: IOException) {
            ServiceDetails.Reachability.Unreachable
        }
        val notes = wallet.store.notes(accountId).first().filter { it.host == h && it.state == NoteState.Outstanding }
        return ServiceDetails(
            host = h,
            service = wallet.store.service(h),
            reachability = reachability,
            description = description,
            balanceMsat = notes.sumOf { it.amountMsat },
            noteCount = notes.size,
            isHome = wallet.store.account(accountId)?.host == h,
            presentedKey = presented.takeIf { reachability == ServiceDetails.Reachability.KeyChanged },
        )
    }
}
