package io.tgmcp.security;

import java.time.Duration;
import java.time.Instant;

import io.tgmcp.config.AppProperties;
import io.tgmcp.support.MutableClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Distinct from {@code PreAuthRateLimitFilter}'s IP-keyed budget: this locks one specific
 * account regardless of source IP, and only for genuine bad-credentials failures.
 */
class LoginLockoutServiceTests {

    private static final int THRESHOLD = 3;
    private static final int LOCK_MINUTES = 15;

    private LoginLockoutService service(MutableClock clock) {
        AppProperties.AbuseControls abuseControls = new AppProperties.AbuseControls(
                10, 20, 5, 30, 5, THRESHOLD, LOCK_MINUTES, 10);
        AppProperties properties = new AppProperties(null, null, null, null, null, null,
                abuseControls, null, null);
        return new LoginLockoutService(properties, clock);
    }

    @Test
    void anAccountWithNoFailuresIsNotLocked() {
        LoginLockoutService lockout = service(new MutableClock(Instant.parse("2026-06-15T12:00:00Z")));
        assertThat(lockout.isLocked("owner@example.invalid")).isFalse();
    }

    @Test
    void locksOnceTheThresholdIsReached() {
        LoginLockoutService lockout = service(new MutableClock(Instant.parse("2026-06-15T12:00:00Z")));
        for (int i = 0; i < THRESHOLD - 1; i++) {
            lockout.recordFailure("owner@example.invalid");
            assertThat(lockout.isLocked("owner@example.invalid")).isFalse();
        }
        lockout.recordFailure("owner@example.invalid");
        assertThat(lockout.isLocked("owner@example.invalid")).isTrue();
    }

    @Test
    void isCaseInsensitiveOnTheEmail() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-15T12:00:00Z"));
        LoginLockoutService lockout = service(clock);
        for (int i = 0; i < THRESHOLD; i++) {
            lockout.recordFailure("Owner@Example.Invalid");
        }
        assertThat(lockout.isLocked("owner@example.invalid")).isTrue();
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-15T12:00:00Z"));
        LoginLockoutService lockout = service(clock);
        for (int i = 0; i < THRESHOLD - 1; i++) {
            lockout.recordFailure("owner@example.invalid");
        }
        lockout.recordSuccess("owner@example.invalid");
        lockout.recordFailure("owner@example.invalid");
        assertThat(lockout.isLocked("owner@example.invalid"))
                .as("the earlier near-miss must not carry over past a successful sign-in")
                .isFalse();
    }

    @Test
    void theLockExpiresAfterTheConfiguredDuration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-15T12:00:00Z"));
        LoginLockoutService lockout = service(clock);
        for (int i = 0; i < THRESHOLD; i++) {
            lockout.recordFailure("owner@example.invalid");
        }
        assertThat(lockout.isLocked("owner@example.invalid")).isTrue();

        clock.advance(Duration.ofMinutes(LOCK_MINUTES).plusSeconds(1));
        assertThat(lockout.isLocked("owner@example.invalid")).isFalse();
    }

    @Test
    void retryingWhileLockedDoesNotPushTheLockFurtherOut() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-15T12:00:00Z"));
        LoginLockoutService lockout = service(clock);
        for (int i = 0; i < THRESHOLD; i++) {
            lockout.recordFailure("owner@example.invalid");
        }
        clock.advance(Duration.ofMinutes(LOCK_MINUTES).minusSeconds(30));
        assertThat(lockout.isLocked("owner@example.invalid"))
                .as("still inside the original lock window")
                .isTrue();

        clock.advance(Duration.ofSeconds(31));
        assertThat(lockout.isLocked("owner@example.invalid"))
                .as("the original lock window (unextended by any further recordFailure calls) has elapsed")
                .isFalse();
    }
}
