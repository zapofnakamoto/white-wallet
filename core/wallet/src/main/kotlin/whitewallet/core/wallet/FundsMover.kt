package whitewallet.core.wallet

import java.io.IOException
import kotlinx.coroutines.flow.first
import whitewallet.core.model.ActivityStatus
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.lnurlcash.protocol.ProtocolViolation
import whitewallet.lnurlcash.protocol.RejectionKind

sealed interface MoveResult {
    /** [amountMsat] moved. A move of everything goes mint by mint: [stayed] is what it left where it was, per mint. */
    data class Moved(val amountMsat: Long, val stayed: List<MoveStayed> = emptyList()) : MoveResult

    /** Nothing changed. If that was because of a mint, [stayed] says which and why. */
    data class Failed(val reason: MoveFailure, val detail: String? = null, val stayed: List<MoveStayed> = emptyList()) : MoveResult

    /** At least one step's outcome is unknown; reconciliation settles it on the next refresh. */
    data object Pending : MoveResult
}

enum class MoveFailure {
    NothingToMove,
    InsufficientFunds,

    /** The funds are spread over several mints and no single one holds the amount. */
    SplitAcrossMints,
    SameAccount,
    UnknownAccount,
    Refused,
}

/** Why the money at a mint stayed with the account it was to leave. */
enum class StayReason {
    /** The mint didn't answer: the same move may work later. */
    Unreachable,

    /**
     * The mint shows another signing key than the wallet of the account pinned for it: the same move won't work until the user looked at
     * the mint, in this wallet.
     */
    KeyChanged,

    /**
     * The wallet the money was to go to pinned another signing key for the mint than this one did: the same move won't work until the user
     * looked at the mint in that wallet.
     */
    OtherWalletKey,

    /** The mint refused the move. */
    Refused,
}

/** The money at the mint [host] that a move left with the account it was to leave, [amountMsat] of it, and why. */
data class MoveStayed(val host: String, val amountMsat: Long, val reason: StayReason)

/**
 * What a move would do, as its mints say now ([Wallet.checkMoveFunds]): [movableMsat] would move, [stayed] wouldn't. Nothing was changed to
 * find out, and a mint may answer differently once the move is made.
 */
data class MoveCheck(val movableMsat: Long, val stayed: List<MoveStayed>)

/**
 * Moves notes from one account to another. Notes never leave their mint: the source spends them into keys derived from the target account's
 * own recovery phrase (a rotate, merge or split), so the mint sees an ordinary mutation and only split fees apply. A wallet that takes a
 * note at a mint it doesn't have yet gets that mint in its list first, so the note is where it looks.
 */
internal class FundsMover(private val wallet: Wallet) {
    private val mutator = NoteMutator(wallet)
    private val log = ActivityLog(wallet)
    private val onboarding = Onboarding(wallet)

    suspend fun move(fromId: String, toId: String, amountMsat: Long?): MoveResult {
        if (fromId == toId) return MoveResult.Failed(MoveFailure.SameAccount)
        if (wallet.store.account(toId) == null) return MoveResult.Failed(MoveFailure.UnknownAccount)
        val byHost = outstanding(fromId).groupBy { it.host }
        if (byHost.isEmpty()) return MoveResult.Failed(MoveFailure.NothingToMove)

        if (amountMsat == null) {
            var moved = 0L
            val failed = mutableListOf<MoveResult.Failed>()
            for ((host, notes) in byHost) {
                // A mint that can't take part keeps its notes here, and the result says so; the other mints' notes still move.
                val held = notes.sumOf { it.amountMsat }
                when (val r = at(host, held) { addMint(fromId, toId, host, held) ?: moveAll(fromId, toId, host, notes) }) {
                    is MoveResult.Moved -> moved += r.amountMsat
                    is MoveResult.Failed -> failed += r
                    MoveResult.Pending -> return r
                }
            }
            val stayed = failed.flatMap { it.stayed }
            return when {
                moved > 0 || failed.isEmpty() -> MoveResult.Moved(moved, stayed)
                else -> failed.singleOrNull() ?: MoveResult.Failed(MoveFailure.Refused, failed.first().detail, stayed)
            }
        }

        require(amountMsat > 0) { "amount must be positive" }
        if (byHost.values.sumOf { notes -> notes.sumOf { it.amountMsat } } < amountMsat)
            return MoveResult.Failed(MoveFailure.InsufficientFunds)
        val (host, notes) =
            byHost.entries.firstOrNull { (_, n) -> n.sumOf { it.amountMsat } >= amountMsat }?.toPair()
                ?: return MoveResult.Failed(MoveFailure.SplitAcrossMints)
        return at(host, amountMsat) { addMint(fromId, toId, host, amountMsat) ?: movePart(fromId, toId, host, notes, amountMsat) }
    }

