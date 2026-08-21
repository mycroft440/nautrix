#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/.test-out/policy"
rm -rf "$OUT" && mkdir -p "$OUT"
POLICY="$ROOT/overlays/chromium/chrome/android/java/src/org/chromium/chrome/browser/nautrix/policy"
javac -encoding UTF-8 -d "$OUT" \
  "$POLICY/NautrixConstants.java" \
  "$POLICY/TabPolicy.java" \
  "$POLICY/MediaCachePolicy.java" \
  "$POLICY/DnsScorePolicy.java" \
  "$POLICY/DownloadPolicy.java" \
  "$POLICY/TorrentPolicy.java" \
  "$ROOT/tests/java/org/chromium/chrome/browser/nautrix/policy/PolicyCoreTest.java"
java -ea -cp "$OUT" org.chromium.chrome.browser.nautrix.policy.PolicyCoreTest
