package whitewallet.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.core.designsystem.ChevronIcon
import whitewallet.core.designsystem.LocalAmountUnit
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.wallet.AddressStatus
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletTextField

@Composable
fun SettingsRoute(
    versionName: String,
    developerPages: List<DeveloperPage>,
    onBack: () -> Unit,
    onNavigate: (SearchDestination) -> Unit,
    brandPages: List<BrandPage> = emptyList(),
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state, versionName, developerPages, onBack, brandPages, onNavigate)
}

/** A page of the brand's own, listed in Settings above About (see docs/WHITE_LABEL.md, "Design"). */
data class BrandPage(@StringRes val title: Int, val route: String)

/**
 * The settings hub: a short list of pages, each with a live one-line summary, and a search over every setting
 * (see [SettingsSearch]).
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    versionName: String,
    developerPages: List<DeveloperPage>,
    onBack: () -> Unit,
    brandPages: List<BrandPage> = emptyList(),
    onNavigate: (SearchDestination) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val open = { target: SettingsTarget -> onNavigate(SearchDestination.Page(target)) }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (state.accountName != null && !state.backupVerified) BackupWarning { open(SettingsTarget.Backup) }
            WalletTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.settings_search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.settings_search_clear))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (query.isBlank()) {
                Hub(state, versionName, open, brandPages) { onNavigate(SearchDestination.Route(it)) }
            } else {
                SearchResults(query, state.developerMode, developerPages, state.hidden, onNavigate, brandPages)
            }
        }
    }
}

@Composable
private fun Hub(state: SettingsState, versionName: String, open: (SettingsTarget) -> Unit, brandPages: List<BrandPage>, openRoute: (String) -> Unit) {
    // Only links: the account's own page first, the app-wide pages below (Design Guide: multiple wallets).
    state.accountName?.let { name ->
        val (status, color) = addressSummary(state.addressStatus)
        SummaryRow(
            stringResource(R.string.settings_account_section, name),
            state.profile?.address ?: stringResource(R.string.settings_account_contents),
            extra = state.lastPolicyRun?.stoppedEarly?.let { stringResource(R.string.settings_policy_stopped, it) }
                ?: status.takeIf { color == MaterialTheme.colorScheme.error },
            extraColor = MaterialTheme.colorScheme.error,
        ) { open(SettingsTarget.Account) }
        HorizontalDivider()
    }
    if (SettingsTarget.Mints !in state.hidden) {
        SummaryRow(
            stringResource(R.string.settings_mints),
            pluralStringResource(R.plurals.settings_mints_summary, state.mintCount, state.mintCount, state.trustedCount),
        ) { open(SettingsTarget.Mints) }
    }
    SummaryRow(
        stringResource(R.string.security_title),
        stringResource(if (state.appLock) R.string.settings_app_lock_on else R.string.settings_app_lock_off),
    ) { open(SettingsTarget.Security) }
    if (SettingsTarget.Display !in state.hidden) {
        SummaryRow(stringResource(R.string.settings_amounts), unitExample(LocalAmountUnit.current)) { open(SettingsTarget.Display) }
    }
    if (SettingsTarget.Developer !in state.hidden) {
        SummaryRow(
            stringResource(R.string.settings_developer),
            stringResource(if (state.developerMode) R.string.settings_on else R.string.settings_off),
        ) { open(SettingsTarget.Developer) }
    }
    brandPages.forEach { page ->
        WalletListItem(
            headlineContent = { Text(stringResource(page.title)) },
            trailingContent = { ChevronIcon() },
            modifier = Modifier.clickable { openRoute(page.route) },
        )
    }
    SummaryRow(stringResource(R.string.about_title), stringResource(R.string.about_version, versionName)) { open(SettingsTarget.About) }
}

/** Shown above everything while the account's recovery phrase isn't backed up: one level down, it would be missed. */
@Composable
private fun BackupWarning(onClick: () -> Unit) {
    SummaryRow(
        stringResource(R.string.settings_backup_now),
        stringResource(R.string.settings_backup_now_hint),
        titleColor = MaterialTheme.colorScheme.error,
        onClick = onClick,
    )
}

@Composable
fun AccountSettingsRoute(onBack: () -> Unit, onOpen: (SettingsTarget) -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountSettingsScreen(state, onBack, onOpen)
}

