package whitewallet.feature.notes

import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import whitewallet.brand.NoteStrategy
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.core.wallet.NoteAction
import whitewallet.core.wallet.NoteActionResult
import whitewallet.core.wallet.NoteCheck
import whitewallet.core.wallet.NoteProblem
import whitewallet.core.wallet.NotCollected
import whitewallet.core.wallet.RotateResult
import whitewallet.core.wallet.MintMoveQuote
import whitewallet.core.wallet.MintMoveResult
import whitewallet.core.wallet.ShareNoteResult
import whitewallet.core.wallet.SimulatedFailure
import whitewallet.core.wallet.SplitLimits
import whitewallet.core.wallet.Wallet

/** How notes are ordered within a mint. The grouping by mint always stays: combining and moving work per mint. */
enum class NoteSort(internal val order: Comparator<Note>) {
    Largest(compareByDescending<Note> { it.amountMsat }.thenByDescending { it.updatedAtMillis }),
    Newest(compareByDescending<Note> { it.updatedAtMillis }.thenByDescending { it.amountMsat }),
}

/** The handed-out notes, waiting for their recipients, in the order the user chose for all notes. */
internal fun sharedNotes(notes: List<Note>, sort: NoteSort): List<Note> = notes.filter { it.state == NoteState.Shared }.sortedWith(sort.order)

/** One mint's notes in the list. [skipped] is set when collecting at the home mint left them there (fee too high). */
data class MintNotes(
    val host: String,
    val isHome: Boolean,
    val notes: List<Note>,
    val skipped: NotCollected.Reason.FeeTooHigh? = null,
    /** The user trusts this mint (by its signing key; see Settings → Mints). */
    val trusted: Boolean = false,
) {
    /** What counts in the balance (notes being paid or confirmed are shown, but not counted). */
    val totalMsat: Long get() = notes.filter { it.state == NoteState.Outstanding }.sumOf { it.amountMsat }
}

data class NotesState(
    val loaded: Boolean = false,
    /** Notes in the balance or on their way, per mint: the home mint first, then the others by name. */
    val mints: List<MintNotes> = emptyList(),
    /** Notes handed out as links that nobody has redeemed yet. */
    val shared: List<Note> = emptyList(),
    val sort: NoteSort = NoteSort.Largest,
    /** Selection mode: tapping a note selects it instead of opening it. */
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    /** The note whose details are open. */
    val open: String? = null,
    val dialog: NotesDialog? = null,
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val outcome: NotesOutcome? = null,
    /** A note just shared: the screen moves on to its QR code. */
    val sharedId: String? = null,
    val noteStrategy: NoteStrategy? = null,
    val strategies: List<NoteStrategy> = emptyList(),
    /** Mints the notes could move to: the home mint and those notes are held on (plus local ones in developer mode). */
    val knownHosts: List<String> = emptyList(),
    val developerMode: Boolean = false,
    /** What the brand lets users do with notes (brand `notes.actions`). */
    val noteActions: Set<NoteAction> = NoteAction.entries.toSet(),
    /** Whether mints are named (brand `mints.visible`); without, one mint's notes are a plain list. */
    val showMints: Boolean = true,
    /** Debug builds in developer mode: a note's sheet offers making the next action fail ([SimulatedFailure]). */
    val failuresOffered: Boolean = false,
    /** How the next action fails, once; null: it runs as it is. */
    val simulatedFailure: SimulatedFailure.Kind? = null,
) {
    private val all: List<Note> get() = mints.flatMap { it.notes }
    val totalMsat: Long get() = mints.sumOf { it.totalMsat }
    val noteCount: Int get() = all.size
    val sharedMsat: Long get() = shared.sumOf { it.amountMsat }
    val openNote: Note? get() = open?.let { id -> all.firstOrNull { it.id == id } }
    val openTrusted: Boolean get() = openNote?.let { n -> mints.any { it.host == n.host && it.trusted } } == true
    val selectedMsat: Long get() = all.filter { it.id in selected }.sumOf { it.amountMsat }
    val canCombine: Boolean get() = !busy && selected.size >= 2
    val canAct: Boolean get() = !busy && selected.isNotEmpty()

    /** Selecting several notes only makes sense with an action that works on several. */
    val canSelect: Boolean get() = noteActions.any { it.bulk }

    /** A header per mint, unless mints aren't named and there is only one. */
    val groupHeaders: Boolean get() = showMints || mints.size > 1

    /** A split by hand would be undone by the account's strategy, so it switches to manual first (if offered). */
    val switchesToManual: Boolean get() = noteStrategy?.reshapesNotes == true && strategies.any { it.id == ManualStrategy.ID }
}

