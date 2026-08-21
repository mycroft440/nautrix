#!/usr/bin/env python3
"""Fail fast when Nautrix documentation/integration references files that do not exist."""
from __future__ import annotations
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    "README.md", "config/args.gn", "config/chromium_revision.txt", "native/adblock_ffi/Cargo.toml",
    "native/adblock_ffi/src/lib.rs", "scripts/bootstrap_chromium.sh",
    "scripts/apply_overlays.sh", "scripts/build_android.sh", "scripts/integrate_chromium.py",
    "scripts/test_policy_core.sh", "docs/ARCHITECTURE.md", "docs/IMPLEMENTATION_STATUS.md",
    "overlays/chromium/chrome/android/java/src/org/chromium/chrome/browser/nautrix/BUILD.gn",
    "overlays/chromium/chrome/browser/nautrix/BUILD.gn",
    "overlays/chromium/chrome/browser/nautrix/adblock/nautrix_adblock_throttle.cc",
    "overlays/chromium/chrome/browser/nautrix/torrent/nautrix_torrent_jni.cc",
    "overlays/chromium/chrome/android/java/src/org/chromium/chrome/browser/nautrix/torrent/NautrixTorrentManagerActivity.java",
    "overlays/chromium/chrome/browser/privacy/java/src/org/chromium/chrome/browser/privacy/secure_dns/NautrixSecureDnsApi.java",
]


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def verify_repo() -> None:
    missing = [p for p in REQUIRED if not (ROOT / p).is_file()]
    if missing: fail("missing required files: " + ", ".join(missing))
    java_build = ROOT / "overlays/chromium/chrome/android/java/src/org/chromium/chrome/browser/nautrix/BUILD.gn"
    text = java_build.read_text()
    for rel in re.findall(r'^\s*"([^"\n]+\.java)",?$', text, re.M):
        if not (java_build.parent / rel).is_file(): fail(f"BUILD.gn references missing Java source: {rel}")
    cpp_build = ROOT / "overlays/chromium/chrome/browser/nautrix/BUILD.gn"
    text = cpp_build.read_text()
    for rel in re.findall(r'^\s*"([^"\n]+\.(?:cc|h))",?$', text, re.M):
        if not (cpp_build.parent / rel).is_file(): fail(f"BUILD.gn references missing C++ source: {rel}")
    args = (ROOT / "config/args.gn").read_text()
    if "enable_desktop_android_extensions = true" not in args:
        fail("Android extensions flag is not enabled in config/args.gn")
    revision = (ROOT / "config/chromium_revision.txt").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        fail("config/chromium_revision.txt must contain one exact 40-char Chromium commit SHA")
    manifest_integrator = (ROOT / "scripts/integrate_chromium.py").read_text()
    for cls in [
        "NautrixPlayerService",
        "NautrixPlayerActivity",
        "NautrixTorrentService",
        "NautrixTorrentEntryActivity",
        "NautrixTorrentManagerActivity",
    ]:
        if cls not in manifest_integrator:
            fail(f"integrator does not wire manifest component {cls}")

    torrent_java = (
        ROOT
        / "overlays/chromium/chrome/android/java/src/org/chromium/chrome/browser/nautrix/torrent/NautrixTorrentService.java"
    ).read_text()
    torrent_cpp = (ROOT / "overlays/chromium/chrome/browser/nautrix/torrent/nautrix_torrent_jni.cc").read_text()
    native_methods = set(re.findall(r"private static native \w+ (native\w+)\(", torrent_java))
    native_methods |= set(
        re.findall(r"private static native \w+\s+(native\w+)\s*\(", torrent_java, re.M)
    )
    jni_methods = set(
        re.findall(
            r"Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_(native\w+)",
            torrent_cpp,
        )
    )
    if native_methods != jni_methods:
        fail(
            "torrent Java/JNI method mismatch: "
            f"Java-only={sorted(native_methods - jni_methods)}, "
            f"C++-only={sorted(jni_methods - native_methods)}"
        )
    banned_torrent_apis = ["set_sequential_download(", "set_download_rate_limit(", "set_upload_rate_limit("]
    for api in banned_torrent_apis:
        if api in torrent_cpp:
            fail(f"torrent JNI uses deprecated libtorrent API: {api}")
    if "torrent_flags::paused" not in torrent_cpp or "torrent_flags::auto_managed" not in torrent_cpp:
        fail("torrent JNI must preserve libtorrent queue-managed paused/auto_managed semantics")
    if "onTimeout(int startId, int fgsType)" not in torrent_java:
        fail("torrent dataSync service does not handle Android 15+ FGS timeout")
    rust = (ROOT / "native/adblock_ffi/src/lib.rs").read_text()
    for symbol in ["nautrix_adblock_create", "nautrix_adblock_replace_rules", "nautrix_adblock_should_block"]:
        if symbol not in rust: fail(f"Rust FFI symbol missing: {symbol}")


