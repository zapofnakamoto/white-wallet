package whitewallet.core.wallet

import java.net.URI
import whitewallet.core.model.JournalEntry
import whitewallet.core.model.JournalStatus
import whitewallet.lnurlcash.RevealsNoteSecret
import whitewallet.lnurlcash.protocol.MutationOutcome
import whitewallet.lnurlcash.protocol.MutationType
import whitewallet.lnurlcash.protocol.NoteInfo

/**
 * The only place in the wallet a note's spend goes out. Whoever gets a spend can claim the note, so it is sent only in the mutation that
 * spends it, and only when:
 * - the request is journaled, unchanged and not sent yet ([JournalStatus.Prepared]): what goes out is what was stored;
 * - its mint presents the key pinned for it (a mint the wallet doesn't know yet is trusted on first use, as on connecting; the claim pins
 *   it);
 * - it goes to the callback [info] names, on the mint's own host, port and scheme. Otherwise nothing is sent.
 */
internal class SecretGate(private val wallet: Wallet) {
    sealed interface Released {
        data class Sent(val outcome: MutationOutcome) : Released

        data class NotSent(val reason: String) : Released
    }

    @OptIn(RevealsNoteSecret::class)
    suspend fun release(entry: JournalEntry, info: NoteInfo, type: MutationType): Released {
        val stored = wallet.store.journal(setOf(JournalStatus.Prepared)).firstOrNull { it.id == entry.id }
        if (stored == null || stored.requestUrl != entry.requestUrl) return Released.NotSent("not journaled as prepared")
        val pinned = wallet.store.mint(entry.host)?.mintPubkey
        if (pinned != null && pinned != info.mintPubkey.toHex()) return Released.NotSent("the signing key of ${entry.host} changed")
        if (!sameOrigin(info.callback, entry.withdrawUrl)) return Released.NotSent("the callback isn't on ${entry.host}")
        if (!entry.requestUrl.startsWith(info.callback.substringBefore('?'))) return Released.NotSent("the request isn't for the callback")
        return Released.Sent(wallet.client.mutate(type, entry.requestUrl))
    }

    private companion object {
        fun sameOrigin(a: String, b: String): Boolean {
            val (x, y) =
                runCatching { URI(a) to URI(b) }
                    .getOrElse {
                        return false
                    }
            return x.scheme != null &&
                x.host != null &&
                x.scheme.equals(y.scheme, ignoreCase = true) &&
                x.host.equals(y.host, ignoreCase = true) &&
                port(x) == port(y)
        }

        fun port(u: URI): Int = if (u.port != -1) u.port else if (u.scheme.equals("https", ignoreCase = true)) 443 else 80
    }
}
