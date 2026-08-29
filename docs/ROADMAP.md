# Nautrix delivery roadmap

`[x]` means the stated evidence exists. Source-only prototypes are never marked as functioning
browser features.

## Preserved and verified

- [x] Audit the WebView implementation and experimental Chromium branch.
- [x] Preserve the pre-hardening WebView baseline as `webview-v0.4.0` and remote branch
  `archive/webview-v0.4.0`.
- [x] Harden the WebView fallback for Android 16 on `main`.
- [x] Build debug/release WebView APKs and pass Rust, Java, signature and project checks in
  [GitHub Actions run 24](https://github.com/mycroft440/nautrix/actions/runs/33258020942).
- [ ] Install the WebView APK and complete a physical-device smoke test.

## Phase 2 — first upstream Chromium APK

- [x] Create the isolated `integration/chromium` branch from the experimental source.
- [x] Pin both Chromium (`0339af4147b92eefcb59470c383a22eaf41e4767`) and its matching
  `depot_tools` revision (`5cac02a8eccc60a1ba2a0804fc0a598f24f6137a`).
- [x] Select the pinned Chromium revision before the first dependency sync.
- [x] Replace the false CMake-file NDK test with `checkout_android` plus LLVM sysroot validation.
- [x] Add a vanilla GN configuration and build script with no Nautrix overlays or optional modules.
- [ ] Pass the pinned checkout, CIPD/NDK and upstream-capability gates in GitHub Actions.
- [ ] Compile and publish the first ARM64 `chrome_public_apk` artifact.
- [ ] Install it on a physical ARM64 device and smoke-test navigation, tabs, permissions and downloads.

## Phase 3 — Nautrix Chromium shell

- [ ] Apply only branding, package identity, dark theme and core navigation UI.
- [ ] Compile after every narrow overlay group rather than applying the full prototype at once.
- [ ] Add instrumentation for startup, navigation, tabs, crashes and process recreation.

## Phase 4 — Chrome extensions on Android

- [ ] Compile `enable_desktop_android_extensions` on the pinned Chromium revision.
- [ ] Validate Web Store and `chrome://extensions` entry points.
- [ ] Install signed and unpacked MV3 test extensions.
- [ ] Test service workers, permissions, site access, action popup, content scripts, storage,
  declarativeNetRequest, update, disable and uninstall.
- [ ] Define unsupported APIs explicitly; never label desktop compatibility as complete without tests.

## Phase 5 — apps/PWA

- [ ] Preserve Chromium's manifest/WebAPK install flow.
- [ ] Distinguish installable PWA, ordinary-page standalone shortcut and simple home-screen shortcut.
- [ ] Test icons, scope, offline launch, updates, uninstall and fallback behavior.

## Phase 6 — media, downloads and privacy

- [ ] Compile and test the Media3 player before enabling smart cache or permanent downloads.
- [ ] Validate background audio, MediaSession, PiP, HLS, DASH, seeking and recovery.
- [ ] Integrate adblock, secure DNS and tracker protection one subsystem at a time.
- [ ] Retain Chromium DownloadManager and verify pause/resume/background behavior.
- [ ] Integrate torrent/libtorrent only after the ordinary download path is stable.

## Production gates

- [ ] Threat model, privacy review, SBOM and dependency/update policy.
- [ ] Automated Chromium security rebase cadence.
- [ ] Unit, integration, instrumented and real-site regression suites.
- [ ] Device matrix for supported Android versions and ARM64 vendors.
- [ ] Reproducible signing, staged rollout, crash telemetry consent and rollback plan.
