package whitewallet.brand

/**
 * A way of keeping an account's notes: which shape the notes on one service should have. The wallet asks [plan] for
 * the next steps, runs them, and asks again with the new notes, until the plan is empty.
 *
 * A strategy only proposes. The wallet checks every step, runs it with its usual safeguards, skips what doesn't apply
 * (unknown notes, impossible amounts) and stops when a service doesn't answer. Money never leaves the account: a
 * strategy can combine, split and rotate notes on one service, nothing else.
 *
 * Register one in a brand's code module (Hilt `@IntoSet`) and name its [id] in brand.properties `noteStrategies`.
 */
interface NoteStrategy {
    /** Stable, stored per account: lowercase letters, digits and `-`, e.g. `keep-small-change`. Never change it. */
    val id: String

    /** String resource: the strategy's name in the notes settings, e.g. "Keep only one note". */
    val title: Int

    /** String resource: one sentence on what it does, shown under the name. */
    val description: Int

    /**
     * True if the strategy also moves notes off address keys, so the separate auto-rotate switch is included in it.
     * Rotating breaks the link between an address payment and the note it became.
     */
    val rotatesAddressNotes: Boolean get() = false

    /**
     * True if the strategy reshapes notes, so it would undo a combine or split done by hand. Before one, the notes
     * page switches the account to the `manual` strategy (if the brand offers it).
     */
    val reshapesNotes: Boolean get() = true

    /** The next steps for one service's notes; empty once they have the shape this strategy wants. */
    fun plan(service: ServiceNotes): List<NoteStep>
}

/** One service's spendable notes of an account, with what that service charges. */
data class ServiceNotes(
    val host: String,
    val notes: List<StrategyNote>,
    /** The service's fee per operation that creates notes (split), in millisatoshi; merges and rotations are free. */
    val feeBaseMsat: Long,
) {
    val totalMsat: Long get() = notes.sumOf { it.amountMsat }
}

data class StrategyNote(
    val id: String,
    val amountMsat: Long,
    /** On a key of a Lightning Address (an incoming payment), not on one of the wallet's own keys. */
    val onAddressKey: Boolean,
    val updatedAtMillis: Long,
)

sealed interface NoteStep {
    /** Merge these notes into one. At least two; long lists are merged in batches. */
    data class Combine(val noteIds: List<String>) : NoteStep

    /**
     * Split a note in two: one worth [firstMsat], the other the rest minus the service's fee. [firstMsat] must leave
     * at least 1 msat after the fee (`1 <= firstMsat <= amount - feeBaseMsat - 1`).
     */
    data class Split(val noteId: String, val firstMsat: Long) : NoteStep

    /** Move a note from an address key onto a fresh key of the wallet (free). Ignored for notes already there. */
    data class Rotate(val noteId: String) : NoteStep
}
