package io.tgmcp.config;

import java.util.UUID;

import io.tgmcp.common.Ids;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.tenancy.Tenant;
import io.tgmcp.tenancy.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the demo tenant and its owner.
 *
 * <p>Everything here is idempotent, so a restart neither duplicates nor resets anything. The
 * trusted MCP client registrations this class used to also own now live in {@link
 * TrustedMcpClientSeeder}, unconditionally: those are real client identities Claude/ChatGPT use
 * regardless of whether synthetic demo data is seeded.
 */
@Component
@ConditionalOnProperty(name = "tgmcp.demo.seed-synthetic-data", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;

    public DemoDataSeeder(TenantRepository tenants, AppUserRepository users,
                          PasswordEncoder passwordEncoder, AppProperties properties) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Demo demo = this.properties.demo();
        UUID tenantId = this.tenants.findBySlug(demo.tenantSlug())
                .map(Tenant::id)
                .orElseGet(() -> {
                    UUID id = Ids.newId();
                    this.tenants.insert(new Tenant(id, demo.tenantSlug(), demo.tenantName(), true, null));
                    log.info("Created demo tenant '{}'", demo.tenantSlug());
                    return id;
                });

        if (this.users.findByEmail(demo.ownerEmail()).isEmpty()) {
            this.users.insert(new AppUser(Ids.newId(), tenantId, demo.ownerEmail(),
                    this.passwordEncoder.encode(demo.ownerPassword()), "Demo Owner", "OWNER", true));
            log.info("Created demo user {} (password: {})", demo.ownerEmail(), demo.ownerPassword());
        }

        // A second tenant, so that cross-tenant isolation is observable in the running app and
        // not only in tests.
        this.tenants.findBySlug("other").orElseGet(() -> {
            UUID otherId = Ids.newId();
            this.tenants.insert(new Tenant(otherId, "other", "Second Synthetic Tenant", true, null));
            this.users.insert(new AppUser(Ids.newId(), otherId, "owner@other.invalid",
                    this.passwordEncoder.encode(demo.ownerPassword()), "Other Owner", "OWNER", true));
            return this.tenants.findBySlug("other").orElseThrow();
        });
    }
}
