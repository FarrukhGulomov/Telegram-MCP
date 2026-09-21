package io.tgmcp.tenancy;

import java.io.Serializable;
import java.util.Locale;
import java.util.UUID;

import io.tgmcp.common.Ids;

import org.springframework.stereotype.Service;

/**
 * Lets a new team create its own tenant and OWNER account, without an operator having to seed
 * it by hand -- the demo tenant/user in {@link io.tgmcp.config.DemoDataSeeder} was previously
 * the only way any {@code app_users} row came to exist.
 *
 * <p>Validation ({@link #validate}) and persistence ({@link #completeRegistration}) are
 * deliberately two steps: {@code RegistrationController} holds a validated {@link
 * PendingRegistration} in the visitor's session across the "Sign in with Telegram" round trip
 * required to link an account, and only creates the row once that comes back -- so a visitor
 * who abandons that step never leaves behind a usable, unlinked account. There is no other way
 * to reach an account (no password exists), so linking a Telegram identity is not optional.
 */
@Service
public class SelfRegistrationService {

    private final TenantRepository tenants;
    private final AppUserRepository users;

    public SelfRegistrationService(TenantRepository tenants, AppUserRepository users) {
        this.tenants = tenants;
        this.users = users;
    }

    /** A validated, not-yet-persisted registration. */
    public record PendingRegistration(String workspaceName, String normalizedEmail) implements Serializable {
    }

    /**
     * Checks every field a registration form submits, but does not touch the database except
     * to check the email is not already taken.
     */
    public PendingRegistration validate(String workspaceName, String email) {
        String normalizedEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) {
            throw new IllegalArgumentException("register.emailInvalid");
        }
        if (this.users.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("register.emailTaken");
        }
        String name = workspaceName == null || workspaceName.isBlank()
                ? normalizedEmail.substring(0, normalizedEmail.indexOf('@'))
                : workspaceName.strip();
        return new PendingRegistration(name, normalizedEmail);
    }

    /**
     * Actually creates the tenant and OWNER user. Re-checks email uniqueness (and Telegram-id
     * uniqueness) immediately before inserting: real wall-clock time -- an entire "Sign in with
     * Telegram" round trip -- can pass between {@link #validate} and this call, so the checks
     * {@code validate} already ran are not guaranteed to still hold.
     */
    public AppUser completeRegistration(PendingRegistration pending, long telegramUserId, String telegramUsername) {
        if (this.users.findByEmail(pending.normalizedEmail()).isPresent()) {
            throw new IllegalArgumentException("register.emailTaken");
        }
        if (this.users.findByTelegramUserId(telegramUserId).isPresent()) {
            throw new IllegalArgumentException("register.telegramTaken");
        }
        UUID tenantId = Ids.newId();
        this.tenants.insert(new Tenant(tenantId, uniqueSlug(pending.workspaceName()), pending.workspaceName(),
                false, null));
        AppUser user = new AppUser(Ids.newId(), tenantId, pending.normalizedEmail(),
                pending.workspaceName(), "OWNER", true, telegramUserId, telegramUsername);
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
