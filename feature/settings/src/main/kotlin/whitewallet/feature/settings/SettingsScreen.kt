package whitewallet.feature.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import whitewallet.brand.ui.Tone
import whitewallet.core.designsystem.ChevronIcon
import whitewallet.core.designsystem.LocalAmountUnit
import whitewallet.core.designsystem.R as DesignR
import whitewallet.core.designsystem.WalletAccountMark
import whitewallet.core.designsystem.WalletListIcon
import whitewallet.core.designsystem.WalletListItem
import whitewallet.core.designsystem.WalletTopBar
import whitewallet.core.designsystem.pageLoaded
import whitewallet.core.wallet.AddressStatus

@Composable
fun SettingsRoute(
    versionName: String,
    onBack: () -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    brandPages: List<BrandPage> = emptyList(),
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state, versionName, onBack, brandPages, onNavigate)
}

/** A page of the brand's own, listed in Settings above About (see docs/WHITE_LABEL.md, "Design"). Null [icon]: a generic one. */
data class BrandPage(@StringRes val title: Int, val route: String, @DrawableRes val icon: Int? = null)

/** The settings hub: a short list of pages, each with a live one-line summary. */
@Composable
fun SettingsScreen(
    state: SettingsState,
    versionName: String,
    onBack: () -> Unit,
    brandPages: List<BrandPage> = emptyList(),
    onNavigate: (SettingsDestination) -> Unit,
) {
    val open = { target: SettingsTarget -> onNavigate(SettingsDestination.Page(target)) }
    Scaffold(topBar = { WalletTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        if (!pageLoaded(state.loaded, Modifier.padding(padding))) return@Scaffold
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (state.accountName != null && !state.backupVerified) BackupWarning { open(SettingsTarget.Backup) }
            Hub(state, versionName, open, { onNavigate(SettingsDestination.Account(it)) }, brandPages) {
                onNavigate(SettingsDestination.Route(it))
            }
        }
    }
}

@Composable
private fun Hub(
    state: SettingsState,
    versionName: String,
    open: (SettingsTarget) -> Unit,
    openAccount: (accountId: String) -> Unit,
    brandPages: List<BrandPage>,
    openRoute: (String) -> Unit,
) {
    // Only links: the account in use first, straight to its page, then every wallet, then the app-wide pages (Design Guide: multiple
    // wallets).
    state.accountName?.let { name ->
        val (status, color) = addressSummary(state.addressStatus)
        SummaryRow(
            leading = { WalletAccountMark(name, state.accountPlace) },
            title = name,
            summary = state.address ?: stringResource(R.string.settings_account_no_address),
            extra =
                state.lastPolicyRun?.stoppedEarly?.let { stringResource(R.string.settings_policy_stopped, it) }
                    ?: status.takeIf { color == MaterialTheme.colorScheme.error },
            extraColor = MaterialTheme.colorScheme.error,
        ) {
            state.accountId?.let(openAccount)
        }
        SummaryRow(
            pageIcon(SettingsTarget.Wallets),
            stringResource(R.string.settings_wallets),
            walletName(state.walletName, state.walletNumber),
        ) {
            open(SettingsTarget.Wallets)
        }
        HorizontalDivider()
    }
    if (SettingsTarget.Mints !in state.hidden) {
        SummaryRow(
            pageIcon(SettingsTarget.Mints),
            stringResource(R.string.settings_mints),
            pluralStringResource(R.plurals.settings_mints_summary, state.mintCount, state.mintCount, state.trustedCount),
        ) {
            open(SettingsTarget.Mints)
        }
    }
    SummaryRow(
        pageIcon(SettingsTarget.Security),
        stringResource(R.string.security_title),
        stringResource(if (state.appLock) R.string.settings_app_lock_on else R.string.settings_app_lock_off),
    ) {
        open(SettingsTarget.Security)
    }
    if (SettingsTarget.Display !in state.hidden) {
        SummaryRow(pageIcon(SettingsTarget.Display), stringResource(R.string.settings_amounts), unitExample(LocalAmountUnit.current)) {
            open(SettingsTarget.Display)
        }
    }
    if (SettingsTarget.Developer !in state.hidden) {
        SummaryRow(
            pageIcon(SettingsTarget.Developer),
            stringResource(R.string.settings_developer),
            stringResource(if (state.developerMode) R.string.settings_on else R.string.settings_off),
        ) {
            open(SettingsTarget.Developer)
        }
    }
    brandPages.forEach { page ->
        WalletListItem(
            leadingContent = { WalletListIcon(page.icon ?: DesignR.drawable.ic_page) },
            headlineContent = { Text(stringResource(page.title)) },
            trailingContent = { ChevronIcon() },
            modifier = Modifier.clickable { openRoute(page.route) },
        )
    }
    SummaryRow(pageIcon(SettingsTarget.About), stringResource(R.string.about_title), stringResource(R.string.about_version, versionName)) {
        open(SettingsTarget.About)
    }
}

