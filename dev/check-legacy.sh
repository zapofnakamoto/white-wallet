#!/usr/bin/env bash
# Keeps legacy 2f17578 removable: fails if production code reaches it from anywhere but the seams listed here (protocol/SPEC.md,
# "Removing legacy 2f17578"). Everything else goes through Wallet.legacy, or lives in a legacy package of its own.
set -euo pipefail
cd "$(dirname "$0")/.."

seams='core/wallet/src/main/kotlin/whitewallet/core/wallet/(AccountKeys|MintCertificates|Payer|Wallet)\.kt|feature/settings/src/main/kotlin/whitewallet/feature/settings/DerivedKeysScreen\.kt'
if hits=$(grep -rnE --include='*.kt' '^import whitewallet\..*\.legacy\b' core/*/src/main feature/*/src/main app/src/main protocol/*/src/main \
  | grep -v '/legacy/' | grep -vE "^($seams):"); then
  echo "Legacy 2f17578 imported outside its seams; go through Wallet.legacy, or add a seam to this script and SPEC.md:"
  echo "$hits" | sed 's/^/  /'
  exit 1
fi
echo "Legacy check passed: legacy 2f17578 is only reached through its seams."
