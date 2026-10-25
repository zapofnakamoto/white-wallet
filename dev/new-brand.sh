#!/usr/bin/env bash
# Creates a complete brand directory, checks it and builds it. See docs/WHITE_LABEL.md, or run with --help.
set -euo pipefail
exec python3 -I "$(dirname "$0")/new_brand.py" "$@"