    /** [move] of [amountMsat] at [host]: a connection or a signing key that fails it is a result that names the mint, as any other. */
    private suspend fun at(host: String, amountMsat: Long, move: suspend () -> MoveResult): MoveResult =
        try {
            move()
        } catch (e: IOException) {
            MoveResult.Failed(MoveFailure.Refused, e.message, listOf(MoveStayed(host, amountMsat, StayReason.Unreachable)))
        } catch (e: MintKeyChangedException) {
            MoveResult.Failed(MoveFailure.Refused, e.message, listOf(MoveStayed(host, amountMsat, StayReason.KeyChanged)))
        }

    /**
     * What [move] would do, asked of each mint the money is at without changing anything: whether it answers, and whether its signing key
     * is the one both wallets know it by.
     */
    suspend fun check(fromId: String, toId: String, amountMsat: Long?): MoveCheck {
        val byHost = outstanding(fromId).groupBy { it.host }.mapValues { (_, notes) -> notes.sumOf { it.amountMsat } }
        val asked =
            if (amountMsat == null) byHost
            else byHost.entries.firstOrNull { (_, held) -> held >= amountMsat }?.let { mapOf(it.key to amountMsat) }.orEmpty()
        var movable = 0L
        val stayed = mutableListOf<MoveStayed>()
        for ((host, amount) in asked) {
            when (val reason = cantMove(fromId, toId, host)) {
                null -> movable += amount
                else -> stayed += MoveStayed(host, amount, reason)
            }
        }
        return MoveCheck(movable, stayed)
    }

    /** Why notes at [host] couldn't move from [fromId] to [toId] now, as [addMint] and the mint would find; null if they could. */
    private suspend fun cantMove(fromId: String, toId: String, host: String): StayReason? {
        val pinned = wallet.mintOf(fromId, host)?.mintPubkey
        val toWallet = wallet.walletOf(toId)
        val known = if (toWallet == wallet.walletOf(fromId)) null else wallet.store.mint(toWallet, host)
        if (pinned != null && known?.mintPubkey != null && known.mintPubkey != pinned) return StayReason.OtherWalletKey
        return try {
            wallet.client.servicePayRequest(host)
            val presented =
                try {
                    wallet.client.withdrawDiscovery(host).mintPubkey?.toHex()
                } catch (e: ProtocolViolation) {
                    null
                }
            // A mint the other wallet is to add must show the key pinned here; one both have must not show another.
            val adds = toWallet != wallet.walletOf(fromId) && known == null
            if (pinned != null && (presented ?: pinned.takeUnless { adds }) != pinned) StayReason.KeyChanged else null
        } catch (e: ProtocolViolation) {
            StayReason.Refused
        } catch (e: IOException) {
            StayReason.Unreachable
        }
    }

    /**
     * The mints a move of [amountMsat] (or everything, if null) would add to the list of [toId]'s wallet: those the money is at that the
     * wallet doesn't have. None for an account of the same wallet.
     */
    suspend fun mintsToAdd(fromId: String, toId: String, amountMsat: Long?): List<String> {
        val toWallet = wallet.walletOf(toId)
        if (toWallet == wallet.walletOf(fromId)) return emptyList()
        val byHost = outstanding(fromId).groupBy { it.host }
        val hosts =
            if (amountMsat == null) byHost.keys
            else listOfNotNull(byHost.entries.firstOrNull { (_, n) -> n.sumOf { it.amountMsat } >= amountMsat }?.key)
        return hosts.filter { wallet.store.mint(toWallet, it) == null }
    }

    /**
     * Before a note at [host] goes to an account of another wallet: adds the mint to that wallet's list if it isn't there, pinned to the
     * key the sending wallet pinned, neither trusted nor scanned nor anyone's home (as a mint met by claiming a note). Returns why the move
     * can't go on: the mint didn't answer, or the two wallets pinned different keys for it.
     */
    private suspend fun addMint(fromId: String, toId: String, host: String, amountMsat: Long): MoveResult.Failed? {
        val toWallet = wallet.walletOf(toId)
        if (toWallet == wallet.walletOf(fromId)) return null
        val failed = { reason: StayReason, detail: String? ->
            MoveResult.Failed(MoveFailure.Refused, detail, listOf(MoveStayed(host, amountMsat, reason)))
        }
        val pinned = wallet.mintOf(fromId, host)?.mintPubkey
        val known = wallet.store.mint(toWallet, host)
        if (known != null) {
            val differs = pinned != null && known.mintPubkey != null && known.mintPubkey != pinned
            return if (differs) failed(StayReason.OtherWalletKey, MINT_KEY_CHANGED) else null
        }
        return when (val connected = onboarding.connectMint(toWallet, host, expectedKey = pinned)) {
            is ConnectResult.Connected -> null
            is ConnectResult.Failed ->
                when (val r = connected.result) {
                    is OnboardResult.MintInvalid ->
                        failed(if (r.reason == MINT_KEY_CHANGED) StayReason.KeyChanged else StayReason.Refused, r.reason)
                    is OnboardResult.NetworkError -> failed(StayReason.Unreachable, r.reason)
                    else -> failed(StayReason.Refused, null)
                }
        }
    }

