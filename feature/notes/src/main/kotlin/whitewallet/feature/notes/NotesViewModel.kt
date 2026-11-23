package whitewallet.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import whitewallet.brand.NoteStrategy
import whitewallet.core.model.Note
import whitewallet.core.model.NoteKind
import whitewallet.core.model.NoteState
import whitewallet.core.model.ReceivedNote
import whitewallet.core.model.isShared
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.AppPreferences
import whitewallet.core.wallet.ManualStrategy
import whitewallet.core.wallet.MintKeyChangedException
import whitewallet.core.wallet.MintMoveQuote
import whitewallet.core.wallet.MintMoveResult
import whitewallet.core.wallet.NotCollected
import whitewallet.core.wallet.NoteAction
import whitewallet.core.wallet.NoteActionResult
import whitewallet.core.wallet.NoteCheck
import whitewallet.core.wallet.NoteProblem
import whitewallet.core.wallet.RotateResult
import whitewallet.core.wallet.ShareNoteResult
import whitewallet.core.wallet.SimulatedFailure
import whitewallet.core.wallet.SplitLimits
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.RevealsNoteSecret

/** How notes are ordered within a mint. The grouping by mint always stays: combining and moving work per mint. */
enum class NoteSort(internal val order: Comparator<Note>) {
    Largest(compareByDescending<Note> { it.amountMsat }.thenByDescending { it.updatedAtMillis }),
    Newest(compareByDescending<Note> { it.updatedAtMillis }.thenByDescending { it.amountMsat }),
}

/** The handed-out notes, waiting for their recipients, in the order the user chose for all notes. */
internal fun sharedNotes(notes: List<Note>, sort: NoteSort): List<Note> = notes.filter { it.state.isShared }.sortedWith(sort.order)

/** One mint's notes in the list. [skipped] is set when collecting at the home mint left them there (fee too high). */
data class MintNotes(
    val host: String,
    val isHome: Boolean,
    val notes: List<Note>,
    val skipped: NotCollected.Reason.FeeTooHigh? = null,
    /** The user trusts this mint (by its signing key; see Settings → Mints). */
    val trusted: Boolean = false,
) {
    /** What counts in the balance (notes being paid or confirmed are shown, but not counted), the biggest first. */
    val inBalance: List<Note>
        get() = notes.filter { it.state == NoteState.Outstanding }.sortedByDescending { it.amountMsat }

    val totalMsat: Long
        get() = inBalance.sumOf { it.amountMsat }
}

