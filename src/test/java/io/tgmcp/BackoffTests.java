package io.tgmcp;

import java.time.Duration;

import io.tgmcp.sync.Backoff;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTests {

    private static final Duration INITIAL = Duration.ofSeconds(5);
    private static final Duration MAX = Duration.ofMinutes(10);

    @Test
    void doublesUpToTheCeiling() {
        assertThat(Backoff.delay(1, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(5));
        assertThat(Backoff.delay(2, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(10));
        assertThat(Backoff.delay(3, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(20));
        assertThat(Backoff.delay(4, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void neverExceedsTheCeilingHoweverManyFailures() {
        assertThat(Backoff.delay(50, INITIAL, MAX)).isEqualTo(MAX);
        assertThat(Backoff.delay(Integer.MAX_VALUE, INITIAL, MAX)).isEqualTo(MAX);
    }

    @Test
    void treatsAZerothAttemptAsTheFirst() {
        assertThat(Backoff.delay(0, INITIAL, MAX)).isEqualTo(INITIAL);
    }
}
