package whitewallet.core.wallet

import whitewallet.brand.MintNotes
import whitewallet.brand.NoteStep
import whitewallet.brand.NoteStrategy

/** Leaves notes as they arrive (auto-rotate, if on, still applies). */
class ManualStrategy(override val title: Int = 0, override val description: Int = 0) : NoteStrategy {
    override val id = ID
    override val reshapesNotes = false
    override fun plan(mint: MintNotes): List<NoteStep> = emptyList()

    companion object {
        const val ID = "manual"
    }
}

/** One note per mint: everything is merged (free), and a single note left on an address key is rotated. */
class SingleNoteStrategy(override val title: Int = 0, override val description: Int = 0) : NoteStrategy {
    override val id = ID
    override val rotatesAddressNotes = true
    override fun plan(mint: MintNotes): List<NoteStep> {
        val notes = mint.notes.sortedBy { it.updatedAtMillis }
        return when {
            notes.size > 1 -> listOf(NoteStep.Combine(notes.map { it.id }))
            notes.singleOrNull()?.onAddressKey == true -> listOf(NoteStep.Rotate(notes.single().id))
            else -> emptyList()
        }
    }

    companion object {
        const val ID = "single"
    }
}

/**
 * The strategies a build offers: every registered one (built-in and the brand's own), narrowed to the brand's
 * `notes.strategies` in that order, with its `notes.strategy.default`. Checked on construction, so a misconfigured brand
 * fails on first start with a message naming the problem.
 */
class NoteStrategies(registered: Collection<NoteStrategy>, offered: List<String>, defaultId: String) {
    /** In the brand's order; the user picks one per account. */
    val offered: List<NoteStrategy>
    val default: NoteStrategy

    init {
        registered.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("two note strategies have the id '$it'") }
        registered.firstOrNull { !ID.matches(it.id) }?.let { error("note strategy id '${it.id}' must be lowercase letters, digits and '-'") }
        val byId = registered.associateBy { it.id }
        val ids = offered.ifEmpty { listOf(defaultId) }
        this.offered = ids.distinct().map { id ->
            byId[id] ?: error("brand.properties offers the note strategy '$id', but none has that id (known: ${byId.keys.sorted().joinToString()})")
        }
        default = this.offered.firstOrNull { it.id == defaultId }
            ?: error("brand.properties notes.strategy.default '$defaultId' is not one of notes.strategies (${ids.joinToString()})")
    }

    /** The account's choice if this build still offers it, else the default. */
    fun resolve(id: String?): NoteStrategy = offered.firstOrNull { it.id == id } ?: default

    fun isOffered(id: String): Boolean = offered.any { it.id == id }

    companion object {
        private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        /** The built-in strategies, unlabeled: for tests and builds without a brand configuration. */
        fun builtIn(default: String = SingleNoteStrategy.ID): NoteStrategies =
            NoteStrategies(listOf(SingleNoteStrategy(), ManualStrategy()), listOf(SingleNoteStrategy.ID, ManualStrategy.ID), default)
    }
}
