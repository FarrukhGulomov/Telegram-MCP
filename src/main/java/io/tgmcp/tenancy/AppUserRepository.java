package io.tgmcp.tenancy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppUserRepository {

    private static final String COLUMNS =
            "id, tenant_id, email, password_hash, display_name, role, enabled";

    private final JdbcClient jdbc;

    public AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(AppUser.class)
                .optional();
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_users WHERE id = :id")
                .param("id", id)
                .query(AppUser.class)
                .optional();
    }

    public void insert(AppUser user) {
        jdbc.sql("""
                        INSERT INTO app_users (id, tenant_id, email, password_hash, display_name, role, enabled)
                        VALUES (:id, :tenantId, :email, :passwordHash, :displayName, :role, :enabled)
                        """)
                .param("id", user.id())
                .param("tenantId", user.tenantId())
                .param("email", user.email())
                .param("passwordHash", user.passwordHash())
                .param("displayName", user.displayName())
                .param("role", user.role())
                .param("enabled", user.enabled())
                .update();
    }
}
