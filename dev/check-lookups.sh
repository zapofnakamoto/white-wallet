#!/usr/bin/env bash
# Fails if a note is looked up without its mint's signing key checked first. Most answers to a lookup carry no key (a
# note in use, one the mint doesn't know), so a mint whose key doesn't match must not be asked at all: lookups go
# through a KeyCheckedMint (KeyProber, Pinning). A lookup that is checked another way, or at a host with no pinned key,
# is marked on the line above: `// key-checked: <why>`.
set -euo pipefail
cd "$(dirname "$0")/.."

hits=$(
  find core/*/src/main feature/*/src/main app/src/main -name '*.kt' -print0 | sort -z | xargs -0 awk '
    FNR == 1 { before = "" }
    /client\.lookup\(|Requests\.lookup\(/ && !/^import / && before !~ /key-checked:/ { print FILENAME ":" FNR ": " $0 }
    { before = $0 }
  '
)
if [[ -n "$hits" ]]; then
  echo "A lookup at a mint whose signing key wasn't checked first (use a KeyCheckedMint, or say why on the line above with // key-checked:):"
  echo "$hits" | sed 's/^/  /'
  exit 1
fi
echo "Lookup check passed: every note lookup asks a mint whose signing key was checked."