sealed interface NotesDialog {
    data class Split(val id: String, val limits: SplitLimits, val amountText: String = "") : NotesDialog {
        /** Amounts are typed in whole sats, so the smallest is at least one sat. */
        val minMsat: Long get() = (limits.minMsat + 999) / 1000 * 1000

        /** Whether two notes of whole sats can come out of it at all (a 2-sat note with a 1-sat fee can't). */
        val possible: Boolean get() = minMsat <= limits.maxMsat

        /** Where the amount starts: half of what is left after the fee, in whole sats. */
        val halfSats: Long get() = ((limits.maxMsat + limits.minMsat) / 2 / 1000).coerceIn(minMsat / 1000, limits.maxMsat / 1000)
        val amountMsat: Long? get() = amountText.toLongOrNull()?.times(1000)?.takeIf { it in minMsat..limits.maxMsat }
    }

    data class Move(val target: String = "", val quote: MintMoveQuote? = null, val quoting: Boolean = false) : NotesDialog
}

sealed interface NotesOutcome {
    data class Combined(val amountMsat: Long) : NotesOutcome
    data class Split(val firstMsat: Long, val secondMsat: Long) : NotesOutcome
    data class Moved(val arrivedMsat: Long, val host: String) : NotesOutcome
    data class Renewed(val amountMsat: Long) : NotesOutcome

    /** Asked the mints about notes: [ok] still there, [gone] spent or unknown, no answer from [unreachable]. */
    data class Checked(val ok: Int, val gone: Int, val unreachable: List<String>) : NotesOutcome
    data class KeyChanged(val host: String) : NotesOutcome

    /** The note to share turned out to be spent already. */
    data object AlreadySpent : NotesOutcome

    /** A recheck settled the change a confirming note was in: [landed], or it never did and the note is back. */
    data class Settled(val landed: Boolean) : NotesOutcome
    data object Pending : NotesOutcome
    data class Failed(val problem: NoteProblem) : NotesOutcome
}

