package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.flow.first
import whitewallet.core.model.Mint
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.protocol.ProtocolViolation

/** What the mint details page shows about one mint (brief §6): stored facts, checked live where possible. */
data class MintDetails(
    val host: String,
    /** As last stored (fees, limits, the pinned signing key); null if the wallet never connected to it. */
    val mint: Mint?,
    val reachability: Reachability,
    /** The mint's own one-line description, from its pay request metadata. */
    val description: String?,
    /** The active account's money held there, and in how many notes. */
    val balanceMsat: Long,
    val noteCount: Int,
    val isHome: Boolean,
    /** The signing key the mint presents now, when it is not the pinned one ([Reachability.KeyChanged]); null if it presents none. */
    val presentedKey: String? = null,
) {
    enum class Reachability {
        Reachable,
        Unreachable,

        /** Answers, but isn't a usable LNURLcash mint (any more). */
        Invalid,

        /**
         * Presents a different signing key than the one pinned on first contact: operations are blocked until the user accepts the new key
         * ([Wallet.acceptMintKey]).
         */
        KeyChanged,
    }
}

/** What a mint is, asked before adding it ([Wallet.previewMint]). */
sealed interface MintPreview {
    val host: String

    /**
     * It answers as a mint: its [description], fees ([feeBaseMsat] plus [feePpm] per million), what one payment to it may be, the fee for
     * an address ([addressFeeMsat], null for none), the signing key it presents now ([signingKey]; [knownKey] if the wallet pinned one
     * already), and whether it speaks the older protocol ([legacy]).
     */
    data class Ready(
        override val host: String,
        val description: String?,
        val feeBaseMsat: Long?,
        val feePpm: Long?,
        val minSendableMsat: Long,
        val maxSendableMsat: Long,
        val addressFeeMsat: Long?,
        val signingKey: String?,
        val knownKey: String?,
        val legacy: Boolean,
    ) : MintPreview

    data class Unreachable(override val host: String, val reason: String) : MintPreview

    /** Not a host, or it answers but isn't a usable LNURLcash mint. */
    data class Invalid(override val host: String, val reason: String) : MintPreview
}

internal class MintInspector(private val wallet: Wallet) {
    suspend fun preview(host: String): MintPreview {
        val h = Onboarding.normalizeHost(host) ?: return MintPreview.Invalid(host, "not a valid host")
        return try {
            val pay = wallet.client.servicePayRequest(h)
            val key = runCatching { wallet.client.withdrawDiscovery(h).mintPubkey?.toHex() }.getOrNull()
            val legacy = runCatching { wallet.client.dialect(h) == MintDialect.Legacy }.getOrDefault(false)
            val walletId = wallet.store.currentAccountId()?.let { wallet.walletOf(it) }
            MintPreview.Ready(
                host = h,
                description =
                    pay.pay.metadataEntries.firstOrNull { it.first == "text/plain" && !it.second.startsWith("Mint fees:") }?.second,
                feeBaseMsat = pay.fee?.baseMsat,
                feePpm = pay.fee?.ppm,
                minSendableMsat = pay.pay.minSendable,
                maxSendableMsat = pay.pay.maxSendable,
                addressFeeMsat = pay.fee?.addressMsat?.takeIf { it > 0 },
                signingKey = key,
                knownKey = walletId?.let { wallet.store.mint(it, h)?.mintPubkey },
                legacy = legacy,
            )
        } catch (e: ProtocolViolation) {
            MintPreview.Invalid(h, e.message ?: "not an LNURLcash server")
        } catch (e: IOException) {
            MintPreview.Unreachable(h, e.message ?: "network error")
        }
    }

    private val onboarding = Onboarding(wallet)

    suspend fun details(host: String): MintDetails {
        val accountId = wallet.activeId()
        val h =
            Onboarding.normalizeHost(host)
                ?: return MintDetails(
                    host,
                    mint = null,
                    reachability = MintDetails.Reachability.Invalid,
                    description = null,
                    balanceMsat = 0,
                    noteCount = 0,
                    isHome = false,
                )
        var description: String? = null
        var presented: String? = null
        val reachability =
            try {
                val pay = wallet.client.servicePayRequest(h)
                description =
                    pay.pay.metadataEntries.firstOrNull { it.first == "text/plain" && !it.second.startsWith("Mint fees:") }?.second
                val pinned = wallet.mintOf(accountId, h)?.mintPubkey
                // Null if it can't be asked now; an answer without a valid key, from a pinned mint, is a changed key.
                val discovery = runCatching { wallet.client.withdrawDiscovery(h) }.getOrNull()
                presented = discovery?.mintPubkey?.toHex()
                if (pinned != null && discovery != null && pinned != presented) {
                    MintDetails.Reachability.KeyChanged
                } else {
                    // Stores fresh fees and limits (and pins the key if it wasn't yet).
                    if (onboarding.connectMint(wallet.walletOf(accountId), h) is ConnectResult.Connected) MintDetails.Reachability.Reachable
                    else MintDetails.Reachability.Invalid
                }
            } catch (e: ProtocolViolation) {
                MintDetails.Reachability.Invalid
            } catch (e: IOException) {
                MintDetails.Reachability.Unreachable
            }
        val notes = wallet.store.notes(accountId).first().filter { it.host == h && it.state == NoteState.Outstanding }
        return MintDetails(
            host = h,
            mint = wallet.mintOf(accountId, h),
            reachability = reachability,
            description = description,
            balanceMsat = notes.sumOf { it.amountMsat },
            noteCount = notes.size,
            isHome = wallet.homeOf(accountId) == h,
            presentedKey = presented.takeIf { reachability == MintDetails.Reachability.KeyChanged },
        )
    }
}
