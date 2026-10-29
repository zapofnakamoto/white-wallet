#!/usr/bin/env bash
# Fails if a screen uses one of Material's components directly instead of the designsystem's (WalletButton,
# WalletTextField, WalletListItem, …), which draw through the brand's design (docs/WHITE_LABEL.md, "Design"). Only
# layout, text, icons, menus and tabs, which the theme already styles, may come from Material, and the snackbar's
# state and result types (its messages are drawn by WalletSnackbarHost, through the brand).
set -euo pipefail
cd "$(dirname "$0")/.."

allowed='Text|Icon|IconButton|MaterialTheme|Scaffold|Surface|HorizontalDivider|ExperimentalMaterial3Api|LocalContentColor|LocalTextStyle|DropdownMenu|DropdownMenuItem|ExposedDropdownMenuBox|ExposedDropdownMenuDefaults|ExposedDropdownMenuAnchorType|PrimaryTabRow|Tab|pulltorefresh\.PullToRefreshBox|SnackbarHostState|SnackbarDuration|SnackbarResult'
if hits=$(grep -rnE --include='*.kt' '^import androidx\.compose\.material3\.' feature/*/src/main app/src/main | grep -vE "material3\.($allowed)$"); then
  echo "Material components used directly; use the designsystem's instead (core/designsystem Controls.kt):"
  echo "$hits" | sed 's/^/  /'
  exit 1
fi
echo "Design check passed: screens draw through the brand's components."