data class NotesState(
    /** The notes were read: until then the view draws nothing (rather than a total of nothing). */
    val loaded: Boolean = true,
    /** Notes in the balance or on their way, per mint: the home mint first, then the others by name. */
    val mints: List<MintNotes> = emptyList(),
    /** Notes handed out as links that nobody has claimed yet. */
    val shared: List<Note> = emptyList(),
    /** Notes received as links, not claimed yet or no longer claimable: not in the balance. */
    val received: List<ReceivedNote> = emptyList(),
    /** How many received notes are archived: no longer tried, only listed in the archive. */
    val archivedLinks: Int = 0,
    /** How many notes the user archived while their mint doesn't know them ([Note.isArchived]): only listed in the archive. */
    val archivedNotes: Int = 0,
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
    /** The phone is offline ([Wallet.offline]): the view says so, and a received note waits for it. */
    val offline: Boolean = false,
    /** Every mint that didn't answer the last time the wallet asked it: what a received note there waits for. */
    val notAnsweringHosts: Set<String> = emptySet(),
    /** Mints whose signing key doesn't match the one the wallet knows: nothing is sent there until it does. */
    val keyChangedHosts: Set<String> = emptySet(),
    /** The mint whose notes are open on a page of their own (sorting and selecting are there); null: all mints at a glance. */
    val mintPage: String? = null,
    /** Debug builds in developer mode: a note's sheet offers making the next action fail ([SimulatedFailure]). */
    val failuresOffered: Boolean = false,
    /** How the next action fails, once; null: it runs as it is. */
    val simulatedFailure: SimulatedFailure.Kind? = null,
) {
    private val all: List<Note>
        get() = mints.flatMap { it.notes }

    val totalMsat: Long
        get() = mints.sumOf { it.totalMsat }

    val noteCount: Int
        get() = all.size

    /** How many notes count in the balance. */
    val balanceNoteCount: Int
        get() = mints.sumOf { it.inBalance.size }

    /** The mint whose page is open. */
    val pageMint: MintNotes?
        get() = mintPage?.let { host -> mints.firstOrNull { it.host == host } }

    /** Notes their mint doesn't know, not archived yet: they need the user. */
    val unknownNotes: List<Note>
        get() = all.filter { it.state == NoteState.Unknown }

    /** Notes in a payment whose outcome isn't known yet: the wallet waits for the mint's answer. */
    val waitingNotes: List<Note>
        get() = all.filter { it.state == NoteState.Ambiguous }

    /** Mints holding notes that didn't answer (offline, none answers, which the view says on its own). */
    val silentMints: List<String>
        get() = if (offline) emptyList() else mints.map { it.host }.filter { it in notAnsweringHosts }

    /** Mints holding notes whose key changed. */
    val keyChangedMints: List<String>
        get() = mints.map { it.host }.filter { it in keyChangedHosts }

    /** The bill colours of [host]'s notes: the home mint's first, the other mints' in turn ([whitewallet.brand.ui.WalletColors.note]). */
    fun colorIndex(host: String): Int {
        val others = mints.filterNot { it.isHome }
        return if (mints.any { it.isHome && it.host == host }) 0 else others.indexOfFirst { it.host == host } + 1
    }

    val sharedMsat: Long
        get() = shared.sumOf { it.amountMsat }

    val receivedMsat: Long
        get() = received.filter { it.isPending }.sumOf { it.amountMsat ?: it.declaredMsat ?: 0 }

    val openNote: Note?
        get() = open?.let { id -> all.firstOrNull { it.id == id } }

    val openTrusted: Boolean
        get() = openNote?.let { n -> mints.any { it.host == n.host && it.trusted } } == true

    val selectedMsat: Long
        get() = all.filter { it.id in selected }.sumOf { it.amountMsat }

    /** What the archive holds. */
    val archivedCount: Int
        get() = archivedLinks + archivedNotes

    /** The selection is of notes their mint doesn't know: they can be archived, or asked about, nothing else. */
    val selectingUnknown: Boolean
        get() = selected.isNotEmpty() && all.filter { it.id in selected }.all(NotesViewModel::archivable)

    val canCombine: Boolean
        get() = !busy && selected.size >= 2 && !selectingUnknown

    val canAct: Boolean
        get() = !busy && selected.isNotEmpty()

    /** Selecting several notes only makes sense with an action that works on several, or with notes to archive. */
    val canSelect: Boolean
        get() = noteActions.any { it.bulk } || all.any(NotesViewModel::archivable)

    /** A header per mint, unless mints aren't named and there is only one. */
    val groupHeaders: Boolean
        get() = showMints || mints.size > 1

    /** A split by hand would be undone by the account's strategy, so it switches to manual first (if offered). */
    val switchesToManual: Boolean
        get() = noteStrategy?.reshapesNotes == true && strategies.any { it.id == ManualStrategy.ID }
}

sealed interface NotesDialog {
    data class Split(val id: String, val limits: SplitLimits, val amountText: String = "") : NotesDialog {
        /** Amounts are typed in whole sats, so the smallest is at least one sat. */
        val minMsat: Long
            get() = (limits.minMsat + 999) / 1000 * 1000

        /** Whether two notes of whole sats can come out of it at all (a 2-sat note with a 1-sat fee can't). */
        val possible: Boolean
            get() = minMsat <= limits.maxMsat

        /** Where the amount starts: half of what is left after the fee, in whole sats. */
        val halfSats: Long
            get() = ((limits.maxMsat + limits.minMsat) / 2 / 1000).coerceIn(minMsat / 1000, limits.maxMsat / 1000)

        val amountMsat: Long?
            get() = amountText.toLongOrNull()?.times(1000)?.takeIf { it in minMsat..limits.maxMsat }
    }

