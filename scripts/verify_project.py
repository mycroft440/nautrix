#!/usr/bin/env python3
"""Fast checks that catch missing browser/build integration before the expensive Android build."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

REQUIRED = [
    "settings.gradle",
    "build.gradle",
    "app/build.gradle",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/nautrix/browser/BrowserActivity.java",
    "app/src/main/java/com/nautrix/browser/AdBlockEngine.java",
    "app/src/main/java/com/nautrix/browser/PlaybackStatusPolicy.java",
    "app/src/main/java/com/nautrix/browser/VideoPlayerActivity.java",
    "app/src/main/java/com/nautrix/browser/VideoCache.java",
    "app/src/main/java/com/nautrix/browser/VideoHistory.java",
    "app/src/main/java/com/nautrix/browser/AutoDnsManager.java",
    "app/src/main/java/com/nautrix/browser/LocalHttpProxy.java",
    "app/src/main/java/com/nautrix/browser/InstalledSiteActivity.java",
    "app/src/main/java/com/nautrix/browser/DnsScorePolicy.java",
    "app/src/main/res/drawable/ic_installed_site.xml",
    "native/adblock_android/Cargo.toml",
    "native/adblock_android/src/lib.rs",
    ".github/workflows/android-apks.yml",
]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    missing = [item for item in REQUIRED if not (ROOT / item).is_file()]
    require(not missing, f"missing project files: {missing}")

    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    manifest = manifest_path.read_text(encoding="utf-8")
    ET.parse(manifest_path)
    require("android.permission.INTERNET" in manifest, "INTERNET permission missing")
    require('android:usesCleartextTraffic="false"' in manifest, "cleartext must stay disabled")
    require('android:exported="true"' in manifest, "launcher activity must be exported")

    activity = (ROOT / REQUIRED[4]).read_text(encoding="utf-8")
    for capability in [
        "setDownloadListener",
        "onShowFileChooser",
        "createTab(",
        "showBookmarks(",
        "toggleDesktopMode(",
        "onSafeBrowsingHit",
        "shouldInterceptRequest",
        "openVideoPlayer(",
        "VideoPlayerActivity.createIntent",
        "installCurrentSite(",
        "showCachedVideos(",
        "showAutoDnsPanel(",
    ]:
        require(capability in activity, f"browser capability missing: {capability}")

    blocker = (ROOT / REQUIRED[5]).read_text(encoding="utf-8")
    for capability in ["nativeshouldblock", "nativecosmeticresources", "easylist.txt", "easyprivacy.txt"]:
        require(capability in blocker.lower(), f"adblock capability missing: {capability}")

    cargo = (ROOT / "native/adblock_android/Cargo.toml").read_text(encoding="utf-8")
    require('adblock = { version = "=0.13.3"' in cargo, "adblock-rust version must be pinned")

    player = (ROOT / "app/src/main/java/com/nautrix/browser/VideoPlayerActivity.java").read_text(
        encoding="utf-8"
    )
    for capability in ["ExoPlayer", "STATE_BUFFERING", "PlaybackStatusPolicy", "Cookie"]:
        require(capability in player, f"video player capability missing: {capability}")

    status_policy = (ROOT / "app/src/main/java/com/nautrix/browser/PlaybackStatusPolicy.java").read_text(
        encoding="utf-8"
    )
    require("Servidor do site lento!" in status_policy, "slow-server feedback missing")

    video_cache = (ROOT / "app/src/main/java/com/nautrix/browser/VideoCache.java").read_text(
        encoding="utf-8"
    )
    for capability in ["SimpleCache", "NoOpCacheEvictor", "MIN_RETENTION_MS", "5L * 24L"]:
        require(capability in video_cache, f"video cache capability missing: {capability}")

    auto_dns = (ROOT / "app/src/main/java/com/nautrix/browser/AutoDnsManager.java").read_text(
        encoding="utf-8"
    )
    for capability in ["CANDIDATES", "benchmarkAsync", "resolveAll", "ProxyController", "20 servidores"]:
        require(capability in auto_dns, f"automatic DNS capability missing: {capability}")
    require(auto_dns.count("new Candidate(") >= 20, "automatic DNS needs at least 20 candidates")

    shortcut = (ROOT / "app/src/main/java/com/nautrix/browser/InstalledSiteActivity.java").read_text(
        encoding="utf-8"
    )
    require("BrowserActivity" in shortcut, "installed-site activity missing")

    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    for module in ["media3-exoplayer", "media3-exoplayer-hls", "media3-exoplayer-dash",
                   "media3-datasource-okhttp", "media3-ui", "androidx.webkit"]:
        require(module in gradle, f"Media3 module missing: {module}")
    print("Nautrix source contract: OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"Nautrix source contract: FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
