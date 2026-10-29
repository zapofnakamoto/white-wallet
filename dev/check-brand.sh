#!/usr/bin/env bash
# Fails if a brand's values (name, application ID, default host, colors) appear in source outside the brand
# directory: proof that a fork only has to edit brand/. Also fails on a string override the app no longer has.
# Usage: dev/check-brand.sh [brandDir] (default: brand).
set -euo pipefail
cd "$(dirname "$0")/.."
brand="${1:-brand}"
props="$brand/brand.properties"
prop() { { grep -E "^$1=" "$props" || true; } | head -1 | cut -d= -f2- | sed 's/^ *//; s/ *$//'; }

needles=("$(prop app.name)" "$(prop app.id)")
# The suggested mints. A local regtest mint (dev/regtest) is nobody's brand, and debug builds name it on purpose.
IFS=',' read -ra hosts <<< "$(prop mints.suggestions)"
for host in "${hosts[@]}"; do
  host="$(echo "$host" | sed 's/^ *//; s/ *$//')"
  [[ "${host%%:*}" =~ ^(localhost|127\.0\.0\.1|10\.0\.2\.2)$ ]] || needles+=("$host")
done
# Brand colors, as written in the brand's resources (#RRGGBB); plain black and white are nobody's brand.
while read -r color; do needles+=("$color"); done < <(grep -rhoIE '#[0-9A-Fa-f]{6,8}' "$brand/res" | grep -viE '^#(FF)?(FFFFFF|000000)$' | sort -u)

# Everything that ships in the app, except the brand itself. Tests and docs may name the default brand.
paths=(app/src/main build-logic/src)
# The protocol testkit only holds test vectors and never ships.
for d in core/*/src/main feature/*/src/main protocol/*/src/main; do
  [ -d "$d" ] && [[ "$d" != protocol/lnurlcash-testkit/* ]] && paths+=("$d")
done

found=0
for needle in "${needles[@]}"; do
  [ -n "$needle" ] || continue
  if hits=$(grep -rnIF --exclude-dir=build -- "$needle" "${paths[@]}"); then
    echo "Brand value '$needle' found outside $brand/:"
    echo "$hits" | sed 's/^/  /'
    found=1
  fi
done
if [ "$found" = 1 ]; then
  echo "Move these into $brand/ (brand.properties or res/). See docs/WHITE_LABEL.md." >&2
  exit 1
fi

# The brand's res/ strings override the app's. One whose name the app no longer has (renamed in a White Wallet
# release) is silently unused, and the app's own text shows instead: name it. The brand's own strings live in code/.
names() { grep -rhoE '<(string|plurals) name="[^"]+"' "$@" 2>/dev/null | sed -E 's/.*name="//; s/"$//' | sort -u; }
if overrides=$(names "$brand"/res/values*/strings.xml) && [ -n "$overrides" ]; then
  stale=$(comm -23 <(echo "$overrides") <(names app/src/main/res/values/strings.xml core/*/src/main/res/values/strings.xml feature/*/src/main/res/values/strings.xml))
  if [ -n "$stale" ]; then
    echo "$brand/res overrides strings the app doesn't have (renamed? see CHANGELOG.md):"
    echo "$stale" | sed 's/^/  /'
    exit 1
  fi
fi
echo "No brand values outside $brand/ (${#needles[@]} checked)."
