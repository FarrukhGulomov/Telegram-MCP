package io.tgmcp.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.tgmcp.config.AppProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-caller token bucket.
 *
 * <p>The default, Spring-managed instance is keyed by tenant + user, for MCP tool calls, so
 * that one noisy MCP client cannot spend another tenant's budget and a single user cannot
 * exhaust the server by fanning out. Security-audit finding SEC-10: other surfaces (login,
 * OAuth token issuance, dynamic client registration, Telegram onboarding) need their own,
 * independently configured limiters, keyed by client IP rather than tenant/user since callers
 * there are not yet authenticated -- construct additional instances directly via {@link
 * #RateLimiter(int, Duration, Clock)} rather than adding more ambiguous {@code @Component}
 * beans of this same type.
 */
@Component
public class RateLimiter {

    /** Bounds how often {@link #tryAcquire} pays for a sweep of long-idle buckets. */
    private static final int SWEEP_INTERVAL = 2048;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int capacity;
    private final double refillPerMilli;
    private final AtomicLong calls = new AtomicLong();

    @Autowired
    public RateLimiter(AppProperties properties, Clock clock) {
        this(properties.mcp().rateLimitRequestsPerMinute(), Duration.ofMinutes(1), clock);
    }

    public RateLimiter(int capacity, Duration window, Clock clock) {
        this.clock = clock;
        this.capacity = Math.max(1, capacity);
        this.refillPerMilli = this.capacity / (double) window.toMillis();
    }

    public boolean tryAcquire(String key) {
        sweepOccasionally();
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

    /**
     * Security-audit finding SEC-10: a bucket map keyed by client IP (rather than an
     * authenticated tenant/user, whose cardinality this application itself controls) can grow
     * without bound at attacker-chosen cardinality -- every distinct source address adds one
     * entry that otherwise lives forever. Buckets that are both fully refilled (idle) and
     * stale are safe to drop: recreating one fresh on the next call produces the identical
     * starting state, so this can never forgive a caller that is still actively spending down
     * its budget.
     */
    private void sweepOccasionally() {
        if (this.calls.incrementAndGet() % SWEEP_INTERVAL != 0) {
            return;
        }
        long staleBeforeMillis = this.clock.millis() - Math.round(this.capacity / this.refillPerMilli) * 4;
        this.buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                return bucket.lastRefillMillis < staleBeforeMillis && bucket.tokens >= this.capacity - 0.001;
            }
        });
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
