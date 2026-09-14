package io.tgmcp.sync;

import java.time.Duration;

/**
 * Bounded exponential backoff.
 *
 * <p>Kept as a pure function so the retry policy can be asserted directly in tests rather
 * than inferred from timing.
 */
public final class Backoff {

    private Backoff() {
    }

    /**
     * @param attempt      number of consecutive failures so far (1 for the first failure)
     * @param initial      delay after the first failure
     * @param max          ceiling; the delay never exceeds this no matter how many failures
     */
    public static Duration delay(int attempt, Duration initial, Duration max) {
        if (attempt <= 1) {
            return initial;
        }
        long millis = initial.toMillis();
        for (int i = 1; i < attempt && millis < max.toMillis(); i++) {
            millis = Math.min(millis * 2, max.toMillis());
        }
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
    }
}
