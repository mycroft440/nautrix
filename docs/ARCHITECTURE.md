# Nautrix architecture

Nautrix is an overlay-based Chromium Android browser. Chromium remains upstream; this repository owns only the code, GN targets and narrow integration edits that distinguish Nautrix.

## Layers

1. **Upstream Chromium** — renderer, network service, sandbox, tabs, ordinary HTTP downloads, PWA/web-app support and the experimental Android extensions stack.
2. **Nautrix Java integration** — tab lifecycle, DNS benchmarker, media player/cache, protocol download router and torrent foreground service.
3. **Nautrix native integration** — pre-download URLLoaderThrottle using `adblock-rust`; optional libtorrent JNI engine.
4. **Policy core** — dependency-free Java decisions with fast tests, so retention/routing behavior can be validated without a multi-hour Chromium build.

## Update strategy

`overlays/chromium/` is copied into a fresh Chromium checkout. `scripts/integrate_chromium.py` changes only explicit anchors and fails if an upstream refactor removes an anchor. This is deliberate: silently losing adblocking, tab cleanup or manifest registration is worse than a failed rebase.

## Security boundaries

Nautrix keeps Chromium's multiprocess sandbox and does not disable site isolation. Native media/download features do not bypass DRM, authentication or access controls. Incognito media is not handed to the persistent smart-cache player. Filter and DNS updates use HTTPS with bounded timeouts/response sizes.

## Heavy modules

`adblock-rust` is built as an ARM64 static library. libtorrent is optional (`NAUTRIX_ENABLE_TORRENT=1`) because it materially increases build/install size. The Java torrent service gracefully reports an unavailable native module rather than crashing a build where libtorrent is disabled.
