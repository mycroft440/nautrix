# Implementation status

Legend: **implemented** = code path exists; **upstream** = intentionally delegated to Chromium; **build-gated** = implemented but requires optional native build input; **pending UI** = engine/API exists but custom Nautrix settings surface is not finished.

| Area | Status | Notes |
|---|---|---|
| Chromium Android ARM64 | implemented / CI-gated | exact Chromium revision pinned; full APK compiles only in GitHub Actions |
| Dark UI default | implemented | writes Chromium theme preference only when unset |
| Android extensions | PASS static / upstream experimental | Chromium toolbar, popup, site access, Web Store, `chrome://extensions`, unpacked SAF flow and core MV3 APIs are capability-gated in CI |
| Tab cleanup 1h/5d | implemented | persistent normal-tab metadata; pinned/media plus native active-download/unload protection; incognito metadata is never persisted |
| Secure DNS modes | implemented / pending UI | System, benchmarked Automatic and secure custom mode; Chromium managed policy is respected |
| Secure DNS automatic benchmark | implemented / pending UI | real DoH wire queries, median/P95/failure scoring and 10% hysteresis |
| Network ad/tracker blocking | implemented / build-gated | URLLoaderThrottle covers normal + keepalive paths; Rust FFI supports blocking and removeparam URL rewrites; cached daily list updater |
| Cosmetic/scriptlet filtering | engine API only | Rust FFI exposes resources; Blink injection is not yet wired |
| Ordinary HTTP downloads | upstream | Chromium DownloadManager owns persistence/pause/resume; tab cleanup queries its real in-progress state |
| HLS/DASH/direct media routing | implemented / pending UI | native Media3 player; HLS/DASH permanent downloads use Media3 DownloadService |
| Background audio / MediaSession | implemented | MediaSessionService |
| Picture-in-Picture | implemented | native PiP helper |
| Smart media cache | implemented | separate persistent cache, per-segment access tracking, explicit retention scoring; permanent offline store is separate and never TTL-evicted |
| Torrent / magnet | implemented / build-gated | libtorrent v2.1.1; magnet/.torrent, private fast-resume, native manager UI, file priorities, rate/connection limits, Wi-Fi-only, optional seeding; Android dataSync timeout saves state for later continuation |
| Install page as app / PWA | implemented / upstream-assisted | Chromium WebAPK/PWA flow retained; explicit Install on an ordinary page is patched to create a standalone app-like launcher entry |
| DRM/access-control bypass | intentionally unsupported | out of scope |
| Full Chromium compile validation | GitHub Actions gate | automatic on relevant branch/PR changes; self-hosted Chromium runner required; APK uploaded as Actions artifact |
