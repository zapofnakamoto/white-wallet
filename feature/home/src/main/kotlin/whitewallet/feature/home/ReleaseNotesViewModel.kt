package whitewallet.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import whitewallet.brand.ReleaseNote
import whitewallet.core.wallet.ReleaseNoteTracker

/** The release notes Home shows once, after an update (or important ones on a fresh install). */
@HiltViewModel
class ReleaseNotesViewModel @Inject constructor(private val tracker: ReleaseNoteTracker) : ViewModel() {
    private val pending = MutableStateFlow<List<ReleaseNote>>(emptyList())

    /** The notes to show now, newest first; empty when there are none. */
    val unseen: StateFlow<List<ReleaseNote>> = pending

    init {
        viewModelScope.launch { pending.value = tracker.unseen() }
    }

    /** "Got it": every note shown counts as seen. */
    fun seen() {
        val shown = pending.value
        pending.value = emptyList()
        viewModelScope.launch { tracker.markSeen(shown) }
    }

    /** Swiped away: ordinary notes count as seen, important ones come back at the next start until "Got it". */
    fun dismissed() {
        val shown = pending.value
        pending.value = emptyList()
        viewModelScope.launch { tracker.markDismissed(shown) }
    }
}
