package io.tgmcp.support;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    /** Fixed instant so date-range assertions are reproducible. */
    public static final Instant START = Instant.parse("2026-06-15T12:00:00Z");

    @Bean
    @Primary
    public Clock testClock() {
        return new MutableClock(START);
    }
}
