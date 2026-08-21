# Third-party notices

Nautrix is designed to build on Chromium (BSD-style license), AndroidX Media3 (Apache-2.0), Brave's `adblock-rust` (MPL-2.0), and optionally libtorrent (BSD license). Those projects remain governed by their own licenses. Distribution builds must preserve the notices/licenses required by the exact revisions shipped.

The `adblock-rust` dependency is pinned in `native/adblock_ffi/Cargo.toml`. libtorrent is fetched by `scripts/build_libtorrent_android.sh` when explicitly enabled.
