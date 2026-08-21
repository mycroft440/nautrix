#!/usr/bin/env bash
set -euo pipefail
SRC="${1:?usage: apply_overlays.sh /path/to/chromium/src}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -f "$SRC/DEPS" ]] || { echo "Not a Chromium src checkout: $SRC" >&2; exit 2; }
cp -a "$ROOT/overlays/chromium/." "$SRC/"
python3 "$ROOT/scripts/integrate_chromium.py" "$SRC"
python3 "$ROOT/scripts/verify_repo.py" --chromium-src "$SRC"
