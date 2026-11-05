package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.flow.first
import whitewallet.core.model.KeyCounter
import whitewallet.core.model.KeyScheme
import whitewallet.core.model.NoteState
import whitewallet.core.model.RegisteredAddress
import whitewallet.lnurlcash.AddressProof
import whitewallet.lnurlcash.client.MintDialect
import whitewallet.lnurlcash.client.RegistrationOutcome
import whitewallet.lnurlcash.client.RegistrationRejection
import whitewallet.lnurlcash.spendDomain

/**
 * Legacy: LUD-26 draft 2f17578 (tag: keys). Brings an account's old keys forward on each refresh, without asking: every step is safe to
 * repeat and picks up where a lost connection left it.
 * - On a LUD-26 mint an address still on the legacy branch moves to a share of its own, proven by the branch key.
 * - Notes on legacy keys rotate onto new wallet keys, for free. On a legacy mint address payments keep arriving on purpose 2, so only
 *   wallet and change notes do there: those are the ones a published `cx1` gives away.
 */
internal class LegacyMigration(
    private val wallet: Wallet,
    private val scanner: AddressScanner,
    private val addresses: AddressCheck,
    private val rotator: NoteRotator,
) {
    suspend fun run(accountId: String) {
        val unmoved = wallet.store.addresses(accountId).first().filter { it.share == null && it.feeInvoice == null }
        val notes = wallet.store.notes(accountId).first().filter { it.scheme == KeyScheme.Legacy && it.state == NoteState.Outstanding }
        for (host in (unmoved.map { it.host } + notes.map { it.host }).distinct()) {
            if (wallet.keysIfSeeded(accountId, host) == null) continue
            // The mint may have been upgraded since it was asked.
            val here = unmoved.filter { it.host == host }
            if (here.isNotEmpty()) wallet.forgetDialect(host)
            val dialect =
                try {
                    wallet.dialect(host)
                } catch (e: IOException) {
                    continue
                }
            if (dialect == MintDialect.Current) here.forEach { moveAddress(accountId, it) }
            rotator.rotateAll(
                accountId,
                host,
                sets = { it.scheme == KeyScheme.Legacy && (dialect == MintDialect.Current || !it.isAddress) },
                automatic = true,
                quiet = true,
            )
        }
    }

    private suspend fun moveAddress(accountId: String, address: RegisteredAddress) {
        val share = move(accountId, address.username, address.host) ?: addresses.provenShare(address.username, address.host, accountId)
        share ?: return
        // What was paid to the old keys up to the move; from here on the name's payments are on the share.
        scanner.scan(accountId, address.host, KeySet.LegacyAddress)
        wallet.store.saveAddress(accountId, address.copy(share = share))
        wallet.setAddressStatus(accountId, address.address, AddressStatus.Ours)
    }

    /**
     * Moves [name] from the account's legacy branch to its next share, the share it is on now, or null if the mint didn't take it. Only the
     * branch key on file can do that, so asking about someone else's name changes nothing. The counter moves only once the share may be on
     * file, so refused attempts leave no gap for a restore to stop at; a crash in between is covered by [AddressCheck.provenShare].
     */
    suspend fun move(accountId: String, name: String, host: String): Long? {
        val domain = spendDomain(host)
        val keys = wallet.keys(accountId, host)
        val n = wallet.store.keyIndexOrNull(accountId, domain, KeyCounter.Shares) ?: 0
        val share = keys.share(n)
        val (sig, newsig) = AddressProof.update(keys.legacy.branchKey(), share, domain, name)
        val taken = suspend { wallet.store.setKeyIndex(accountId, domain, KeyCounter.Shares, n + 1) }
        return when (val outcome = wallet.client.updateRegistration(host, name, share.cx1(), sig, newsig)) {
            RegistrationOutcome.Registered -> n.also { taken() }
            is RegistrationOutcome.Ambiguous -> null.also { taken() }
            is RegistrationOutcome.Rejected -> null.also { if (outcome.kind == RegistrationRejection.Cx1AlreadyUsed) taken() }
            is RegistrationOutcome.AwaitingFee -> null
        }
    }
}