/**
 * The Notes tab: the active account's notes per mint, and everything that can be done with them — open one (share,
 * split, recheck, renew), select several (combine, move, recheck), pull to recheck all.
 */
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val wallet: Wallet,
    private val preferences: AppPreferences,
    defaults: AppDefaults,
) : ViewModel() {
    private val ui = MutableStateFlow(NotesState(noteActions = defaults.noteActions, showMints = defaults.showMints))
    private val failuresBuilt = defaults.simulatedFailures
    private var quoteJob: Job? = null

    /** In developer mode, the local regtest mints are offered as move targets too. */
    private val devHosts = preferences.developerMode.map { on -> on to if (on) defaults.localDevMints else emptyList() }
    private val hosts = combine(wallet.homeHost, wallet.trustedHosts, ::Pair)
    private val context = combine(hosts, wallet.lastPolicyRun, wallet.noteStrategy, devHosts, preferences.notesSort) { (home, trusted), run, strategy, dev, sort ->
        Context(home, trusted, run?.notCollected.orEmpty(), strategy, dev.first, dev.second, sort?.let { runCatching { NoteSort.valueOf(it) }.getOrNull() } ?: NoteSort.Largest)
    }

    private data class Context(
        val home: String?,
        val trusted: Set<String>,
        val notCollected: List<NotCollected>,
        val strategy: NoteStrategy,
        val developer: Boolean,
        val devHosts: List<String>,
        val sort: NoteSort,
    )

    val state: StateFlow<NotesState> = combine(wallet.notes, context, ui) { notes, c, u ->
        val live = notes.filter { it.state == NoteState.Outstanding || it.state == NoteState.Paying || it.state == NoteState.Ambiguous }
        val mints = live.groupBy { it.host }.toList()
            .sortedWith(compareBy({ it.first != c.home }, { it.first }))
            .map { (host, list) ->
                val skipped = c.notCollected.firstOrNull { it.host == host }?.reason as? NotCollected.Reason.FeeTooHigh
                MintNotes(host, host == c.home, list.sortedWith(c.sort.order), skipped, trusted = host in c.trusted)
            }
        val selectable = live.filter(::selectable).map { it.id }.toSet()
        u.copy(
            loaded = true, mints = mints, shared = sharedNotes(notes, c.sort),
            sort = c.sort, selected = u.selected intersect selectable, open = u.open?.takeIf { id -> live.any { it.id == id } },
            noteStrategy = c.strategy, strategies = wallet.noteStrategies, developerMode = c.developer,
            failuresOffered = c.developer && failuresBuilt, simulatedFailure = u.simulatedFailure.takeIf { c.developer && failuresBuilt },
            knownHosts = (listOfNotNull(c.home) + mints.map { it.host } + c.devHosts).distinct(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesState())

    fun setSort(sort: NoteSort) {
        viewModelScope.launch { preferences.setNotesSort(sort.name) }
    }

    /** Opens a note's details, or in selection mode selects it. */
    fun tap(note: Note) {
        if (state.value.selecting) toggle(note) else ui.update { it.copy(open = note.id, outcome = null) }
    }

    /** Long-press: starts selecting with this note. */
    fun select(note: Note) {
        if (!state.value.canSelect) return
        ui.update { it.copy(selecting = true, open = null) }
        toggle(note)
    }

    fun startSelecting() {
        if (state.value.canSelect) ui.update { it.copy(selecting = true, open = null, outcome = null) }
    }

    fun stopSelecting() = ui.update { it.copy(selecting = false, selected = emptySet()) }

    fun close() = ui.update { it.copy(open = null) }

    fun dismissOutcome() = ui.update { it.copy(outcome = null) }

    /** For developers: the next action fails as [kind] says (null: it doesn't); see [NotesState.failuresOffered]. */
    fun setSimulatedFailure(kind: SimulatedFailure.Kind?) = ui.update { it.copy(simulatedFailure = kind) }

    fun sharedShown() = ui.update { it.copy(sharedId = null) }

    /** Notes are combined or moved one mint at a time: picking a note on another mint starts a new selection. */
    private fun toggle(note: Note) {
        if (!selectable(note)) return
        val notes = state.value.mints.flatMap { it.notes }
        ui.update { s ->
            val sameMint = s.selected.filter { id -> notes.firstOrNull { it.id == id }?.host == note.host }.toSet()
            s.copy(selected = if (note.id in sameMint) sameMint - note.id else sameMint + note.id, outcome = null)
        }
    }

    /** Pull to refresh: refreshes as Home does, then asks the mints about every note. */
    fun refresh() {
        if (ui.value.refreshing || ui.value.busy) return
        ui.update { it.copy(refreshing = true, outcome = null) }
        viewModelScope.launch {
            val outcome = try {
                val check = wallet.recheckNotes()
                NotesOutcome.Checked(check.checked - check.gone, check.gone, check.unreachable)
            } catch (e: IOException) {
                NotesOutcome.Failed(NoteProblem.Unreachable)
            } catch (e: MintKeyChangedException) {
                NotesOutcome.KeyChanged(e.host)
            } finally {
                ui.update { it.copy(refreshing = false) }
            }
            // Nothing to report when everything is as it was.
            ui.update { it.copy(outcome = outcome.takeUnless { o -> o is NotesOutcome.Checked && o.gone == 0 && o.unreachable.isEmpty() }) }
        }
    }

    fun recheck(id: String) = act(NoteAction.Recheck, setOf(id)) { recheckAll(it) }

    fun recheckSelected() = act(NoteAction.Recheck) { recheckAll(it) }

    private suspend fun recheckAll(ids: Set<String>): NotesOutcome {
        var ok = 0
        var gone = 0
        val unreachable = mutableListOf<String>()
        for (id in ids) {
            when (val check = wallet.recheckNote(id)) {
                is NoteCheck.Ok -> ok++
                NoteCheck.Spent, NoteCheck.Unknown -> gone++
                NoteCheck.Unreachable -> state.value.mints.firstOrNull { m -> m.notes.any { it.id == id } }?.let { unreachable += it.host }
                is NoteCheck.KeyChanged -> return NotesOutcome.KeyChanged(check.host)
                NoteCheck.NotFound -> Unit
                // Only a confirming note settles, and it is only rechecked on its own.
                NoteCheck.Landed -> return NotesOutcome.Settled(landed = true)
                NoteCheck.NotLanded -> return NotesOutcome.Settled(landed = false)
            }
        }
        return NotesOutcome.Checked(ok, gone, unreachable.distinct())
    }

    /** Renews a note: a fresh key, so any copy of the old one (a QR code shown by mistake) is worthless. */
    fun renew(id: String) = act(NoteAction.Renew, setOf(id)) {
        when (val r = wallet.rotateNote(id)) {
            is RotateResult.Rotated -> NotesOutcome.Renewed(r.amountMsat)
            RotateResult.Pending -> NotesOutcome.Pending
            is RotateResult.Failed -> NotesOutcome.Failed(if (r.noAnswer) NoteProblem.Unreachable else NoteProblem.Refused)
            is RotateResult.NotRotatable -> NotesOutcome.Failed(NoteProblem.NotFound)
        }
    }

    /** Hands the whole note out: once the mint confirms it, the screen moves on to its QR code ([NotesState.sharedId]). */
    fun share(id: String) = act(NoteAction.Share, setOf(id)) {
        when (val r = wallet.shareNote(id)) {
            is ShareNoteResult.Shared -> {
                ui.update { s -> s.copy(sharedId = r.note.id) }
                null
            }
            ShareNoteResult.AlreadySpent -> NotesOutcome.AlreadySpent
            ShareNoteResult.Unreachable -> NotesOutcome.Failed(NoteProblem.Unreachable)
            ShareNoteResult.NotShareable -> NotesOutcome.Failed(NoteProblem.NotFound)
        }
    }

    fun combine() = act(NoteAction.Combine) { ids ->
        when (val r = wallet.combineNotes(ids)) {
            is NoteActionResult.Done -> NotesOutcome.Combined(r.notes.sumOf { it.amountMsat })
            else -> r.toOutcome()
        }
    }

    fun openSplit(id: String) {
        if (NoteAction.Split !in state.value.noteActions) return
        viewModelScope.launch {
            val dialog = wallet.splitLimits(id)?.let { NotesDialog.Split(id, it) }?.takeIf { it.possible }
            ui.update {
                if (dialog == null) it.copy(outcome = NotesOutcome.Failed(NoteProblem.InvalidAmount)) else it.copy(dialog = dialog.copy(amountText = dialog.halfSats.toString()))
            }
        }
    }

    fun onSplitAmount(text: String) = ui.update { s ->
        val d = s.dialog as? NotesDialog.Split ?: return@update s
        s.copy(dialog = d.copy(amountText = text.filter(Char::isDigit).take(12)))
    }

    /** Splits the note; under a strategy that would undo it, switches to manual first (see [NotesState.switchesToManual]). */
    fun split() {
        val d = state.value.dialog as? NotesDialog.Split ?: return
        val amount = d.amountMsat ?: return
        val switchToManual = state.value.switchesToManual
        act(NoteAction.Split, setOf(d.id)) { ids ->
            if (switchToManual) wallet.setNoteStrategy(ManualStrategy.ID)
            when (val r = wallet.splitNote(ids.single(), amount)) {
                is NoteActionResult.Done -> r.notes.map { it.amountMsat }.let { NotesOutcome.Split(it.first(), it.getOrElse(1) { 0 }) }
                else -> r.toOutcome()
            }
        }
    }

    fun openMove() {
        if (NoteAction.Move !in state.value.noteActions) return
        val s = state.value
        val from = s.mints.firstOrNull { m -> m.notes.any { it.id in s.selected } }?.host
        val suggested = s.knownHosts.firstOrNull { it != from }.orEmpty()
        ui.update { it.copy(dialog = NotesDialog.Move(suggested)) }
        if (suggested.isNotEmpty()) requote(immediately = true)
    }

    fun onMoveTarget(text: String) {
        ui.update { s -> (s.dialog as? NotesDialog.Move)?.let { s.copy(dialog = it.copy(target = text.trim(), quote = null)) } ?: s }
        requote()
    }

    fun move() {
        val d = state.value.dialog as? NotesDialog.Move ?: return
        if (d.quote !is MintMoveQuote.Ready || d.quoting) return
        moveTo(state.value.selected, d.quote.toHost)
    }

    /** Moves everything on [host] to the home mint although collecting skipped it (the fee was over the limit). */
    fun moveAnyway(host: String) {
        val home = state.value.mints.firstOrNull { it.isHome }?.host ?: state.value.knownHosts.firstOrNull() ?: return
        moveTo(state.value.mints.firstOrNull { it.host == host }?.notes.orEmpty().filter(::selectable).map { it.id }.toSet(), home)
    }

    private fun moveTo(ids: Set<String>, host: String) = act(NoteAction.Move, ids) {
        when (val r = wallet.moveToMint(it, host)) {
            is MintMoveResult.Moved -> NotesOutcome.Moved(r.arrivedMsat, host)
            MintMoveResult.Pending -> NotesOutcome.Pending
            is MintMoveResult.Failed -> NotesOutcome.Failed(r.problem)
        }
    }

    fun dismissDialog() {
        quoteJob?.cancel()
        ui.update { it.copy(dialog = null) }
    }

    private fun requote(immediately: Boolean = false) {
        quoteJob?.cancel()
        val d = ui.value.dialog as? NotesDialog.Move ?: return
        val ids = state.value.selected
        if (d.target.isBlank() || ids.isEmpty()) {
            ui.update { it.copy(dialog = d.copy(quote = null, quoting = false)) }
            return
        }
        ui.update { it.copy(dialog = d.copy(quoting = true)) }
        quoteJob = viewModelScope.launch {
            if (!immediately) delay(QUOTE_DEBOUNCE_MILLIS)
            val quote = wallet.quoteMintMove(ids, d.target)
            ui.update { s -> (s.dialog as? NotesDialog.Move)?.let { s.copy(dialog = it.copy(quote = quote, quoting = false)) } ?: s }
        }
    }

    /**
     * Runs [action] on [ids] (the selection by default), if the brand offers it; a null outcome reports nothing.
     * Selection ends unless it failed.
     */
    private fun act(action: NoteAction, ids: Set<String> = state.value.selected, block: suspend (Set<String>) -> NotesOutcome?) {
        if (action !in ui.value.noteActions || ids.isEmpty() || ui.value.busy || ui.value.refreshing) return
        quoteJob?.cancel()
        // A failure armed for developers goes with the next action only, and is used up by it.
        val failure = state.value.simulatedFailure
        ui.update { it.copy(busy = true, dialog = null, outcome = null, simulatedFailure = null) }
        viewModelScope.launch {
            val outcome = try {
                SimulatedFailure.during(failure) { block(ids) }
            } catch (e: MintKeyChangedException) {
                NotesOutcome.KeyChanged(e.host)
            } finally {
                ui.update { it.copy(busy = false) }
            }
            ui.update {
                val failed = outcome is NotesOutcome.Failed
                it.copy(
                    outcome = outcome,
                    open = if (failed) it.open else null,
                    selected = if (failed) it.selected else emptySet(),
                    selecting = it.selecting && failed,
                )
            }
        }
    }

    private fun NoteActionResult.toOutcome(): NotesOutcome = when (this) {
        is NoteActionResult.Done -> NotesOutcome.Pending
        NoteActionResult.Pending -> NotesOutcome.Pending
        is NoteActionResult.Failed -> NotesOutcome.Failed(problem)
    }

    companion object {
        private const val QUOTE_DEBOUNCE_MILLIS = 400L

        /** Notes that can be combined, split, moved, rechecked, renewed or shared. */
        fun selectable(note: Note) = note.state == NoteState.Outstanding && note.kind == NoteKind.KeyPath && note.index != null

        /** Notes that can be rechecked: those, and a confirming one, whose change a recheck settles. */
        fun recheckable(note: Note) = selectable(note) || (note.state == NoteState.Ambiguous && note.kind == NoteKind.KeyPath)
    }
}
