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

    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    for module in ["media3-exoplayer", "media3-exoplayer-hls", "media3-exoplayer-dash", "media3-ui"]:
        require(module in gradle, f"Media3 module missing: {module}")
    print("Nautrix source contract: OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"Nautrix source contract: FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
