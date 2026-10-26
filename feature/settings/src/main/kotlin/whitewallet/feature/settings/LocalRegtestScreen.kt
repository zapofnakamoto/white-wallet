package whitewallet.feature.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.formatAmount
import whitewallet.core.wallet.AppDefaults
import whitewallet.core.wallet.Wallet
import whitewallet.lnurlcash.client.LnurlcashClient
import whitewallet.lnurlcash.protocol.ProtocolViolation

sealed interface LocalMintStatus {
    data object Checking : LocalMintStatus
    data class Reachable(val minMsat: Long, val maxMsat: Long, val feeBaseMsat: Long?, val feePpm: Long?, val mintPubkey: String?) : LocalMintStatus
    data class Unreachable(val reason: String) : LocalMintStatus
}

/** One local mint: [noteFlag] is what `regtest.sh note` needs to mint on it (empty for the first). */
data class LocalMint(
    val host: String,
    val noteFlag: String,
    val status: LocalMintStatus = LocalMintStatus.Checking,
    /** The active account's address on this mint, if it has one. */
    val address: String? = null,
)

data class LocalRegtestState(val mints: List<LocalMint> = emptyList())

@HiltViewModel
class LocalRegtestViewModel @Inject constructor(
    private val client: LnurlcashClient,
    private val wallet: Wallet,
    defaults: AppDefaults,
) : ViewModel() {
    private val _state = MutableStateFlow(
        LocalRegtestState(defaults.localDevServices.mapIndexed { i, host -> LocalMint(host, if (i == 0) "" else " --mint${i + 1}") }),
    )
    val state: StateFlow<LocalRegtestState> = _state

    init {
        check()
    }

    fun check() {
        _state.update { s -> s.copy(mints = s.mints.map { it.copy(status = LocalMintStatus.Checking) }) }
        _state.value.mints.forEach { mint ->
            viewModelScope.launch {
                val status = status(mint.host)
                val address = wallet.addresses.first().map { it.address }.firstOrNull { it.host == mint.host }?.address
                _state.update { s -> s.copy(mints = s.mints.map { if (it.host == mint.host) it.copy(status = status, address = address) else it }) }
            }
        }
    }

    private suspend fun status(host: String): LocalMintStatus = try {
        val pay = client.servicePayRequest(host)
        val key = runCatching { client.withdrawDiscovery(host).mintPubkey?.toHex() }.getOrNull()
        LocalMintStatus.Reachable(pay.pay.minSendable, pay.pay.maxSendable, pay.fee?.baseMsat, pay.fee?.ppm, key)
    } catch (e: IOException) {
        LocalMintStatus.Unreachable(e.message ?: "connection failed")
    } catch (e: ProtocolViolation) {
        LocalMintStatus.Unreachable(e.message ?: "not an LNURLcash server")
    }
}

@Composable
fun LocalRegtestRoute(onBack: () -> Unit, onAddAccount: (host: String) -> Unit, viewModel: LocalRegtestViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LocalRegtestScreen(state, onBack, viewModel::check, onAddAccount)
}

/**
 * Developer page: the local regtest mints (dev/regtest: two, to try notes on several services), their status, and the
 * commands to drive them from the computer.
 */
@Composable
fun LocalRegtestScreen(state: LocalRegtestState, onBack: () -> Unit, onRecheck: () -> Unit, onAddAccount: (host: String) -> Unit) {
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.regtest_title), onBack) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.mints.any { it.status is LocalMintStatus.Unreachable }) {
                Text(stringResource(R.string.regtest_fix), style = MaterialTheme.typography.bodyMedium)
                Command("./dev/regtest/regtest.sh up")
                Command("./dev/regtest/regtest.sh connect")
            }
            state.mints.forEach { mint -> MintCard(mint, onRecheck) { onAddAccount(mint.host) } }
            Text(stringResource(R.string.regtest_multi_hint), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.regtest_commands), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.regtest_pay_invoice), style = MaterialTheme.typography.bodySmall)
            Command("./dev/regtest/regtest.sh pay lnbcrt…")
            Text(stringResource(R.string.regtest_other), style = MaterialTheme.typography.bodySmall)
            Command("./dev/regtest/regtest.sh mine")
            Command("./dev/regtest/regtest.sh status")
            Command("./dev/regtest/regtest.sh reset")
        }
    }
}

@Composable
private fun MintCard(mint: LocalMint, onRecheck: () -> Unit, onAddAccount: () -> Unit) {
    StatusCard(mint.host, mint.status, onRecheck)
    PrimaryButton(stringResource(R.string.regtest_add_account, mint.host), onAddAccount, enabled = mint.status is LocalMintStatus.Reachable)
    Text(stringResource(R.string.regtest_pay_address), style = MaterialTheme.typography.bodySmall)
    Command("./dev/regtest/regtest.sh pay ${mint.address ?: "<name>@${mint.host}"} 1000")
    Text(stringResource(R.string.regtest_make_note), style = MaterialTheme.typography.bodySmall)
    Command("./dev/regtest/regtest.sh note 1000${mint.noteFlag}")
}

@Composable
private fun StatusCard(host: String, status: LocalMintStatus, onRecheck: () -> Unit) {
    val (container, title) = when (status) {
        LocalMintStatus.Checking -> CardDefaults.cardColors() to stringResource(R.string.regtest_checking)
        is LocalMintStatus.Reachable -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) to
            stringResource(R.string.regtest_reachable, host)
        is LocalMintStatus.Unreachable -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) to
            stringResource(R.string.regtest_unreachable, host)
    }
    Card(colors = container, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when (status) {
                is LocalMintStatus.Reachable -> {
                    Text(stringResource(R.string.regtest_limits, formatAmount(status.minMsat), formatAmount(status.maxMsat)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.regtest_fee, status.feeBaseMsat ?: 0, status.feePpm ?: 0), style = MaterialTheme.typography.bodySmall)
                    status.mintPubkey?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
                is LocalMintStatus.Unreachable -> Text(status.reason, style = MaterialTheme.typography.bodySmall)
                LocalMintStatus.Checking -> Unit
            }
            OutlinedButton(onClick = onRecheck) { Text(stringResource(R.string.regtest_recheck)) }
        }
    }
}

@Composable
private fun Command(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth()) {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
        TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("command", text))) } }) {
            Text(stringResource(R.string.logs_copy))
        }
    }
}
