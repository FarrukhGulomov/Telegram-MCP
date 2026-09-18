package io.tgmcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time removal of demo-seeded data left behind in a database that is now used for
 * real-account processing.
 *
 * <p>{@link DemoDataSeeder} is idempotent going forward (it stops inserting once {@code
 * tgmcp.demo.seed-synthetic-data=false}), but disabling it does not delete rows a previous run
 * already created -- exactly the gap {@link ProductionSafetyValidator} refuses to start against
 * (finding SEC-11). This is the remediation: an explicit, off-by-default action, not something
 * that runs as a side effect of any other flag.
 *
 * <p>Constructed eagerly, like {@link ProductionSafetyValidator}, and ordered strictly before it
 * ({@code @DependsOn}) so a cleanup enabled on the same deploy that authorizes real-account
 * access actually clears the blocking condition before the validator's own check runs, rather
 * than requiring a second restart. A no-op constructor when the flag is off, which is the
 * default and expected state once cleanup has run once.
 */
@Component
public class DemoDataCleanupRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataCleanupRunner.class);

    public DemoDataCleanupRunner(AppProperties properties, JdbcTemplate jdbc) {
        if (!properties.demo().cleanupLegacyDemoData()) {
            return;
        }
        int authorizations = jdbc.update("""
                DELETE FROM oauth2_authorization WHERE registered_client_id IN (
                    SELECT id FROM oauth2_registered_client WHERE client_id IN (?, ?))
                """, DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID, DemoDataSeeder.CHATGPT_DEMO_CLIENT_ID);
        int consents = jdbc.update("""
                DELETE FROM oauth2_authorization_consent WHERE registered_client_id IN (
                    SELECT id FROM oauth2_registered_client WHERE client_id IN (?, ?))
                """, DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID, DemoDataSeeder.CHATGPT_DEMO_CLIENT_ID);
        int registeredClients = jdbc.update(
                "DELETE FROM oauth2_registered_client WHERE client_id IN (?, ?)",
                DemoDataSeeder.CLAUDE_DEMO_CLIENT_ID, DemoDataSeeder.CHATGPT_DEMO_CLIENT_ID);
        // tenants.demo_mode is the same durable marker ProductionSafetyValidator checks;
        // every tenant-scoped table cascades from it (see V1__core_schema.sql), so this alone
        // also removes that tenant's users, chats, messages, consents and audit trail.
        int tenants = jdbc.update("DELETE FROM tenants WHERE demo_mode = true");
        log.info("Demo-data cleanup ran: removed {} oauth2_authorization row(s), {} "
                        + "oauth2_authorization_consent row(s), {} oauth2_registered_client row(s), and "
                        + "{} demo-seeded tenant(s) (with all cascading data). Set "
                        + "tgmcp.demo.cleanup-legacy-demo-data=false once this has run successfully.",
                authorizations, consents, registeredClients, tenants);
    }
}
