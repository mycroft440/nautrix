#!/usr/bin/env python3
"""Idempotently wires Nautrix overlay sources into a Chromium Android checkout.

Only narrow upstream integration points are touched. Missing anchors fail loudly so an upstream
Chromium change cannot silently disable a Nautrix feature.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

NAUTRIX_JAVA_TARGET = (
    "//chrome/android/java/src/org/chromium/chrome/browser/nautrix:nautrix_integration_java"
)


def insert_once(text: str, anchor: str, insertion: str, *, after: bool = True) -> str:
    if insertion.strip() in text:
        return text
    idx = text.find(anchor)
    if idx < 0:
        raise RuntimeError(f"Chromium integration anchor not found: {anchor!r}")
    pos = idx + len(anchor) if after else idx
    return text[:pos] + insertion + text[pos:]


def find_target_block(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"GN target not found: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise RuntimeError(f"Opening brace not found for: {signature}")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1
    raise RuntimeError(f"Unclosed GN target: {signature}")


def add_gn_dep(src: Path, signature: str, dep: str) -> None:
    text = src.read_text()
    start, end = find_target_block(text, signature)
    block = text[start:end]
    if f'"{dep}"' in block:
        return
    marker = "  deps = ["
    p = block.find(marker)
    if p < 0:
        brace = block.find("{")
        block = block[: brace + 1] + f'\n  deps = [ "{dep}" ]' + block[brace + 1 :]
    else:
        insert_at = p + len(marker)
        block = block[:insert_at] + f'\n    "{dep}",' + block[insert_at:]
    src.write_text(text[:start] + block + text[end:])


def add_conditional_dep_to_target_containing_source(
    src: Path, source_literal: str, dep: str
) -> None:
    text = src.read_text()
    source_pos = text.find(source_literal)
    if source_pos < 0:
        raise RuntimeError(f"GN source not found: {source_literal}")
    target_re = re.compile(r'(?m)^[a-zA-Z_][a-zA-Z0-9_]*\("[^"\n]+"\)\s*\{')
    enclosing: tuple[int, int] | None = None
    for match in target_re.finditer(text):
        signature = match.group(0)[:-1].rstrip()
        try:
            start, end = find_target_block(text, signature)
        except RuntimeError:
            continue
        if start <= source_pos < end:
            enclosing = (start, end)
            break
    if enclosing is None:
        raise RuntimeError(f"Could not find GN target containing {source_literal}")
    start, end = enclosing
    block = text[start:end]
    if f'"{dep}"' in block:
        return
    insertion = f'\n  if (is_android) {{\n    deps += [ "{dep}" ]\n  }}\n'
    close = block.rfind("}")
    block = block[:close] + insertion + block[close:]
    src.write_text(text[:start] + block + text[end:])


def patch_chrome_application(src: Path) -> None:
    text = src.read_text()
    text = insert_once(
        text,
        "import org.chromium.chrome.browser.night_mode.SystemNightModeMonitor;",
        "\nimport org.chromium.chrome.browser.nautrix.runtime.NautrixThemeBootstrap;",
    )
    text = insert_once(
        text,
        "        assert SplitCompatApplication.isBrowserProcess();",
        "\n\n        // Nautrix defaults to dark exactly once; later user choices are preserved.\n"
        "        NautrixThemeBootstrap.applyDarkDefault(getApplication());",
    )
    src.write_text(text)


def patch_tabbed_activity(src: Path) -> None:
    text = src.read_text()
    text = insert_once(
        text,
        "import org.chromium.chrome.browser.navigation_predictor.NavigationPredictorBridge;",
        "\nimport org.chromium.chrome.browser.nautrix.runtime.NautrixRuntime;",
    )
    anchor = "        mTabModelSelector = assertNonNull(mTabModelOrchestrator.getTabModelSelector());"
    text = insert_once(
        text,
        anchor,
        "\n        // Attach after the selector exists; runtime waits for restored tab state.\n"
        "        NautrixRuntime.attach(mTabModelSelector, getLifecycleDispatcher());",
    )
    src.write_text(text)


def patch_content_browser_client(src: Path) -> None:
    text = src.read_text()
    include = (
        "#if BUILDFLAG(IS_ANDROID)\n"
        "#include \"chrome/browser/nautrix/adblock/nautrix_adblock_throttle.h\"\n"
        "#endif\n"
    )
    if "chrome/browser/nautrix/adblock/nautrix_adblock_throttle.h" not in text:
        anchor = '#include "chrome/browser/net/chrome_network_delegate.h"\n'
        if anchor not in text:
            raise RuntimeError("ChromeContentBrowserClient include anchor changed")
        text = text.replace(anchor, anchor + include, 1)

    method = "ChromeContentBrowserClient::CreateURLLoaderThrottles("
    method_pos = text.find(method)
    if method_pos < 0:
        raise RuntimeError("CreateURLLoaderThrottles anchor changed")
    vector = "  std::vector<std::unique_ptr<blink::URLLoaderThrottle>> result;\n"
    vector_pos = text.find(vector, method_pos)
    if vector_pos < 0:
        raise RuntimeError("CreateURLLoaderThrottles result anchor changed")
    insertion = (
        "#if BUILDFLAG(IS_ANDROID)\n"
        "  // Native blocking happens before a blocked response body is downloaded.\n"
        "  result.push_back(std::make_unique<nautrix::NautrixAdBlockThrottle>(request));\n"
        "#endif\n"
    )
    nearby = text[vector_pos : vector_pos + 700]
    if "NautrixAdBlockThrottle" not in nearby:
        at = vector_pos + len(vector)
        text = text[:at] + insertion + text[at:]
    src.write_text(text)


def patch_privacy_build(src: Path) -> None:
    text = src.read_text()
    start, end = find_target_block(text, 'android_library("java")')
    block = text[start:end]
    source = (
        '      "java/src/org/chromium/chrome/browser/privacy/secure_dns/'
        'NautrixSecureDnsApi.java",'
    )
    if source not in block:
        anchor = (
            '      "java/src/org/chromium/chrome/browser/privacy/secure_dns/'
            'SecureDnsBridge.java",'
        )
        if anchor not in block:
            raise RuntimeError("SecureDnsBridge source anchor changed")
        block = block.replace(anchor, anchor + "\n" + source, 1)
    visibility = '      "//chrome/android/java/src/org/chromium/chrome/browser/nautrix:*",'
    if visibility not in block:
        marker = '      "//chrome/android:*",'
        if marker not in block:
            raise RuntimeError("privacy java visibility anchor changed")
        block = block.replace(marker, marker + "\n" + visibility, 1)
    src.write_text(text[:start] + block + text[end:])


def patch_manifest(src: Path) -> None:
    text = src.read_text()
    components = """
        <!-- Nautrix Media3 player: background audio + MediaSession + PiP. -->
        <activity android:name="org.chromium.chrome.browser.nautrix.media.NautrixPlayerActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Material.NoActionBar"
            android:hardwareAccelerated="true"
            android:resizeableActivity="true"
            android:supportsPictureInPicture="true"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|uiMode" />
        <activity android:name="org.chromium.chrome.browser.nautrix.torrent.NautrixTorrentEntryActivity"
            android:exported="true"
            android:theme="@android:style/Theme.NoDisplay">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="magnet" />
            </intent-filter>
        </activity>
        <service android:name="org.chromium.chrome.browser.nautrix.torrent.NautrixTorrentService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
        <service android:name="org.chromium.chrome.browser.nautrix.media.NautrixPlayerService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>

