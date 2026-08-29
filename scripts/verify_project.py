#!/usr/bin/env python3
"""Fast checks that catch missing browser/build integration before the expensive Android build."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "app/src/main/java/com/nautrix/browser"

REQUIRED = [
    "settings.gradle",
    "build.gradle",
    "app/build.gradle",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/drawable/ic_installed_site.xml",
    "app/src/main/res/xml/file_paths.xml",
    "native/adblock_android/Cargo.toml",
    "native/adblock_android/src/lib.rs",
    ".github/workflows/android-apks.yml",
]

SOURCE_CLASSES = [
    "BrowserActivity",
    "AdBlockEngine",
    "PlaybackStatusPolicy",
    "VideoPlayerActivity",
    "VideoCache",
    "VideoHistory",
    "AutoDnsManager",
    "NavigationSecurityPolicy",
    "NotificationPermissionHelper",
    "InstalledSiteActivity",
    "DownloadRegistry",
    "DownloadManagerActivity",
    "TorrentService",
    "DnsScorePolicy",
]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def source_path(class_name: str) -> Path:
    """Return Kotlin first, then Java, so migrations can happen class-by-class."""
    for suffix in (".kt", ".java"):
        candidate = SOURCE_DIR / f"{class_name}{suffix}"
        if candidate.is_file():
            return candidate
    raise AssertionError(f"missing Android source: {class_name}.kt/.java")


def read_source(class_name: str) -> str:
    return source_path(class_name).read_text(encoding="utf-8")


def main() -> int:
    missing = [item for item in REQUIRED if not (ROOT / item).is_file()]
    require(not missing, f"missing project files: {missing}")
    for class_name in SOURCE_CLASSES:
        source_path(class_name)

    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    manifest = manifest_path.read_text(encoding="utf-8")
    ET.parse(manifest_path)
    require("android.permission.INTERNET" in manifest, "INTERNET permission missing")
    require('android:usesCleartextTraffic="false"' in manifest, "cleartext must stay disabled")
    require('android:exported="true"' in manifest, "launcher activity must be exported")
    require("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" not in manifest,
            "first-run battery exemption permission must stay removed")

    activity = read_source("BrowserActivity")
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
        "NavigationSecurityPolicy.mayLaunchExternal",
        "PerformanceSetupActivity::class.java",
        "showMediaDownloadPicker(",
        "openDownloadManager(",
        "confirmMagnet(",
    ]:
        require(capability in activity, f"browser capability missing: {capability}")

    blocker = read_source("AdBlockEngine")
    for capability in ["nativeshouldblock", "nativecosmeticresources", "easylist.txt", "easyprivacy.txt"]:
        require(capability in blocker.lower(), f"adblock capability missing: {capability}")

    cargo = (ROOT / "native/adblock_android/Cargo.toml").read_text(encoding="utf-8")
    require('adblock = { version = "=0.13.3"' in cargo, "adblock-rust version must be pinned")

    player = read_source("VideoPlayerActivity")
    for capability in ["ExoPlayer", "STATE_BUFFERING", "PlaybackStatusPolicy",
                       "addNetworkInterceptor", "sameHttpsOrigin"]:
        require(capability in player, f"video player capability missing: {capability}")

    status_policy = read_source("PlaybackStatusPolicy")
    require("Servidor do site lento!" in status_policy, "slow-server feedback missing")

    video_cache = read_source("VideoCache")
    for capability in ["SimpleCache", "NoOpCacheEvictor", "MIN_RETENTION_MS", "5L * 24L"]:
        require(capability in video_cache, f"video cache capability missing: {capability}")

    auto_dns = read_source("AutoDnsManager")
    for capability in ["resolveAll", "InetAddress.getAllByName", "clearProxyOverride", "DNS privado"]:
        require(capability in auto_dns, f"safe Android DNS capability missing: {capability}")
    require("DatagramSocket" not in auto_dns, "unencrypted UDP DNS must stay disabled")
    require("addProxyRule" not in auto_dns, "the WebView must not use the legacy DNS proxy")

    download_manager = read_source("DownloadManagerActivity")
    for capability in ["DownloadRegistry", "TorrentService", "Adicionar magnet", "Abrir .torrent"]:
        require(capability in download_manager, f"download manager capability missing: {capability}")

    torrent_service = read_source("TorrentService")
    for capability in ["SessionManager", "TorrentInfo", "addMagnet", "handle.pause()",
                       "setInstanceFollowRedirects(false)", "sameHttpsOrigin"]:
        require(capability in torrent_service, f"torrent capability missing: {capability}")

    shortcut = read_source("InstalledSiteActivity")
    require("BrowserActivity" in shortcut, "installed-site activity missing")

    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    require("org.jetbrains.kotlin.android" in gradle, "Kotlin Android plugin missing")
    require("targetSdk 36" in gradle, "targetSdk 36 required")
    for module in ["media3-exoplayer", "media3-exoplayer-hls", "media3-exoplayer-dash",
                   "media3-datasource-okhttp", "media3-ui", "androidx.webkit", "jlibtorrent"]:
        require(module in gradle, f"Media3 module missing: {module}")
    print("Nautrix source contract: OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"Nautrix source contract: FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
