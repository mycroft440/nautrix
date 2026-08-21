# Nautrix Browser

Nautrix is an Android-first Chromium browser project focused on a small base install, native privacy, extensions, advanced downloads and media playback.

## Product principles

1. **Chromium upstream first** — keep custom code isolated so security updates can be rebased quickly.
2. **Small by default** — ARM64 first; optional heavyweight features are modular.
3. **Dark by default** — browser chrome ships in dark mode by default.
4. **Privacy in the network path** — native ad/tracker filtering and secure DNS, not only extensions.
5. **Media that survives connectivity changes** — persistent smart cache for already-received, non-DRM media.
6. **No bypass of DRM or access controls** — media features operate on content the browser is allowed to receive/store.

## Requested feature set

- Chromium Android, ARM64 first.
- Manifest V3 extension support using Chromium's experimental Extensions Core / desktop-Android work.
- Dark theme by default.
- Native ad/tracker blocker built around `brave/adblock-rust`, integrated before network bodies are downloaded.
- Secure DNS (DoH), manual provider selection, plus automatic benchmarking by real DNS resolution latency/stability.
- Download manager with pause/resume/background operation.
- Torrent/magnet support as an optional libtorrent module with a foreground transfer service.
- Media3/ExoPlayer-based native media player with background audio, MediaSession and Picture-in-Picture.
- Persistent smart media cache: already-received media remains playable offline for 5 days after last access, subject to storage/DRM constraints.
- Install pages as apps/PWAs.
- Automatic tab lifecycle:
  - never-used background tabs: close after 1 hour;
  - used tabs: close after 5 days of inactivity;
  - pinned, active-media, active-download and dirty-form tabs are protected.

## Repository model

This repository is intentionally **not a copy of the whole Chromium source tree**. Chromium is huge and changes constantly. Nautrix keeps:

- `overlays/chromium/` — Nautrix-owned source files copied into a Chromium checkout;
- `patches/` — narrow upstream patches when overlay-only integration is impossible;
- `config/` — GN build args;
- `scripts/` — bootstrap/apply/build helpers;
- `docs/` — architecture and implementation status;
- `tests/` — fast tests for Nautrix policy code that do not require a full Chromium build.

## Current state

**Active integration / audit phase.** Nautrix now contains the Chromium integration script, Android runtime, 1h/5d tab lifecycle, DNS benchmark engine, Rust adblock FFI + browser throttle, Media3 player/background session/PiP, Smart Cache, permanent adaptive-media downloads, and optional libtorrent v2.1.1 torrent engine with fast-resume and native manager UI.

Fast policy and repository checks run without a Chromium checkout. A **full Chromium ARM64 build is the release gate** and compilation is performed only by GitHub Actions. The workflow uses the exact Chromium commit pinned in `config/chromium_revision.txt`, builds Rust adblock + libtorrent with Chromium's synced NDK, compiles `chrome_public_apk`, and uploads the APK plus SHA-256 as an Actions artifact. Cosmetic/scriptlet ad filtering and privacy hardening remain under critic review.

## Quick policy test

```bash
./scripts/test_policy_core.sh
```

## Bootstrap Chromium

Requires a Linux x86-64 machine with enough disk/RAM for Chromium development.

```bash
./scripts/bootstrap_chromium.sh /path/to/work
./scripts/apply_overlays.sh /path/to/work/chromium/src
./scripts/build_android.sh /path/to/work/chromium/src
```

The default build target is `chrome_public_apk` and the default Nautrix target CPU is ARM64.
