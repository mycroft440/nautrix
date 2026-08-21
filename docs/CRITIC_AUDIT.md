# Critic audit protocol

The project is reviewed adversarially one subsystem at a time. A subsystem is approved only after: (1) missing dependencies/references are resolved, (2) fast tests/static invariants pass, (3) failure behavior is explicit, and (4) remaining limits are documented rather than presented as finished.

Audit order: repository/build → policies/tabs → DNS → adblock → media/cache → downloads → torrent → extensions/PWA → security/privacy → CI/documentation.

The authoritative current results are updated in each review PR. A full Chromium/NDK runtime approval additionally requires the self-hosted `Chromium Android ARM64` workflow; static approval cannot substitute for that build.


## Current review ledger

| Subsystem | Static critic | Main findings / resolution | Runtime gate |
|---|---|---|---|
| Repository/build foundation | PASS | Missing files/targets filled; verifier + Java policies + script syntax pass | Full Chromium build required |
| Tabs 1h/5d | PASS | Active Chromium downloads and unload/beforeunload state now protect tabs; private metadata not persisted | Device/session-restore test required |
| DNS | PASS | Explicit System/Automatic/Custom modes; no manual-DNS overwrite; real DoH benchmark + hysteresis | Device/network-change test required |
| Adblock network path | PASS (engine) | Normal + keepalive throttles, corrected Chromium destination enums, `$removeparam` rewrites | Rust + Chromium link/run required |
| Adblock cosmetics/scriptlets | FAIL / open | Rust exposes cosmetic resources but early Blink/renderer injection is not wired | Must be implemented before Brave-parity claim |
| Media player | PASS (static) | Background MediaSession, PiP ratio safety, non-compounding gestures | Device codec/PiP tests required |
| Smart cache | PASS (static) | No generic LRU; 5-day per-key access; complete/recent retention; HLS/DASH segment access tracked | Offline-loss test required |
| Adaptive media downloads | PASS (static) | HLS/DASH use permanent Media3 DownloadService/cache, not raw manifest download | Device download/resume test required |
| Torrent | PASS (static) | libtorrent v2.1.1 pinned; queue-safe JNI, private fast-resume, manager UI, file priorities, rate/peer limits, Wi-Fi-only, seeding policy and Android 15+ timeout persistence | NDK/libtorrent + device/background test required |
| Extensions | PASS (static/upstream-gated) | Android toolbar/popup/site access, CWS, `chrome://extensions`, unpacked SAF and essential MV3 APIs verified against pinned Chromium; local CRX sideload remains a separate hardening item | Full Chromium + device extension matrix |
| PWA / install page as app | PASS (static) | Upstream WebAPK/PWA preserved; ordinary-page explicit Install is forced to standalone while explicit shortcut behavior remains upstream | Install/open/remove tests on device |
| Security/privacy | PENDING | sandbox preserved; per-site shields/fingerprinting/url sanitation scope still to audit | Security review |
| CI / reproducible Chromium build | PASS (configuration) | exact Chromium SHA pinned; push/PR preflight + full self-hosted GitHub Actions build; Rust + libtorrent enabled; APK + SHA256 artifact | Runner must execute successfully |