def verify_chromium(src: Path) -> None:
    if not (src / "DEPS").is_file(): fail(f"not a Chromium src checkout: {src}")
    expected = {
        "chrome/android/java/src/org/chromium/chrome/browser/nautrix/BUILD.gn": "nautrix_integration_java",
        "chrome/browser/nautrix/BUILD.gn": "adblock_integration",
        "chrome/browser/chrome_content_browser_client.cc": "NautrixAdBlockThrottle",
        "chrome/android/java/AndroidManifest.xml": "NautrixPlayerService",
        "chrome/browser/privacy/BUILD.gn": "NautrixSecureDnsApi.java",
        "components/webapps/browser/android/add_to_homescreen_data_fetcher.cc":
            "Nautrix: an explicit Install request",
    }
    for rel, marker in expected.items():
        path = src / rel
        if not path.is_file() or marker not in path.read_text(errors="replace"):
            fail(f"Chromium integration missing marker {marker!r} in {rel}")

    pinned = (ROOT / "config/chromium_revision.txt").read_text().strip()
    head_file = src / ".git"
    # Worktrees may use a .git file; ask git itself when available rather than parsing it.
    import subprocess
    actual = subprocess.check_output(["git", "-C", str(src), "rev-parse", "HEAD"], text=True).strip()
    if actual != pinned:
        fail(f"Chromium checkout drifted: expected {pinned}, got {actual}")

    # Extension capability contract. These are intentionally upstream-owned; if Chromium's
    # experimental Android port regresses, CI must fail rather than silently shipping less.
    upstream_capabilities = {
        "extensions/buildflags/buildflags.gni": [
            "enable_desktop_android_extensions",
            "enable_extensions_core",
        ],
        "chrome/browser/ui/android/toolbar/java/src/org/chromium/chrome/browser/toolbar/extensions/ExtensionsMenuMediator.java": [
            "CHROME_WEBSTORE_URL",
            "CHROME_EXTENSIONS_URL",
        ],
        "chrome/browser/ui/android/toolbar/java/src/org/chromium/chrome/browser/toolbar/extensions/ExtensionActionPopup.java": [
            "ExtensionActionPopupContents",
            "ThinWebView",
        ],
        "chrome/browser/ui/android/extensions/extension_install_dialog_view_android.cc": [
            "ShowExtensionInstallDialogAndroid",
            "GetPermissions",
        ],
        "chrome/browser/extensions/api/developer_private/developer_private_functions.cc": [
            "DeveloperPrivateLoadUnpackedFunction",
            "ResolveToVirtualDocumentPath",
        ],
        "extensions/browser/api/BUILD.gn": [
            "declarative_net_request",
            "management",
            "runtime",
            "scripting",
            "storage",
            "webstore_private",
        ],
        "chrome/browser/extensions/api/BUILD.gn": [
            "context_menus",
            "cookies",
            "extension_action",
            "history",
            "notifications",
            "permissions",
            "web_navigation",
        ],
        "chrome/android/java/src/org/chromium/chrome/browser/webapps/AppInstallMenuHandler.java": [
            "doUniversalInstall",
            "AddToHomescreenCoordinator",
        ],
        "chrome/browser/android/shortcut_helper.cc": [
            "AddWebappWithSkBitmap",
            "DisplayMode::kStandalone",
        ],
    }
    for rel, markers in upstream_capabilities.items():
        path = src / rel
        if not path.is_file():
            fail(f"required upstream capability file missing: {rel}")
        body = path.read_text(errors="replace")
        for marker in markers:
            if marker not in body:
                fail(f"upstream capability marker {marker!r} missing in {rel}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chromium-src", type=Path)
    args = parser.parse_args()
    verify_repo()
    if args.chromium_src: verify_chromium(args.chromium_src.resolve())
    print("Nautrix repository verification: PASS")

if __name__ == "__main__": main()
