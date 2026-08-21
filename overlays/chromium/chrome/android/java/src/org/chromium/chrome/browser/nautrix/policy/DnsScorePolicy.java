package org.chromium.chrome.browser.nautrix.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Scores real DNS query samples; lower is better. */
public final class DnsScorePolicy {
    public static final class Result {
        public final String id;
        public final long medianMs;
        public final long p95Ms;
        public final int failures;
        public final int attempts;
        public final double score;

        public Result(String id, List<Long> successfulSamplesMs, int failures, int attempts) {
            this.id = id;
            this.failures = failures;
            this.attempts = Math.max(1, attempts);
            List<Long> samples = new ArrayList<>(successfulSamplesMs);
            Collections.sort(samples);
            this.medianMs = percentile(samples, 0.50);
            this.p95Ms = percentile(samples, 0.95);
            double failureRate = failures / (double) this.attempts;
            this.score = samples.isEmpty() ? Double.POSITIVE_INFINITY
                    : medianMs + (0.35 * p95Ms) + (failureRate * 3000.0);
        }
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return Long.MAX_VALUE / 4;
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    public static Result choose(Result current, List<Result> candidates) {
        Result best = current;
        for (Result candidate : candidates) {
            if (candidate.score < best.score) best = candidate;
        }
        if (best == current || !Double.isFinite(best.score)) return current;
        if (!Double.isFinite(current.score)) return best;
        double required = current.score * (1.0 - NautrixConstants.DNS_SWITCH_HYSTERESIS);
        return best.score <= required ? best : current;
    }

    private DnsScorePolicy() {}
}
