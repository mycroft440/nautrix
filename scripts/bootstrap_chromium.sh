#!/usr/bin/env bash
set -euo pipefail
WORK="${1:?usage: bootstrap_chromium.sh /path/to/work}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVISION="${CHROMIUM_REVISION:-$(tr -d '[:space:]' < "$ROOT/config/chromium_revision.txt")}"
[[ "$REVISION" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid Chromium revision: $REVISION" >&2; exit 2; }

mkdir -p "$WORK"
cd "$WORK"
if [[ ! -d depot_tools/.git ]]; then
  git clone --filter=blob:none https://chromium.googlesource.com/chromium/tools/depot_tools.git
else
  git -C depot_tools fetch --quiet origin
  git -C depot_tools reset --hard origin/main
fi
export PATH="$WORK/depot_tools:$PATH"

if [[ ! -d chromium/src/.git ]]; then
  mkdir -p chromium
  cd chromium
  fetch --nohooks --no-history android
  cd src
else
  cd chromium/src
fi

# Reproducibility: the Nautrix commit pins the exact Chromium source revision.
git fetch --no-tags origin "$REVISION"
git checkout --detach "$REVISION"
gclient sync --revision "src@$REVISION" --delete_unversioned_trees --force

ACTUAL="$(git rev-parse HEAD)"
[[ "$ACTUAL" == "$REVISION" ]] || {
  echo "Chromium revision mismatch: expected $REVISION, got $ACTUAL" >&2
  exit 3
}

# Chromium owns the Android SDK/NDK revision under third_party. The full CI build
# uses that exact NDK for Nautrix Rust/C++ native modules as well.
[[ -f third_party/android_toolchain/ndk/build/cmake/android.toolchain.cmake ]] || {
  echo "Chromium Android NDK was not synced" >&2
  exit 4
}

if [[ "${NAUTRIX_SKIP_BUILD_DEPS:-0}" != "1" ]]; then
  build/install-build-deps.sh --android
fi

printf 'Chromium ready at %s (revision %s)\n' "$PWD" "$ACTUAL"
