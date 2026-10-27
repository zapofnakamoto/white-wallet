#!/usr/bin/env bash
# Prepares the LNURLcash conformance suite in .conformance/ (git-ignored) at the commit pinned in protocol/SPEC.md:
# clones it, checks the commit out and runs npm ci, each only when needed. The mock-mint tests run against it; the
# build finds it there (build-logic, whitewallet.jvm-library). Needs git, node and npm; the network only when the
# pinned commit or its packages aren't there yet. `just test` and CI run this.
set -euo pipefail
cd "$(dirname "$0")/.."

repo=https://github.com/lnurlcash/lnurlcash-conformance
dir=.conformance
pin="$(grep -F '[lnurlcash-conformance]' protocol/SPEC.md | grep -oE '\b[0-9a-f]{40}\b' | head -1)"
[[ -n "$pin" ]] || { echo "No lnurlcash-conformance commit in protocol/SPEC.md." >&2; exit 1; }

[[ -d "$dir/.git" ]] || git clone --quiet "$repo" "$dir"
if [[ "$(git -C "$dir" rev-parse HEAD)" != "$pin" ]]; then
  git -C "$dir" cat-file -e "$pin^{commit}" 2>/dev/null || git -C "$dir" fetch --quiet origin
  git -C "$dir" checkout --quiet --detach "$pin"
fi
# npm ci again whenever the commit's lockfile differs from the one the installed packages came from.
if ! cmp -s "$dir/package-lock.json" "$dir/node_modules/.package-lock.prepared" 2>/dev/null; then
  (cd "$dir" && npm ci --silent --no-audit --no-fund)
  cp "$dir/package-lock.json" "$dir/node_modules/.package-lock.prepared"
fi