/** Everything that belongs to the active account: its recovery phrase, addresses, home mint, notes, and deleting it. */
@Composable
fun AccountSettingsScreen(state: SettingsState, onBack: () -> Unit, open: (SettingsTarget) -> Unit) {
    val title = state.accountName?.let { stringResource(R.string.settings_account_section, it) } ?: stringResource(R.string.settings_account)
    Scaffold(topBar = { WalletTopBar(title, onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SummaryRow(
                stringResource(R.string.settings_recovery_phrase),
                stringResource(if (state.backupVerified) R.string.settings_backed_up else R.string.settings_not_backed_up),
                if (state.backupVerified) Color.Unspecified else MaterialTheme.colorScheme.error,
            ) { open(SettingsTarget.Backup) }
            val profile = state.profile
            if (profile != null) {
                val (status, color) = addressSummary(state.addressStatus)
                val summary = if (state.otherAddresses > 0) {
                    pluralStringResource(R.plurals.settings_address_with_more, state.otherAddresses, profile.address, state.otherAddresses)
                } else {
                    profile.address
                }
                SummaryRow(stringResource(R.string.addresses_title), summary, extra = status, extraColor = color) { open(SettingsTarget.Addresses) }
            } else if (state.accountName != null) {
                SummaryRow(stringResource(R.string.addresses_title), stringResource(R.string.settings_no_address)) { open(SettingsTarget.Addresses) }
            }
            state.homeHost?.takeIf { SettingsTarget.Service !in state.hidden }?.let { host ->
                SummaryRow(stringResource(R.string.settings_home_mint), stringResource(R.string.settings_home_mint_hint, host)) { open(SettingsTarget.Service) }
            }
            if (SettingsTarget.NoteList !in state.hidden) {
                SummaryRow(stringResource(R.string.settings_note_list), stringResource(R.string.settings_note_list_hint)) { open(SettingsTarget.NoteList) }
            }
            if (SettingsTarget.Notes !in state.hidden) {
                SummaryRow(
                    stringResource(R.string.settings_notes),
                    notesSummary(state),
                    extra = state.lastPolicyRun?.stoppedEarly?.let { stringResource(R.string.settings_policy_stopped, it) },
                    extraColor = MaterialTheme.colorScheme.error,
                ) { open(SettingsTarget.Notes) }
            }
            HorizontalDivider()
            SummaryRow(
                stringResource(R.string.settings_delete),
                stringResource(R.string.settings_delete_hint),
                titleColor = MaterialTheme.colorScheme.error,
            ) { open(SettingsTarget.Delete) }
        }
    }
}

@Composable
private fun SearchResults(
    query: String,
    developerMode: Boolean,
    developerPages: List<DeveloperPage>,
    hidden: Set<SettingsTarget>,
    onNavigate: (SearchDestination) -> Unit,
    brandPages: List<BrandPage>,
) {
    val entries = SettingsSearch.entries.filterNot { (it.destination as? SearchDestination.Page)?.target in hidden }
    val items = (entries + SettingsSearch.developerEntries(developerPages) + SettingsSearch.brandEntries(brandPages)).map { entry ->
        SearchItem(
            title = stringResource(entry.title),
            location = stringResource(locationOf(entry.destination)),
            keywords = entry.keywords?.let { stringResource(it) } ?: "",
            destination = entry.destination,
            developerOnly = entry.developerOnly,
        )
    }
    val results = SettingsSearch.search(items, query, developerMode)
    if (results.isEmpty()) {
        Text(
            stringResource(R.string.settings_search_empty, query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
    results.forEach { item ->
        WalletListItem(
            headlineContent = { Text(item.title) },
            supportingContent = { Text(item.location) },
            trailingContent = { ChevronIcon() },
            modifier = Modifier.clickable { onNavigate(item.destination) },
        )
    }
}

private fun locationOf(destination: SearchDestination): Int = when (destination) {
    // Developer pages live under developer/; anything else (a brand's page) is listed on the settings page itself.
    is SearchDestination.Route -> if (destination.route.startsWith("developer/")) R.string.developer_title else R.string.settings_title
    is SearchDestination.Page -> when (destination.target) {
        SettingsTarget.Account -> R.string.settings_account
        SettingsTarget.Backup -> R.string.settings_recovery_phrase
        SettingsTarget.Addresses -> R.string.addresses_title
        SettingsTarget.Notes -> R.string.settings_notes
        SettingsTarget.NoteList -> R.string.settings_note_list
        SettingsTarget.Mints -> R.string.settings_mints
        SettingsTarget.Delete -> R.string.settings_delete
        SettingsTarget.Display -> R.string.settings_amounts
        SettingsTarget.Service -> R.string.settings_home_mint
        SettingsTarget.Security -> R.string.security_title
        SettingsTarget.About -> R.string.about_title
        SettingsTarget.Developer -> R.string.developer_title
    }
}

/** One hub row: a page, a one-line summary of it (plus an optional status line), and a chevron. */
@Composable
private fun SummaryRow(
    title: String,
    summary: String,
    summaryColor: Color = Color.Unspecified,
    titleColor: Color = Color.Unspecified,
    extra: String? = null,
    extraColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    WalletListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = {
            Column {
                Text(summary, color = summaryColor)
                extra?.let { Text(it, color = extraColor) }
            }
        },
        trailingContent = { ChevronIcon() },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun notesSummary(state: SettingsState): String {
    val strategy = state.noteStrategy ?: return ""
    val title = stringResource(strategy.title)
    val withRotate = if (state.autoRotate && !strategy.rotatesAddressNotes) stringResource(R.string.settings_notes_with_rotate, title) else title
    val home = state.homeHost
    return if (state.collectAtHome && home != null) stringResource(R.string.settings_notes_with_collect, withRotate, home) else withRotate
}

@Composable
private fun addressSummary(status: AddressStatus?): Pair<String?, Color> = when (status) {
    AddressStatus.Ours -> stringResource(R.string.settings_address_active) to MaterialTheme.colorScheme.primary
    AddressStatus.Unconfirmed -> stringResource(R.string.settings_address_unconfirmed) to Color.Unspecified
    AddressStatus.Taken, AddressStatus.Available -> stringResource(R.string.settings_address_inactive) to MaterialTheme.colorScheme.error
    else -> null to Color.Unspecified
}
