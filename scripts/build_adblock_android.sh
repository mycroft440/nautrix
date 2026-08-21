#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT must point to the Android NDK}"
command -v cargo >/dev/null || { echo "Rust/cargo is required" >&2; exit 2; }
if ! cargo ndk --version >/dev/null 2>&1; then
  echo "cargo-ndk is required: cargo install cargo-ndk --locked" >&2
  exit 2
fi
cd "$ROOT/native/adblock_ffi"
rustup target add aarch64-linux-android >/dev/null
cargo ndk -t arm64-v8a build --release
LIB="$PWD/target/aarch64-linux-android/release/libnautrix_adblock.a"
[[ -f "$LIB" ]] || { echo "Expected static library not found: $LIB" >&2; exit 3; }
printf '%s\n' "$LIB"
