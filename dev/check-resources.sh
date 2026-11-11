#!/usr/bin/env bash
# Fails if two modules define a resource value of the same name (a string, a plural, a colour, …). The app's build merges
# every module's resources into one table by name, without a word: one of the two wins, and the other module's screen
# shows its text (the QR scanner was titled "Scan for notes" in 0.8.0). A name belongs to one module; a text two
# modules share goes in core/designsystem.
set -euo pipefail
cd "$(dirname "$0")/.."

hits=$(
  grep -oH '<\(string\|plurals\|string-array\|color\|dimen\|integer\|bool\) name="[^"]*"' \
    app/src/main/res/values/*.xml core/*/src/main/res/values/*.xml feature/*/src/main/res/values/*.xml 2>/dev/null |
    sed -E 's#^([^/]+/[^/]+)/src/main/res/values/[^:]*:<([a-z-]+) name="([^"]*)"#\2 \3 \1#; s#^app/src/main/res/values/[^:]*:<([a-z-]+) name="([^"]*)"#\1 \2 app#' |
    sort -u |
    awk '{ key = $1 " " $2; modules[key] = modules[key] " " $3; count[key]++ } END { for (k in count) if (count[k] > 1) print k ":" modules[k] }' |
    sort
)
if [[ -n "$hits" ]]; then
  echo "A resource name defined in more than one module (only one of them reaches the app; rename one):"
  echo "$hits" | sed 's/^/  /'
  exit 1
fi
echo "Resource check passed: no resource name is defined in two modules."
