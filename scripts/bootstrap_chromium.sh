#!/usr/bin/env bash
set -euo pipefail
WORK="${1:?usage: bootstrap_chromium.sh /path/to/work}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVISION="${CHROMIUM_REVISION:-$(tr -d '[:space:]' < "$ROOT/config/chromium_revision.txt")}"
DEPOT_TOOLS_REVISION="${DEPOT_TOOLS_REVISION:-$(tr -d '[:space:]' < "$ROOT/config/depot_tools_revision.txt")}"
[[ "$REVISION" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid Chromium revision: $REVISION" >&2; exit 2; }
[[ "$DEPOT_TOOLS_REVISION" =~ ^[0-9a-f]{40}$ ]] || {
  echo "Invalid depot_tools revision: $DEPOT_TOOLS_REVISION" >&2
  exit 2
}

mkdir -p "$WORK"
cd "$WORK"
if [[ ! -d depot_tools/.git ]]; then
  mkdir -p depot_tools
  git -C depot_tools init --quiet
  git -C depot_tools remote add origin \
    https://chromium.googlesource.com/chromium/tools/depot_tools.git
fi
git -C depot_tools fetch --depth=1 --no-tags --filter=blob:none \
  origin "$DEPOT_TOOLS_REVISION"
git -C depot_tools checkout --detach FETCH_HEAD
export DEPOT_TOOLS_UPDATE=0
export PATH="$WORK/depot_tools:$PATH"
if [[ -n "${GITHUB_PATH:-}" ]]; then
  printf '%s\n' "$WORK/depot_tools" >> "$GITHUB_PATH"
fi

CHROMIUM_ROOT="$WORK/chromium"
SRC="$CHROMIUM_ROOT/src"
mkdir -p "$CHROMIUM_ROOT"

# Configure Android before the first dependency sync. `fetch android` checks out
# Chromium HEAD and syncs all of its dependencies before the pinned revision is
# selected, which makes CI both slow and non-reproducible.
cd "$CHROMIUM_ROOT"
if [[ ! -f .gclient ]]; then
  gclient config --spec $'solutions = [\n  {\n    "name": "src",\n    "url": "https://chromium.googlesource.com/chromium/src.git",\n    "managed": False,\n    "custom_deps": {},\n    "custom_vars": {},\n  },\n]\ntarget_os = ["android"]\n'
fi
grep -Fq 'target_os = ["android"]' .gclient || {
  echo "Existing $CHROMIUM_ROOT/.gclient is not configured for Android" >&2
  exit 3
}

if [[ ! -d "$SRC/.git" ]]; then
  mkdir -p "$SRC"
  git -C "$SRC" init --quiet
  git -C "$SRC" remote add origin https://chromium.googlesource.com/chromium/src.git
fi

# Reproducibility: the Nautrix commit pins the exact Chromium source revision.
git -C "$SRC" fetch --depth=1 --no-tags --filter=blob:none origin "$REVISION"
git -C "$SRC" checkout --detach FETCH_HEAD
gclient sync --revision "src@$REVISION" --no-history --nohooks \
  --delete_unversioned_trees --force

ACTUAL="$(git -C "$SRC" rev-parse HEAD)"
[[ "$ACTUAL" == "$REVISION" ]] || {
  echo "Chromium revision mismatch: expected $REVISION, got $ACTUAL" >&2
  exit 3
}

# The pinned DEPS file supplies the NDK as a CIPD Android toolchain package.
# Its contract is the LLVM prebuilt/sysroot used by Chromium, not the optional
# CMake helper file that the old bootstrap incorrectly required.
GCLIENT_ARGS="$SRC/build/config/gclient_args.gni"
[[ -f "$GCLIENT_ARGS" ]] && grep -Eq '^checkout_android[[:space:]]*=[[:space:]]*true$' "$GCLIENT_ARGS" || {
  echo "Chromium was synced without checkout_android=true" >&2
  exit 4
}
NDK_ROOT="$SRC/third_party/android_toolchain/ndk"
NDK_PREBUILT="$(find "$NDK_ROOT/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -print -quit 2>/dev/null || true)"
[[ -n "$NDK_PREBUILT" && -d "$NDK_PREBUILT/sysroot/usr/include" ]] || {
  echo "Chromium Android NDK LLVM sysroot was not synced under $NDK_ROOT" >&2
  find "$SRC/third_party/android_toolchain" -maxdepth 4 -type d -print 2>/dev/null | head -80 >&2 || true
  exit 5
}

if [[ "${NAUTRIX_SKIP_BUILD_DEPS:-0}" != "1" ]]; then
  "$SRC/build/install-build-deps.sh" --android
fi
gclient runhooks

printf 'Chromium ready at %s (revision %s, NDK %s)\n' "$SRC" "$ACTUAL" "$NDK_ROOT"