/** Shown above everything while the active wallet's recovery phrase isn't backed up: one level down, it would be missed. */
@Composable
private fun BackupWarning(onClick: () -> Unit) {
    SummaryRow(
        leading = pageIcon(DesignR.drawable.ic_warning, Tone.Danger),
        title = stringResource(R.string.settings_backup_now),
        summary = stringResource(R.string.settings_backup_now_hint),
        titleColor = MaterialTheme.colorScheme.error,
        onClick = onClick,
    )
}

/** The mark of a page, the same in the hub and on the account's page. */
@DrawableRes
private fun iconOf(target: SettingsTarget): Int =
    when (target) {
        SettingsTarget.Wallets -> DesignR.drawable.ic_wallet
        SettingsTarget.Backup -> DesignR.drawable.ic_key
        SettingsTarget.Addresses -> DesignR.drawable.ic_address
        SettingsTarget.Notes -> DesignR.drawable.ic_tune
        SettingsTarget.NoteList -> DesignR.drawable.ic_notes
        SettingsTarget.Mints,
        SettingsTarget.AccountMints -> DesignR.drawable.ic_mint
        SettingsTarget.Scan -> DesignR.drawable.ic_refresh
        SettingsTarget.Delete -> DesignR.drawable.ic_delete
        SettingsTarget.Display -> DesignR.drawable.ic_amounts
        SettingsTarget.Security -> DesignR.drawable.ic_lock
        SettingsTarget.About -> DesignR.drawable.ic_info
        SettingsTarget.Developer,
        SettingsTarget.Keys -> DesignR.drawable.ic_developer
    }

/** A page's icon as a [SummaryRow]'s leading mark. */
internal fun pageIcon(@DrawableRes icon: Int, tone: Tone = Tone.Neutral): @Composable () -> Unit = { WalletListIcon(icon, tone = tone) }

internal fun pageIcon(target: SettingsTarget, tone: Tone = Tone.Neutral) = pageIcon(iconOf(target), tone)

/** One hub row: a leading mark, the page, a one-line summary of it (plus an optional status line), and a chevron. */
@Composable
internal fun SummaryRow(
    leading: @Composable () -> Unit,
    title: String,
    summary: String,
    summaryColor: Color = Color.Unspecified,
    titleColor: Color = Color.Unspecified,
    extra: String? = null,
    extraColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    WalletListItem(
        leadingContent = leading,
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
internal fun notesSummary(state: SettingsState): String {
    val strategy = state.noteStrategy ?: return ""
    val title = stringResource(strategy.title)
    val withRotate =
        if (state.autoRotate && !strategy.rotatesAddressNotes) stringResource(R.string.settings_notes_with_renew, title) else title
    val home = state.homeHost
    return if (state.collectAtHome && home != null) stringResource(R.string.settings_notes_with_collect, withRotate, home) else withRotate
}

@Composable
internal fun addressSummary(status: AddressStatus?): Pair<String?, Color> =
    when (status) {
        AddressStatus.Ours -> stringResource(R.string.settings_address_active) to MaterialTheme.colorScheme.primary
        AddressStatus.Unconfirmed -> stringResource(R.string.settings_address_unconfirmed) to Color.Unspecified
        AddressStatus.Taken,
        AddressStatus.Available -> stringResource(R.string.settings_address_inactive) to MaterialTheme.colorScheme.error
        else -> null to Color.Unspecified
    }