    private suspend fun moveAll(fromId: String, toId: String, host: String, notes: List<Note>): MoveResult {
        val inputs = notes.sumOf { it.amountMsat }
        val target = OutputSpec.Own(toId, KeySet.Wallet)
        val result =
            if (notes.size == 1) mutator.rotate(fromId, host, inputs = notes, outputs = listOf(target))
            else mutator.merge(fromId, host, notes, target)
        when (result) {
            is MutationResult.Done -> {
                val out = result.outputs.single().amountMsat
                log.move(
                    fromId,
                    toId,
                    host,
                    amountMsat = out.takeIf { it > 0 } ?: inputs,
                    feeMsat = ActivityLog.fee(inputs, result.outputs),
                    status = ActivityStatus.Done,
                    result.journalId,
                )
            }
            is MutationResult.Pending ->
                log.move(fromId, toId, host, amountMsat = inputs, feeMsat = null, status = ActivityStatus.Pending, result.journalId)
            else -> Unit
        }
        return result.toMove(host, inputs)
    }

    private suspend fun movePart(fromId: String, toId: String, host: String, notes: List<Note>, amountMsat: Long): MoveResult {
        // Largest notes first, so as few notes as possible are touched.
        val sorted = notes.sortedByDescending { it.amountMsat }
        sorted
            .firstOrNull { it.amountMsat == amountMsat }
            ?.let {
                return moveAll(fromId, toId, host, listOf(it))
            }
        var inputs = sorted.takeWhileCovering(amountMsat)
        if (inputs.size > 1) {
            // Merge into one of our own notes first, then split that.
            val merged = mutator.merge(fromId, host, inputs, OutputSpec.Own(fromId, KeySet.Wallet))
            inputs = (merged as? MutationResult.Done)?.outputs ?: return merged.toMove(host, amountMsat)
            if (inputs.single().amountMsat == amountMsat) return moveAll(fromId, toId, host, inputs)
        }
        val outputs = listOf(OutputSpec.Own(toId, KeySet.Wallet), OutputSpec.Own(fromId, KeySet.Change))
        val result = mutator.split(fromId, host, inputs, outputs, splitMsat = amountMsat)
        when (result) {
            is MutationResult.Done -> {
                log.move(
                    fromId,
                    toId,
                    host,
                    amountMsat,
                    feeMsat = ActivityLog.fee(inputs.sumOf { it.amountMsat }, result.outputs),
                    status = ActivityStatus.Done,
                    result.journalId,
                )
            }
            is MutationResult.Pending ->
                log.move(fromId, toId, host, amountMsat, feeMsat = null, status = ActivityStatus.Pending, result.journalId)
            else -> Unit
        }
        val r = result.toMove(host, amountMsat)
        return if (r is MoveResult.Moved) MoveResult.Moved(amountMsat) else r
    }

    /** What the mutation of [amountMsat] at [host] came to, as a move. */
    private fun MutationResult.toMove(host: String, amountMsat: Long): MoveResult =
        when (this) {
            is MutationResult.Done -> MoveResult.Moved(outputs.firstOrNull()?.amountMsat ?: 0)
            is MutationResult.Refused ->
                MoveResult.Failed(
                    if (kind == RejectionKind.InsufficientValue) MoveFailure.InsufficientFunds else MoveFailure.Refused,
                    reason,
                    listOf(MoveStayed(host, amountMsat, StayReason.Refused)),
                )
            is MutationResult.Unreachable ->
                MoveResult.Failed(
                    MoveFailure.Refused,
                    reason,
                    listOf(MoveStayed(host, amountMsat, if (keyChanged) StayReason.KeyChanged else StayReason.Unreachable)),
                )
            is MutationResult.Pending -> MoveResult.Pending
        }

    private suspend fun outstanding(accountId: String): List<Note> =
        wallet.store.notes(accountId).first().filter {
            it.state == NoteState.Outstanding && it.kind == NoteKind.KeyPath && it.index != null
        }

    private fun List<Note>.takeWhileCovering(amountMsat: Long): List<Note> {
        var sum = 0L
        val taken = mutableListOf<Note>()
        for (note in this) {
            if (sum >= amountMsat) break
            taken += note
            sum += note.amountMsat
        }
        return taken
    }
}
