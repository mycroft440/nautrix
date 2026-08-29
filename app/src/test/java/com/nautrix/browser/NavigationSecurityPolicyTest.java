package com.nautrix.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigationSecurityPolicyTest {
    @Test
    public void externalAppsRequireMainFrameAndGesture() {
        assertTrue(NavigationSecurityPolicy.mayLaunchExternal(true, true));
        assertFalse(NavigationSecurityPolicy.mayLaunchExternal(true, false));
        assertFalse(NavigationSecurityPolicy.mayLaunchExternal(false, true));
    }

    @Test
    public void fallbackAcceptsOnlyCredentialFreeHttps() {
        assertEquals("https://example.com/fallback",
                NavigationSecurityPolicy.safeHttpsUrl("https://example.com/fallback"));
        assertNull(NavigationSecurityPolicy.safeHttpsUrl("http://example.com/fallback"));
        assertNull(NavigationSecurityPolicy.safeHttpsUrl("https://user:secret@example.com/"));
        assertNull(NavigationSecurityPolicy.safeHttpsUrl("javascript:alert(1)"));
    }

    @Test
    public void upgradesHttpWithoutKeepingDefaultPort() {
        assertEquals("https://example.com/path?q=1",
                NavigationSecurityPolicy.upgradeHttpToHttps("http://example.com:80/path?q=1"));
        assertEquals("https://example.com/path",
                NavigationSecurityPolicy.upgradeHttpToHttps("https://example.com/path"));
    }

    @Test
    public void refererIsReducedToOrigin() {
        assertEquals("https://example.com/",
                NavigationSecurityPolicy.originOnly("https://example.com/private/page?q=secret"));
        assertEquals("https://example.com:8443/",
                NavigationSecurityPolicy.originOnly("https://example.com:8443/page"));
    }

    @Test
    public void originComparisonIncludesPort() {
        assertTrue(NavigationSecurityPolicy.sameHttpsOrigin(
                "https://example.com/video", "https://EXAMPLE.com/segment"));
        assertFalse(NavigationSecurityPolicy.sameHttpsOrigin(
                "https://example.com/video", "https://cdn.example.com/segment"));
        assertFalse(NavigationSecurityPolicy.sameHttpsOrigin(
                "https://example.com/video", "https://example.com:8443/segment"));
    }
}
