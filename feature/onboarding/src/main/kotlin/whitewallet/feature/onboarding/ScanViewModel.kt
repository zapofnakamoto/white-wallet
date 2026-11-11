package whitewallet.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.wallet.MintInfo
import whitewallet.core.wallet.ScanProgress
import whitewallet.core.wallet.ScanResult
import whitewallet.core.wallet.Wallet

data class ScanState(
    /** The active wallet's mints, each saying whether its keys were looked through ([MintInfo.scan]). */
    val mints: List<MintInfo> = emptyList(),
    val scanning: Boolean = false,
    /** The mint being looked through, and how far. */
    val host: String? = null,
    val progress: ScanProgress? = null,
    /** What the last scan here came to. */
    val result: ScanResult? = null,
    /** The wallet's mints were read: until then the page shows none, and no button. */
    val loaded: Boolean = true,
)

private data class Run(
    val scanning: Boolean = false,
    val host: String? = null,
    val progress: ScanProgress? = null,
    val result: ScanResult? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(private val wallet: Wallet) : ViewModel() {
    private val run = MutableStateFlow(Run())
    private var started = false

    val state: StateFlow<ScanState> =
        combine(wallet.mints, run) { mints, run ->
                ScanState(mints.filter { it.scan != null }, run.scanning, run.host, run.progress, run.result)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanState(loaded = false))

    /** Call once from the screen; [start]: the scan begins at once (opened from the main screen's notice). */
    fun init(start: Boolean) {
        if (started) return
        started = true
        if (start) scan()
    }

    /** Looks through every mint of the wallet (see [Wallet.scan]); it goes on to its end if the page is left. */
    fun scan() {
        if (run.value.scanning) return
        run.value = Run(scanning = true)
        viewModelScope.launch {
            val result = wallet.scan { host, progress -> run.update { it.copy(host = host, progress = progress) } }
            run.value = Run(result = result)
        }
    }
}
