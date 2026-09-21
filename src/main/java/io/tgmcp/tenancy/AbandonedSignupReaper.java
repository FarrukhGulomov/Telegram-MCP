package io.tgmcp.tenancy;

import java.time.Clock;
import java.time.Instant;

import io.tgmcp.config.AppProperties;
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
public class AbandonedSignupReaper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedSignupReaper.class);

    private final TenantRepository tenants;
    private final AppProperties properties;
    private final Clock clock;

    public AbandonedSignupReaper(TenantRepository tenants, AppProperties properties, Clock clock) {
        this.tenants = tenants;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tgmcp.abuse-controls.abandoned-signup-max-age:PT1H}")
    @ConditionalOnProperty(name = "tgmcp.sync.scheduled", havingValue = "true", matchIfMissing = true)
    public void reap() {
        Instant olderThan = this.clock.instant().minus(this.properties.abuseControls().abandonedSignupMaxAge());
        for (Tenant tenant : this.tenants.findAbandonedSignups(olderThan)) {
            log.info("Deleting abandoned signup: tenant {} (created {}) never completed Telegram login",
                    tenant.id(), tenant.createdAt());
            this.tenants.delete(tenant.id());
        }
    }
}
