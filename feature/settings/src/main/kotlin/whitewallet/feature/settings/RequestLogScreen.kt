package whitewallet.feature.settings

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import whitewallet.core.designsystem.localTime
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.lnurlcash.client.LogEntry
import whitewallet.lnurlcash.client.RequestLog

@HiltViewModel
class RequestLogViewModel @Inject constructor(private val log: RequestLog) : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = log.entries
    fun clear() = log.clear()
}

@Composable
fun RequestLogRoute(onBack: () -> Unit, viewModel: RequestLogViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    RequestLogScreen(entries, onBack, viewModel::clear)
}

/** Developer page: every request to a service, newest first. Tap an entry to see the full URL and response. */
@Composable
fun RequestLogScreen(entries: List<LogEntry>, onBack: () -> Unit, onClear: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        WalletTopBar(stringResource(R.string.logs_title), onBack) {
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("request log", entries.joinToString("\n\n") { it.asText() }))) }
            }, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.logs_copy)) }
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.logs_clear)) }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(stringResource(R.string.logs_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.logs_empty), modifier = Modifier.padding(16.dp)) }
            }
            items(entries, key = { it.id }) { entry ->
                LogRow(entry)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val failed = entry.status == null || entry.status !in 200..299 || entry.responseBody?.contains("\"ERROR\"") == true
    Column(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.method, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
            Text(
                entry.status?.toString() ?: stringResource(R.string.logs_failed),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text("${entry.durationMillis} ms · ${time(entry.timestampMillis)}", style = MaterialTheme.typography.labelMedium)
        }
        Text(
            if (expanded) entry.url else entry.url.substringAfter("://").substringBefore('?'),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        if (expanded) {
            entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                entry.responseBody?.let(::pretty) ?: stringResource(R.string.logs_no_body),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

private val prettyJson = Json { prettyPrint = true }

private fun pretty(body: String): String =
    runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(body)) }.getOrDefault(body)

private fun time(millis: Long): String = localTime(millis, "jms")

private fun LogEntry.asText(): String =
    "${time(timestampMillis)} $method ${status ?: "failed"} ${durationMillis}ms\n$url\n" + (error ?: "") + (responseBody ?: "")
