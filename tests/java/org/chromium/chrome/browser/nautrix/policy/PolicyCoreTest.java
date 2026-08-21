package org.chromium.chrome.browser.nautrix.policy;

import java.util.List;

public final class PolicyCoreTest {
    public static void main(String[] args) {
        testTabs();
        testCache();
        testDns();
        testDownloadRouting();
        testMagnets();
        System.out.println("Nautrix policy tests: PASS");
    }

    private static void testTabs() {
        long t0 = 1_000_000L;
        TabPolicy.State fresh = new TabPolicy.State(t0, 0, false, false, false, false, false);
        assert !TabPolicy.shouldAutoClose(t0 + NautrixConstants.NEVER_USED_TAB_TTL_MS - 1, fresh);
        assert TabPolicy.shouldAutoClose(t0 + NautrixConstants.NEVER_USED_TAB_TTL_MS, fresh);
        TabPolicy.State used = new TabPolicy.State(t0, t0 + 100, true, false, false, false, false);
        assert !TabPolicy.shouldAutoClose(t0 + NautrixConstants.DAY_MS, used);
        assert TabPolicy.shouldAutoClose(t0 + 100 + NautrixConstants.USED_TAB_TTL_MS, used);
        TabPolicy.State media = new TabPolicy.State(t0, t0, true, false, true, false, false);
        assert !TabPolicy.shouldAutoClose(Long.MAX_VALUE / 4, media);
    }

    private static void testCache() {
        long now = 10 * NautrixConstants.DAY_MS;
        assert MediaCachePolicy.isExpired(now, now - NautrixConstants.MEDIA_CACHE_TTL_MS, false, false);
        assert !MediaCachePolicy.isExpired(now, now - 100, false, false);
        assert !MediaCachePolicy.isExpired(now, 1, true, false);
        assert MediaCachePolicy.retentionScore(now, now - 10, true, false, false)
                > MediaCachePolicy.retentionScore(now, now - 10, false, false, false);
    }

    private static void testDns() {
        DnsScorePolicy.Result current = new DnsScorePolicy.Result("a", List.of(20L, 21L, 22L, 23L, 24L), 0, 5);
        DnsScorePolicy.Result tiny = new DnsScorePolicy.Result("b", List.of(19L, 20L, 21L, 22L, 23L), 0, 5);
        assert DnsScorePolicy.choose(current, List.of(tiny)) == current;
        DnsScorePolicy.Result fast = new DnsScorePolicy.Result("c", List.of(5L, 6L, 6L, 7L, 8L), 0, 5);
        assert DnsScorePolicy.choose(current, List.of(fast)) == fast;
        DnsScorePolicy.Result flaky = new DnsScorePolicy.Result("d", List.of(1L), 4, 5);
        assert flaky.score > current.score;
    }

    private static void testDownloadRouting() {
        assert DownloadPolicy.classify("https://x.test/a.m3u8") == DownloadPolicy.Kind.HLS;
        assert DownloadPolicy.classify("https://x.test/a.mpd") == DownloadPolicy.Kind.DASH;
        assert DownloadPolicy.classify("https://x.test/a.mp4") == DownloadPolicy.Kind.DIRECT_MEDIA;
        assert DownloadPolicy.classify("magnet:?xt=urn:btih:0123456789012345678901234567890123456789") == DownloadPolicy.Kind.MAGNET;
    }

    private static void testMagnets() {
        TorrentPolicy.Magnet m = TorrentPolicy.parseMagnet(
                "magnet:?xt=urn:btih:0123456789012345678901234567890123456789&dn=Test&tr=https%3A%2F%2Ft.example%2Fa");
        assert m != null;
        assert m.displayName.equals("Test");
        assert m.trackers.size() == 1;
        assert !m.v2;
        TorrentPolicy.Magnet v2 = TorrentPolicy.parseMagnet(
                "magnet:?xt=urn:btmh:1220aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&dn=V2");
        assert v2 != null;
        assert v2.v2;
        assert v2.infoHash.length() == 64;
        assert TorrentPolicy.parseMagnet("magnet:?xt=urn:btmh:1220abcd") == null;
        assert TorrentPolicy.parseMagnet("magnet:?dn=missinghash") == null;
    }
}
