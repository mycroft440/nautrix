#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT must point to the Android NDK}"
SRC="$ROOT/native/libtorrent-src-v2.1.1"
BUILD="$ROOT/native/libtorrent-build/v2.1.1/arm64-v8a"
BOOST_VERSION="1.91.0"
BOOST_DIR="$ROOT/native/boost_1_91_0"
BOOST_ARCHIVE="$ROOT/native/boost_1_91_0.tar.gz"

if [[ ! -d "$SRC/.git" ]]; then
  git clone --depth 1 --branch v2.1.1 https://github.com/arvidn/libtorrent.git "$SRC"
fi

# Pin exact libtorrent/Boost releases: reproducible Android builds must never drift with a moving
# release branch or accidentally discover host headers from the self-hosted runner.
if [[ ! -d "$BOOST_DIR/boost" ]]; then
  if [[ ! -f "$BOOST_ARCHIVE" ]]; then
    curl --fail --location --retry 3 \
      "https://archives.boost.io/release/${BOOST_VERSION}/source/boost_1_91_0.tar.gz" \
      --output "$BOOST_ARCHIVE"
  fi
  tar -xzf "$BOOST_ARCHIVE" -C "$ROOT/native"
fi

cmake -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DBoost_ROOT="$BOOST_DIR" \
  -DBoost_NO_SYSTEM_PATHS=ON \
  -Dbuild_tests=OFF \
  -Dbuild_examples=OFF \
  -Dbuild_tools=OFF \
  -Ddeprecated-functions=OFF \
  -Dexceptions=OFF \
  -Dlogging=OFF \
  -Dwebtorrent=OFF \
  -Di2p=OFF \
  -Dmutable-torrents=OFF \
  -Dextensions=ON \
  -Ddht=ON \
  -Dstreaming=ON \
  -Dencryption=ON \
  -DCMAKE_DISABLE_FIND_PACKAGE_OpenSSL=TRUE \
  -DCMAKE_DISABLE_FIND_PACKAGE_GnuTLS=TRUE \
  -DCMAKE_DISABLE_FIND_PACKAGE_LibGcrypt=TRUE

cmake --build "$BUILD" --parallel
LIB="$(find "$BUILD" -name 'libtorrent-rasterbar.a' -print -quit)"
[[ -n "$LIB" && -f "$LIB" ]] || { echo "libtorrent static library not found" >&2; exit 3; }
printf '%s;%s\n' "$LIB" "$SRC/include"
