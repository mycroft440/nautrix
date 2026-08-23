package com.nautrix.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DnsScorePolicyTest {
    @Test
    public void requiresTwoSuccessfulSamplesForStability() {
        assertFalse(DnsScorePolicy.isStable(1));
        assertTrue(DnsScorePolicy.isStable(2));
    }

    @Test
    public void penalizesJitterAndFailures() {
        long stable = DnsScorePolicy.score(24, 22, 26, 3, 900);
        long erratic = DnsScorePolicy.score(20, 8, 80, 3, 900);
        long failing = DnsScorePolicy.score(15, 12, 18, 2, 900);
        assertTrue(stable < erratic);
        assertTrue(stable < failing);
    }
}
