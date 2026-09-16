package io.tgmcp.tenancy;

import java.util.Locale;
import java.util.UUID;

import io.tgmcp.common.Ids;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Lets a new team create its own tenant and OWNER account, without an operator having to seed
 * it by hand -- the demo tenant/user in {@link io.tgmcp.config.DemoDataSeeder} was previously
 * the only way any {@code app_users} row came to exist.
 */
@Service
public class SelfRegistrationService {

    private static final int MIN_PASSWORD_LENGTH = 10;

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public SelfRegistrationService(TenantRepository tenants, AppUserRepository users,
                                   PasswordEncoder passwordEncoder) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates a fresh tenant and its OWNER user. The same generic-looking failure ({@code
     * register.emailTaken}) is used whether the email belongs to another tenant's owner or a
     * member of one -- like {@link io.tgmcp.security.DatabaseUserDetailsService}, this must not
     * let a registration attempt confirm which email addresses already have accounts.
     */
    public AppUser register(String workspaceName, String email, String password) {
        String normalizedEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) {
            throw new IllegalArgumentException("register.emailInvalid");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("register.passwordTooShort");
        }
        if (this.users.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("register.emailTaken");
        }
        String name = workspaceName == null || workspaceName.isBlank()
                ? normalizedEmail.substring(0, normalizedEmail.indexOf('@'))
                : workspaceName.strip();

        UUID tenantId = Ids.newId();
        this.tenants.insert(new Tenant(tenantId, uniqueSlug(name), name, false, null));
        AppUser user = new AppUser(Ids.newId(), tenantId, normalizedEmail,
                this.passwordEncoder.encode(password), name, "OWNER", true, null);
        this.users.insert(user);
        return user;
    }

    private String uniqueSlug(String name) {
        String base = slugify(name);
        String candidate = base;
        int attempt = 1;
        while (this.tenants.findBySlug(candidate).isPresent()) {
            attempt++;
            candidate = base + "-" + attempt;
        }
        return candidate;
    }

    private static String slugify(String name) {
        String slug = name.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "team-" + Ids.newId().toString().substring(0, 8);
        }
        return slug.length() > 64 ? slug.substring(0, 64) : slug;
    }
}
