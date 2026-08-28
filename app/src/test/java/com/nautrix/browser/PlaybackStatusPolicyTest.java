package com.nautrix.browser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PlaybackStatusPolicyTest {
    @Test
    public void reportsOfflineBeforeServerSpeed() {
        assertEquals(PlaybackStatusPolicy.Status.OFFLINE,
                PlaybackStatusPolicy.bufferingStatus(false, false, 30_000L));
    }

    @Test
    public void allowsInitialConnectionWindow() {
        assertEquals(PlaybackStatusPolicy.Status.CONNECTING,
                PlaybackStatusPolicy.bufferingStatus(true, false, 7_999L));
    }

    @Test
    public void flagsSlowInitialServerAtEightSeconds() {
        assertEquals(PlaybackStatusPolicy.Status.SLOW_SERVER,
                PlaybackStatusPolicy.bufferingStatus(true, false, 8_000L));
    }

    @Test
    public void flagsRebufferingSooner() {
        assertEquals(PlaybackStatusPolicy.Status.BUFFERING,
                PlaybackStatusPolicy.bufferingStatus(true, true, 4_999L));
        assertEquals(PlaybackStatusPolicy.Status.SLOW_SERVER,
                PlaybackStatusPolicy.bufferingStatus(true, true, 5_000L));
    }
}