    data class Move(val target: String = "", val quote: MintMoveQuote? = null, val quoting: Boolean = false) : NotesDialog
}

sealed interface NotesOutcome {
    data class Combined(val amountMsat: Long) : NotesOutcome

    data class Split(val firstMsat: Long, val secondMsat: Long) : NotesOutcome

    data class Moved(val arrivedMsat: Long, val host: String) : NotesOutcome

    data class Renewed(val amountMsat: Long) : NotesOutcome

    /** [notes] their mint doesn't know went to the archive. */
    data class Archived(val notes: Int) : NotesOutcome

    /**
     * Asked the mints about notes: [ok] still there, [gone] spent elsewhere, [unknown] set aside as their mint doesn't know them, no answer
     * from [unreachable]. Notes the user handed out as links, which were never in the balance, are counted apart: [handedOut] their mint
     * knows again and they wait to be claimed, [handedOutSpent] spent by whoever holds the link, [handedOutUnknown] set aside as their mint
     * doesn't know them, [takenBack] taken back into the balance as the user asked, [takeBackRefused] whose take back the mint refused (it
     * is tried again).
     */
    data class Checked(
        val ok: Int,
        val gone: Int,
        val unreachable: List<String>,
        val unknown: Int = 0,
        val handedOut: Int = 0,
        val handedOutSpent: Int = 0,
        val handedOutUnknown: Int = 0,
        val takenBack: Int = 0,
        val takeBackRefused: Int = 0,
    ) : NotesOutcome {
        /** Something happened to a note the user handed out. */
        val handedOutNews: Boolean
            get() = handedOut + handedOutSpent + handedOutUnknown + takenBack + takeBackRefused > 0
    }

    data class KeyChanged(val host: String) : NotesOutcome

    /** The note to share turned out to be spent already. */
    data object AlreadySpent : NotesOutcome

    /** A recheck settled the change a confirming note was in: [landed], or it never did and the note is back. */
    data class Settled(val landed: Boolean) : NotesOutcome

    data object Pending : NotesOutcome

    data class Failed(val problem: NoteProblem) : NotesOutcome
}

/**
 * The Notes tab: the active account's notes per mint, and everything that can be done with them — open one (share, split, recheck, renew),
 * select several (combine, move, recheck), pull to recheck all.
 */
