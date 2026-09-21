package io.tgmcp.tenancy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TenantRepository {

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Security-audit finding SEC-11: {@code demo_mode} is only ever set {@code true} by {@link
     * io.tgmcp.config.DemoDataSeeder} -- no other code path creates a tenant at all, so this is
     * an exact, deterministic list of every demo-seeded tenant on this database, independent of
     * whatever the currently configured demo email/slug happen to be.
     */
    public List<Tenant> findAllDemo() {
        return jdbc.sql("SELECT id, slug, name, demo_mode, created_at FROM tenants WHERE demo_mode = true")
                .query(Tenant.class)
                .list();
    }

    public Optional<Tenant> findById(UUID id) {
        return jdbc.sql("SELECT id, slug, name, demo_mode, created_at FROM tenants WHERE id = :id")
                .param("id", id)
                .query(Tenant.class)
                .optional();
    }

    public Optional<Tenant> findBySlug(String slug) {
        return jdbc.sql("SELECT id, slug, name, demo_mode, created_at FROM tenants WHERE slug = :slug")
                .param("slug", slug)
                .query(Tenant.class)
                .optional();
    }

    /**
     * Security-audit finding SEC-R08: candidates for {@link AbandonedSignupReaper} -- real
     * (non-demo) tenants old enough that any legitimate login attempt should have long since
     * either finished or been abandoned, and whose Telegram connection has still never once
     * reached {@code READY} (a connection that reached READY at any point, however long ago,
     * proved real identity and everything downstream of it is real data, never reaped, even if
     * the connection has since been disconnected or lost authorization).
     */
    public List<Tenant> findAbandonedSignups(Instant olderThan) {
        return jdbc.sql("""
                        SELECT t.id, t.slug, t.name, t.demo_mode, t.created_at
                          FROM tenants t
                         WHERE t.demo_mode = false
                           AND t.created_at < :olderThan
                           AND NOT EXISTS (
                               SELECT 1 FROM telegram_connections c
                                WHERE c.tenant_id = t.id AND c.connected_at IS NOT NULL
                           )
                        """)
                .param("olderThan", java.sql.Timestamp.from(olderThan))
                .query(Tenant.class)
                .list();
    }

    public void insert(Tenant tenant) {
        jdbc.sql("""
                        INSERT INTO tenants (id, slug, name, demo_mode)
                        VALUES (:id, :slug, :name, :demoMode)
                        """)
                .param("id", tenant.id())
                .param("slug", tenant.slug())
                .param("name", tenant.name())
                .param("demoMode", tenant.demoMode())
                .update();
    }

    /**
     * Rolls back a tenant this same request eagerly created (see {@code PreAuthLoginService}):
     * cascades to its {@code app_users}/{@code telegram_connections} row via {@code ON DELETE
     * CASCADE}. Only ever called for a tenant that was just created and never reached a real
     * login, so there is nothing else of value to lose.
     */
    public void delete(UUID id) {
        jdbc.sql("DELETE FROM tenants WHERE id = :id").param("id", id).update();
    }
}
