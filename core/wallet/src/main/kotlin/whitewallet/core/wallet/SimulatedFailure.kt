package whitewallet.core.wallet

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * For developers, in debug builds: makes the first note change (rotate, merge, split) of one action fail as [kind]
 * says, or with [Kind.Unreachable] a recheck of one note too, so its failure screens can be seen without a broken mint. It travels with the action's coroutine
 * ([during]), so nothing else the wallet does meanwhile (a refresh, the note strategy) can use it up, and it is gone
 * when the action ends. The wallet takes the same path as for a real failure: journal, outcome, reconciliation.
 */
class SimulatedFailure(val kind: Kind) : AbstractCoroutineContextElement(Key) {
    enum class Kind {
        /** The mint doesn't answer the note lookup before the change, or a recheck: nothing is sent. */
        Unreachable,

        /** The mint refuses the change: nothing changes. */
        Refused,

        /**
         * The change is sent and lands, but its answer is lost, and the mint can't be asked about it right away:
         * the change stays open until a refresh settles it.
         */
        AnswerLost,
    }

    private val used = AtomicBoolean(false)

    /** Whether this failure is [kind] and still unused; it is used up by a true answer. */
    internal fun take(kind: Kind): Boolean = this.kind == kind && used.compareAndSet(false, true)

    companion object Key : CoroutineContext.Key<SimulatedFailure> {
        /** Runs [action] with [failure] (none: as it is). */
        suspend fun <T> during(failure: Kind?, action: suspend () -> T): T =
            if (failure == null) action() else withContext(SimulatedFailure(failure)) { action() }

        /** Whether the running action should fail as [kind] here (see [SimulatedFailure]). */
        internal suspend fun take(kind: Kind): Boolean = currentCoroutineContext()[Key]?.take(kind) == true
    }
}
