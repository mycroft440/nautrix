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

**Chromium bootstrap phase.** The supported WebView fallback remains on `main`; this branch is an
isolated migration workspace. Nautrix overlay prototypes exist for tabs, DNS, adblock, media and
torrents, but none is described as working until it compiles and passes runtime tests inside the
pinned Chromium tree.

The immediate release gate is deliberately smaller: GitHub Actions syncs the exact Chromium and
matching `depot_tools` revisions, verifies the Android LLVM sysroot and upstream capabilities, then
builds an unmodified ARM64 `chrome_public_apk`. Only after that artifact succeeds will overlays be
introduced in narrow, independently testable groups. See `docs/ROADMAP.md` and
`docs/IMPLEMENTATION_STATUS.md` for checked evidence and pending gates.

## Quick policy test

```bash
./scripts/test_policy_core.sh
```

## Bootstrap Chromium

Requires a Linux x86-64 machine with enough disk/RAM for Chromium development.

```bash
./scripts/bootstrap_chromium.sh /path/to/work
./scripts/build_vanilla_android.sh /path/to/work/chromium/src
```

After the vanilla gate succeeds, the experimental overlay build can be attempted separately:

```bash
./scripts/build_android.sh /path/to/work/chromium/src
```

Both paths target `chrome_public_apk`; ARM64 is the current baseline.
