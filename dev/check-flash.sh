#!/usr/bin/env bash
# Fails if a screen's state starts with a value that isn't the truth yet and doesn't say so. A view model's
# `stateIn(…, initial)` shows `initial` for a moment before what the store says: unless the screen can tell, it draws
# one thing first and another after (a default mint, an empty list, a switch that is off). The initial value must be
# `null` or carry `loaded = false`, and the screen draws nothing until then (docs/DEVELOPMENT.md, "No flashing
# screens"). An initial value that draws nothing by itself is marked on the line above: `// no-flash: <why>`.
set -euo pipefail
cd "$(dirname "$0")/.."

hits=$(
  find app/src/main feature/*/src/main -name '*.kt' -print0 | sort -z | xargs -0 awk '
    FNR == 1 { before = "" }
    /stateIn\(/ && !/^import / && !/, null\)/ && !/loaded = false\)/ && before !~ /no-flash:/ { print FILENAME ":" FNR ": " $0 }
    { before = $0 }
  '
)
if [[ -n "$hits" ]]; then
  echo "A state that starts with a value the screen would draw before the real one (start with null or loaded = false):"
  echo "$hits" | sed 's/^/  /'
  exit 1
fi
echo "Flash check passed: every screen state says when it isn't loaded yet."