@HiltViewModel
class NotesViewModel
@Inject
constructor(
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
    private val context =
        combine(hosts, wallet.lastPolicyRun, wallet.noteStrategy, devHosts, preferences.notesSort) {
            (home, trusted),
            run,
            strategy,
            dev,
            sort ->
            Context(
                home,
                trusted,
                notCollected = run?.notCollected.orEmpty(),
                strategy,
                developer = dev.first,
                devHosts = dev.second,
                sort = sort?.let { runCatching { NoteSort.valueOf(it) }.getOrNull() } ?: NoteSort.Largest,
            )
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

    /** Whether the phone is offline, the mints that didn't answer (what a received note waits for), and those whose key changed. */
    private val reach = combine(wallet.offline, wallet.mintsNotAnswering, wallet.mintsWithChangedKey, ::Triple)

    val state: StateFlow<NotesState> =
        combine(wallet.notes, wallet.receivedNotes, context, ui, reach) { notes, received, c, u, (offline, notAnswering, keyChanged) ->
                val live = notes.filter {
                    it.state == NoteState.Outstanding ||
                        it.state == NoteState.Paying ||
                        it.state == NoteState.Ambiguous ||
                        (it.state == NoteState.Unknown && !it.isArchived)
                }
                val mints =
                    live
                        .groupBy { it.host }
                        .toList()
                        .sortedWith(compareBy({ it.first != c.home }, { it.first }))
                        .map { (host, list) ->
                            val skipped = c.notCollected.firstOrNull { it.host == host }?.reason as? NotCollected.Reason.FeeTooHigh
                            MintNotes(
                                host,
                                isHome = host == c.home,
                                notes = list.sortedWith(c.sort.order),
                                skipped,
                                trusted = host in c.trusted,
                            )
                        }
                val pickable = live.filter(::pickable).map { it.id }.toSet()
                u.copy(
                    loaded = true,
                    mints = mints,
                    shared = sharedNotes(notes, c.sort),
                    received = ReceivedNote.shown(received),
                    offline = offline,
                    notAnsweringHosts = notAnswering,
                    keyChangedHosts = keyChanged,
                    mintPage = u.mintPage?.takeIf { host -> mints.any { it.host == host } },
                    archivedLinks = ReceivedNote.archived(received).size,
                    archivedNotes = notes.count { it.isArchived },
                    sort = c.sort,
                    selected = u.selected intersect pickable,
                    open = u.open?.takeIf { id -> live.any { it.id == id } },
                    noteStrategy = c.strategy,
                    strategies = wallet.noteStrategies,
                    developerMode = c.developer,
                    failuresOffered = c.developer && failuresBuilt,
                    simulatedFailure = u.simulatedFailure.takeIf { c.developer && failuresBuilt },
                    knownHosts = (listOfNotNull(c.home) + mints.map { it.host } + c.devHosts).distinct(),
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesState(loaded = false))

    /** Forgets a received note someone else spent. */
    fun removeReceived(id: String) {
        viewModelScope.launch { wallet.removeReceivedNote(id) }
    }

    /** Puts a kept received note aside, into the archive. */
    fun archiveReceived(id: String) {
        viewModelScope.launch { wallet.archiveReceivedNote(id) }
    }

    /** Opens a received note that only Lightning can bring in, in Claim ([open] gets its link). */
    fun receiveReceived(id: String, open: (String) -> Unit) {
        viewModelScope.launch { @OptIn(RevealsNoteSecret::class) wallet.receivedNoteLink(id)?.let(open) }
    }

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

    /** Opens [host]'s notes on a page of their own. */
    fun openMint(host: String) = ui.update { it.copy(mintPage = host, outcome = null) }

    /** Back to all mints at a glance; a selection ends with the page. */
    fun closeMint() = ui.update { it.copy(mintPage = null, selecting = false, selected = emptySet()) }

    fun dismissOutcome() = ui.update { it.copy(outcome = null) }

    /** For developers: the next action fails as [kind] says (null: it doesn't); see [NotesState.failuresOffered]. */
    fun setSimulatedFailure(kind: SimulatedFailure.Kind?) = ui.update { it.copy(simulatedFailure = kind) }

    fun sharedShown() = ui.update { it.copy(sharedId = null) }

    /**
     * Notes are combined or moved one mint at a time, and those their mint doesn't know only archived: picking a note on another mint, or
     * of the other kind, starts a new selection.
     */
    private fun toggle(note: Note) {
        if (!pickable(note)) return
        val notes = state.value.mints.flatMap { it.notes }
        ui.update { s ->
            val alike =
                s.selected
                    .filter { id ->
                        notes.firstOrNull { it.id == id }?.let { it.host == note.host && archivable(it) == archivable(note) } == true
                    }
                    .toSet()
            s.copy(selected = if (note.id in alike) alike - note.id else alike + note.id, outcome = null)
        }
    }

    /** Pull to refresh: refreshes as Home does, then asks the mints about every note. */
    fun refresh() {
        if (ui.value.refreshing || ui.value.busy) return
        ui.update { it.copy(refreshing = true, outcome = null) }
        viewModelScope.launch {
            val outcome =
                try {
                    val check = wallet.recheckNotes()
                    NotesOutcome.Checked(
                        check.checked - check.gone - check.unknown,
                        check.gone,
                        check.unreachable,
                        check.unknown,
                        check.handedOutAgain,
                        handedOutUnknown = check.handedOutUnknown,
                        takenBack = check.takenBack,
                        takeBackRefused = check.takeBackRefused,
                    )
                } catch (e: IOException) {
                    NotesOutcome.Failed(NoteProblem.Unreachable)
                } catch (e: MintKeyChangedException) {
                    NotesOutcome.KeyChanged(e.host)
                } finally {
                    ui.update { it.copy(refreshing = false) }
                }
            // Nothing to report when everything is as it was.
            ui.update {
                it.copy(
                    outcome =
                        outcome.takeUnless { o ->
                            o is NotesOutcome.Checked && o.gone == 0 && o.unknown == 0 && !o.handedOutNews && o.unreachable.isEmpty()
                        }
                )
            }
        }
    }

    fun recheck(id: String) = act(NoteAction.Recheck, setOf(id)) { recheckAll(it) }

    fun recheckSelected() = act(NoteAction.Recheck) { recheckAll(it) }

    private suspend fun recheckAll(ids: Set<String>): NotesOutcome {
        var ok = 0
        var gone = 0
        var unknown = 0
        var handedOut = 0
        var handedOutSpent = 0
        var handedOutUnknown = 0
        var takenBack = 0
        var takeBackRefused = 0
        val unreachable = mutableListOf<String>()
        for (id in ids) {
            // One handed out as a link was never in the balance: what happens to it is said apart.
            val handedOutNote = state.value.mints.any { m -> m.notes.any { it.id == id && it.handedOut != null } }
            when (val check = wallet.recheckNote(id)) {
                is NoteCheck.Ok -> ok++
                NoteCheck.Spent -> if (handedOutNote) handedOutSpent++ else gone++
                NoteCheck.Unknown -> if (handedOutNote) handedOutUnknown++ else unknown++
                NoteCheck.HandedOutAgain -> handedOut++
                is NoteCheck.TakenBack -> takenBack++
                is NoteCheck.TakeBackRefused -> takeBackRefused++
                NoteCheck.Unreachable -> state.value.mints.firstOrNull { m -> m.notes.any { it.id == id } }?.let { unreachable += it.host }
                is NoteCheck.KeyChanged -> return NotesOutcome.KeyChanged(check.host)
                NoteCheck.NotFound -> Unit
                // Only a confirming note settles, and it is only rechecked on its own.
                NoteCheck.Landed -> return NotesOutcome.Settled(landed = true)
                NoteCheck.NotLanded -> return NotesOutcome.Settled(landed = false)
            }
        }
        return NotesOutcome.Checked(
            ok,
            gone,
            unreachable.distinct(),
            unknown,
            handedOut,
            handedOutSpent,
            handedOutUnknown,
            takenBack,
            takeBackRefused,
        )
    }

    /** Archives one note its mint doesn't know, from its details. */
    fun archive(id: String) = archiveAll(setOf(id))

    fun archiveSelected() = archiveAll(state.value.selected)

    /**
     * Moves [ids] (notes their mint doesn't know) to the archive: Home no longer warns of them. Offered whatever the brand's note actions,
     * as the only way to quiet that warning.
     */
    private fun archiveAll(ids: Set<String>) {
        if (ids.isEmpty() || ui.value.busy || ui.value.refreshing) return
        ui.update { it.copy(busy = true, dialog = null, outcome = null) }
        viewModelScope.launch {
            val archived =
                try {
                    wallet.archiveUnknownNotes(ids)
                } finally {
                    ui.update { it.copy(busy = false) }
                }
            ui.update { it.copy(outcome = NotesOutcome.Archived(archived), open = null, selected = emptySet(), selecting = false) }
        }
    }

    /** Renews a note: a fresh key, so any copy of the old one (a QR code shown by mistake) is worthless. */
    fun renew(id: String) =
        act(NoteAction.Renew, setOf(id)) {
            when (val r = wallet.rotateNote(id)) {
                is RotateResult.Rotated -> NotesOutcome.Renewed(r.amountMsat)
                RotateResult.Pending -> NotesOutcome.Pending
                is RotateResult.Failed if r.keyChanged -> keyChanged(setOf(id))
                is RotateResult.Failed -> NotesOutcome.Failed(if (r.noAnswer) NoteProblem.Unreachable else NoteProblem.Refused)
                is RotateResult.NotRotatable -> NotesOutcome.Failed(NoteProblem.NotFound)
            }
        }

    /** Hands the whole note out (offline too, unconfirmed), then the screen moves on to its QR code ([NotesState.sharedId]). */
    fun share(id: String) =
        act(NoteAction.Share, setOf(id)) {
            when (val r = wallet.shareNote(id)) {
                is ShareNoteResult.Shared -> {
                    ui.update { s -> s.copy(sharedId = r.note.id) }
                    null
                }
                ShareNoteResult.AlreadySpent -> NotesOutcome.AlreadySpent
                ShareNoteResult.Unreachable -> NotesOutcome.Failed(NoteProblem.Unreachable)
                ShareNoteResult.UnknownToMint -> NotesOutcome.Checked(ok = 0, gone = 0, unreachable = emptyList(), unknown = 1)
                ShareNoteResult.NotShareable -> NotesOutcome.Failed(NoteProblem.NotFound)
            }
        }

    fun combine() =
        act(NoteAction.Combine) { ids ->
            when (val r = wallet.combineNotes(ids)) {
                is NoteActionResult.Done -> NotesOutcome.Combined(r.notes.sumOf { it.amountMsat })
                else -> r.toOutcome(ids)
            }
        }

    fun openSplit(id: String) {
        if (NoteAction.Split !in state.value.noteActions) return
        viewModelScope.launch {
            val dialog = wallet.splitLimits(id)?.let { NotesDialog.Split(id, it) }?.takeIf { it.possible }
            ui.update {
                if (dialog == null) it.copy(outcome = NotesOutcome.Failed(NoteProblem.InvalidAmount))
                else it.copy(dialog = dialog.copy(amountText = dialog.halfSats.toString()))
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
                else -> r.toOutcome(ids)
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

    private fun moveTo(ids: Set<String>, host: String) =
        act(NoteAction.Move, ids) {
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
     * Runs [action] on [ids] (the selection by default), if the brand offers it; a null outcome reports nothing. Selection ends unless it
     * failed.
     */
    private fun act(action: NoteAction, ids: Set<String> = state.value.selected, block: suspend (Set<String>) -> NotesOutcome?) {
        if (action !in ui.value.noteActions || ids.isEmpty() || ui.value.busy || ui.value.refreshing) return
        quoteJob?.cancel()
        // A failure armed for developers goes with the next action only, and is used up by it.
        val failure = state.value.simulatedFailure
        ui.update { it.copy(busy = true, dialog = null, outcome = null, simulatedFailure = null) }
        viewModelScope.launch {
            val outcome =
                try {
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

    private fun NoteActionResult.toOutcome(ids: Set<String>): NotesOutcome =
        when (this) {
            is NoteActionResult.Done -> NotesOutcome.Pending
            NoteActionResult.Pending -> NotesOutcome.Pending
            is NoteActionResult.Failed if problem == NoteProblem.KeyChanged -> keyChanged(ids)
            is NoteActionResult.Failed -> NotesOutcome.Failed(problem)
        }

    /** The signing key of the mint [ids] are on doesn't match: said with its name, as a check that finds it does. */
    private fun keyChanged(ids: Set<String>): NotesOutcome =
        state.value.mints.firstOrNull { m -> m.notes.any { it.id in ids } }?.host?.let(NotesOutcome::KeyChanged)
            ?: NotesOutcome.Failed(NoteProblem.KeyChanged)

    companion object {
        private const val QUOTE_DEBOUNCE_MILLIS = 400L

        /** Notes that can be combined, split, moved, rechecked, renewed or shared. */
        fun selectable(note: Note) = note.state == NoteState.Outstanding && note.kind == NoteKind.KeyPath && note.index != null

        /** A note its mint doesn't know, not archived yet: it can be archived (and rechecked). */
        fun archivable(note: Note) =
            note.state == NoteState.Unknown && !note.isArchived && note.kind == NoteKind.KeyPath && note.index != null

        /** Notes that can be selected: [selectable] ones, or [archivable] ones (never both at once, see [toggle]). */
        fun pickable(note: Note) = selectable(note) || archivable(note)

        /**
         * Notes that can be rechecked: those, a confirming one, whose change a recheck settles, and an unknown one, which a recheck brings
         * back once its mint knows it again.
         */
        fun recheckable(note: Note) =
            selectable(note) || ((note.state == NoteState.Ambiguous || note.state == NoteState.Unknown) && note.kind == NoteKind.KeyPath)
    }
}