"""
    if "org.chromium.chrome.browser.nautrix.media.NautrixPlayerActivity" not in text:
        anchor = "        {% block extra_application_definitions %}"
        text = insert_once(text, anchor, components, after=False)
    src.write_text(text)


def integrate(root: Path) -> None:
    required = [
        root / "chrome/android/BUILD.gn",
        root / "chrome/android/java/AndroidManifest.xml",
        root / "chrome/android/java/src/org/chromium/chrome/browser/ChromeApplicationImpl.java",
        root / "chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java",
        root / "chrome/browser/BUILD.gn",
        root / "chrome/browser/chrome_content_browser_client.cc",
        root / "chrome/browser/privacy/BUILD.gn",
    ]
    for path in required:
        if not path.exists():
            raise RuntimeError(f"Required Chromium file missing: {path}")

    add_gn_dep(
        root / "chrome/android/BUILD.gn", 'android_library("chrome_java")', NAUTRIX_JAVA_TARGET
    )
    add_conditional_dep_to_target_containing_source(
        root / "chrome/browser/BUILD.gn",
        '"chrome_content_browser_client.cc"',
        "//chrome/browser/nautrix:adblock_integration",
    )
    add_conditional_dep_to_target_containing_source(
        root / "chrome/browser/BUILD.gn",
        '"chrome_content_browser_client.cc"',
        "//chrome/browser/nautrix:torrent_integration",
    )
    patch_content_browser_client(root / "chrome/browser/chrome_content_browser_client.cc")
    patch_privacy_build(root / "chrome/browser/privacy/BUILD.gn")
    patch_chrome_application(
        root / "chrome/android/java/src/org/chromium/chrome/browser/ChromeApplicationImpl.java"
    )
    patch_tabbed_activity(
        root / "chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java"
    )
    patch_manifest(root / "chrome/android/java/AndroidManifest.xml")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("chromium_src", type=Path)
    args = parser.parse_args()
    root = args.chromium_src.resolve()
    if not (root / "DEPS").exists():
        raise SystemExit(f"Not a Chromium src checkout: {root}")
    integrate(root)
    print(f"Integrated Nautrix into {root}")


if __name__ == "__main__":
    main()
