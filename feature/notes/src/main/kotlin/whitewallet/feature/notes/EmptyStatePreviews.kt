package whitewallet.feature.notes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import whitewallet.brand.ui.Anchor
import whitewallet.core.designsystem.CheckAll
import whitewallet.core.designsystem.EmptyState
import whitewallet.core.designsystem.PrimaryButton
import whitewallet.core.designsystem.WalletPreviews

// The shared empty state (docs/DEVELOPMENT.md, "Screenshots"), as the notes view and the archive show it.

/** The notes view without notes: the brand's picture and the way forward. */
@WalletPreviews
@CheckAll
@Composable
private fun EmptyStateWithActionPreview() =
    EmptyState(stringResource(R.string.notes_empty), anchor = Anchor.NotesEmpty) {
        PrimaryButton(stringResource(R.string.notes_receive), {})
    }

/** The archive without notes: only the text. */
@WalletPreviews @Composable private fun EmptyStatePreview() = EmptyState(stringResource(R.string.archive_empty))
