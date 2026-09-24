package io.tgmcp.tenancy;

import java.time.Clock;
import java.time.Instant;

import io.tgmcp.config.AppProperties;
import io.tgmcp.telegram.gateway.TelegramGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Security-audit finding SEC-R08: {@code PreAuthLoginService#createAccount} creates a
 * permanent tenant/user row for a new phone number the instant a login attempt starts,
 * before Telegram's own login (the code it sends, and the two-factor password if one is set)
 * ever proves the visitor holds that number -- TDLib needs a stable per-tenant identity to run
 * its login state machine against from the very first request, so that row cannot simply be
 * created later once proof exists. A visitor who mistypes a number, closes the tab, or
 * otherwise abandons the flow leaves that row behind forever unless they explicitly cancel.
 *
 * <p>Rather than the larger rework a true "no row until proof" fix would need, this closes the
 * actual gap -- unproven data lingering permanently -- by deleting any such tenant once it is
 * old enough that no legitimate in-progress login could still be using it. {@link
 * TenantRepository#findAbandonedSignups} is exact about what counts as "abandoned": a tenant
 * whose connection has <em>never once</em> reached {@code READY}. Any tenant that ever
 * completed a real login is never touched here again, no matter what its connection's status
 * is by the time this runs.
 */
@Service
// Security-audit finding L2: @ConditionalOnProperty has no effect on an ordinary @Scheduled
// method -- Spring's condition evaluator only ever consults it on a class (component-scan
// registration) or a @Bean factory method. It used to sit on reap() itself, silently doing
// nothing: the reaper kept running every tick regardless of tgmcp.sync.scheduled. Moved to the
// class, matching SyncScheduler's own working pattern.
@ConditionalOnProperty(name = "tgmcp.sync.scheduled", havingValue = "true", matchIfMissing = true)
public class AbandonedSignupReaper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedSignupReaper.class);

    private final TenantRepository tenants;
    private final AppProperties properties;
    private final Clock clock;
    private final TelegramGateway gateway;

    public AbandonedSignupReaper(TenantRepository tenants, AppProperties properties, Clock clock,
                                 TelegramGateway gateway) {
        this.tenants = tenants;
        this.properties = properties;
        this.clock = clock;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${tgmcp.abuse-controls.abandoned-signup-max-age:PT1H}")
    public void reap() {
        Instant olderThan = this.clock.instant().minus(this.properties.abuseControls().abandonedSignupMaxAge());
        for (Tenant tenant : this.tenants.findAbandonedSignups(olderThan)) {
            log.info("Deleting abandoned signup: tenant {} (created {}) never completed Telegram login",
                    tenant.id(), tenant.createdAt());
            // Security-audit finding H3: deleting only the database row left that tenant's TDLib
            // client (native session, executor thread pool, on-disk directory) resident for the
            // life of the JVM regardless -- unlike a real tenant's client (a documented,
            // accepted per-tenant cost), this one belongs to nothing any longer, so keeping it
            // alive is pure, uncapped waste an anonymous caller could keep growing indefinitely.
            this.gateway.discardTenant(tenant.id());
            this.tenants.delete(tenant.id());
        }
    }
}
