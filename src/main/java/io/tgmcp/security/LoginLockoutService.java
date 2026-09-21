package io.tgmcp.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.tgmcp.config.AppProperties;

import org.springframework.stereotype.Component;

/**
 * Locks one specific account out of password login after repeated failed attempts against it,
 * regardless of source IP.
 *
 * <p>{@code PreAuthRateLimitFilter}'s login budget (see {@code WebSecurityConfig}) is IP-keyed
 * and resets every minute -- an attacker who simply rotates IPs, or is patient, faces no
 * additional slowdown targeting one specific account's password. This closes that gap: {@link
 * DatabaseUserDetailsService} reports the account as {@code accountLocked} once its failure
 * count reaches {@link AppProperties.AbuseControls#accountLockThreshold()}, which Spring
 * Security's own {@code AbstractUserDetailsAuthenticationProvider} then rejects with {@code
 * LockedException} before even comparing the submitted password -- so a lock blocks every
 * attempt, correct password included, until it expires.
 *
 * <p>In-memory only, like {@link io.tgmcp.ratelimit.RateLimiter}: a restart forgives every
 * account's count, which is an acceptable trade-off for how rarely this application restarts
 * versus the complexity of persisting it.
 */
@Component
public class LoginLockoutService {

    private final Map<String, Attempt> attemptsByEmail = new ConcurrentHashMap<>();
    private final int threshold;
    private final Duration lockDuration;
    private final Clock clock;

    public LoginLockoutService(AppProperties properties, Clock clock) {
        this.threshold = Math.max(1, properties.abuseControls().accountLockThreshold());
        this.lockDuration = Duration.ofMinutes(Math.max(1, properties.abuseControls().accountLockMinutes()));
        this.clock = clock;
    }

    public boolean isLocked(String email) {
        Attempt attempt = this.attemptsByEmail.get(key(email));
        if (attempt == null) {
            return false;
        }
        synchronized (attempt) {
            if (attempt.count < this.threshold) {
                return false;
            }
            if (this.clock.instant().isAfter(attempt.lockedUntil)) {
                this.attemptsByEmail.remove(key(email));
                return false;
            }
            return true;
        }
    }

    /** Called only for a genuine bad-credentials failure -- never while already locked, so a
     * legitimate account holder retrying during a lockout cannot keep pushing it further out. */
    public void recordFailure(String email) {
        Attempt attempt = this.attemptsByEmail.computeIfAbsent(key(email), unused -> new Attempt());
        synchronized (attempt) {
            attempt.count++;
            if (attempt.count >= this.threshold) {
                attempt.lockedUntil = this.clock.instant().plus(this.lockDuration);
            }
        }
    }

    /** Called once the password check itself has succeeded, so a legitimate sign-in always
     * clears whatever count a few earlier typos left behind. */
    public void recordSuccess(String email) {
        this.attemptsByEmail.remove(key(email));
    }

    private static String key(String email) {
        return email == null ? "" : email.toLowerCase(Locale.ROOT);
    }

    private static final class Attempt {
        private int count;
        private Instant lockedUntil = Instant.MIN;
    }
}
