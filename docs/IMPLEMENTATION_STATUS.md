# Implementation status

Status terms are strict:

- **verified**: compiled or exercised by the named validation;
- **static prototype**: source exists and fast checks pass, but it has not compiled inside Chromium;
- **upstream capability found**: the pinned Chromium source contains the relevant API/UI path;
- **pending**: no runtime claim is made.

| Area | Current evidence | Next release gate |
|---|---|---|
| Secure WebView fallback | **verified in GitHub Actions** on `main` at `targetSdk 36` | Smoke test on a physical Android device |
| Chromium Android ARM64 baseline | Bootstrap/build workflow prepared on `integration/chromium`; no successful Chromium APK yet | Sync pinned checkout/NDK and compile `chrome_public_apk` |
| Android extensions | **upstream capability found** for the experimental desktop-Android flag, toolbar, install dialog and core APIs | Compile the flag, install CRX/unpacked MV3 samples, test permissions/update/removal |
| Nautrix theme and tab lifecycle | **static prototype** overlay | Compile after the vanilla Chromium gate; add instrumentation |
| Secure DNS automatic/manual modes | **static prototype** overlay | Compile and test real DoH failures, captive portals and managed policy |
| Network ad/tracker blocking | **static prototype**, Rust unit checks only | Compile the URLLoader throttle and run request-level integration tests |
| Cosmetic/scriptlet filtering | Engine API only | Wire and security-review renderer injection |
| Ordinary HTTP downloads | Planned to retain Chromium DownloadManager | Runtime pause/resume/background tests |
| HLS/DASH/direct media player | **static prototype** Media3 overlay | Compile; test MediaSession, PiP, seeking, rotation and process recreation |
| Smart media cache/offline media | **static prototype** overlay | Compile; validate quota, eviction, DRM and corrupt segments |
| Torrent/magnet | **static prototype**, optional libtorrent build path | Compile separately after Chromium baseline; test fast-resume and FGS limits |
| Install page as app/PWA | Pinned Chromium contains upstream install/WebAPK paths; Nautrix ordinary-page patch is **static only** | Test manifest PWA and non-PWA fallback end to end |
| DRM/access-control bypass | Intentionally unsupported | Remain out of scope |

The previous full-build run did **not** reach GN generation or compilation. It stopped after
dependency sync because the bootstrap treated an optional NDK CMake file as proof of the
toolchain. The current workflow validates Chromium's actual LLVM sysroot contract and builds a
vanilla APK before any Nautrix overlay is applied.
