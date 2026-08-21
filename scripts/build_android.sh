#!/usr/bin/env bash
set -euo pipefail
SRC="${1:?usage: build_android.sh /path/to/chromium/src [out-dir]}"
OUT_NAME="${2:-Nautrix}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -f "$SRC/DEPS" ]] || { echo "Not a Chromium src checkout: $SRC" >&2; exit 2; }
"$ROOT/scripts/apply_overlays.sh" "$SRC"
ARGS="$(cat "$ROOT/config/args.gn")"
if [[ -n "${ANDROID_NDK_ROOT:-}" ]] && command -v cargo >/dev/null 2>&1; then
  ADBLOCK_LIB="$("$ROOT/scripts/build_adblock_android.sh")"
  ARGS+=$'\nnautrix_adblock_lib_path = "'"$ADBLOCK_LIB"'"'
else
  echo "Warning: Rust adblock library not built; compiling C++ integration in stub mode." >&2
  ARGS+=$'\nnautrix_enable_adblock_rust = false'
fi
if [[ "${NAUTRIX_ENABLE_TORRENT:-0}" == "1" && -n "${ANDROID_NDK_ROOT:-}" ]]; then
  IFS=';' read -r TORRENT_LIB TORRENT_INCLUDE <<< "$("$ROOT/scripts/build_libtorrent_android.sh")"
  ARGS+=$'\nnautrix_enable_torrent = true'
  ARGS+=$'\nnautrix_libtorrent_lib_path = "'"$TORRENT_LIB"'"'
  ARGS+=$'\nnautrix_libtorrent_include_dir = "'"$TORRENT_INCLUDE"'"'
fi
cd "$SRC"
mkdir -p "out/$OUT_NAME"
printf '%s\n' "$ARGS" > "out/$OUT_NAME/args.gn"
gn gen "out/$OUT_NAME"
autoninja -C "out/$OUT_NAME" chrome_public_apk
