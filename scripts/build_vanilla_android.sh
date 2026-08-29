#!/usr/bin/env bash
set -euo pipefail
SRC="${1:?usage: build_vanilla_android.sh /path/to/chromium/src [out-dir]}"
OUT_NAME="${2:-NautrixVanilla}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -f "$SRC/DEPS" ]] || { echo "Not a Chromium src checkout: $SRC" >&2; exit 2; }

DEPOT_TOOLS="${DEPOT_TOOLS_DIR:-$SRC/third_party/depot_tools}"
[[ -x "$DEPOT_TOOLS/autoninja" ]] || {
  echo "Pinned depot_tools checkout is unavailable at $DEPOT_TOOLS" >&2
  exit 3
}
export DEPOT_TOOLS_UPDATE=0
export PATH="$DEPOT_TOOLS:$PATH"

cd "$SRC"
mkdir -p "out/$OUT_NAME"
cp "$ROOT/config/vanilla_args.gn" "out/$OUT_NAME/args.gn"
gn gen "out/$OUT_NAME"
autoninja -C "out/$OUT_NAME" chrome_public_apk
