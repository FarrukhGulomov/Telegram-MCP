package io.tgmcp.tenancy;

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
}
