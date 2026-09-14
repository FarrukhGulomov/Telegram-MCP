package io.tgmcp.ratelimit;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.tgmcp.config.AppProperties;

import org.springframework.stereotype.Component;

/**
 * Per-caller token bucket.
 *
 * <p>Keyed by tenant + user so that one noisy MCP client cannot spend another tenant's
 * budget, and so that a single user cannot exhaust the server by fanning out.
 */
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int capacity;
    private final double refillPerMilli;

    public RateLimiter(AppProperties properties, Clock clock) {
        this.clock = clock;
        this.capacity = Math.max(1, properties.mcp().rateLimitRequestsPerMinute());
        this.refillPerMilli = this.capacity / 60_000.0;
    }

    public boolean tryAcquire(String key) {
        Bucket bucket = this.buckets.computeIfAbsent(key,
                unused -> new Bucket(this.capacity, this.clock.millis()));
        synchronized (bucket) {
            long now = this.clock.millis();
            double refilled = Math.min(this.capacity, bucket.tokens + (now - bucket.lastRefillMillis) * this.refillPerMilli);
            bucket.lastRefillMillis = now;
            if (refilled < 1.0) {
                bucket.tokens = refilled;
                return false;
            }
            bucket.tokens = refilled - 1.0;
            return true;
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;

        private Bucket(double tokens, long lastRefillMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = lastRefillMillis;
        }
    }
}
