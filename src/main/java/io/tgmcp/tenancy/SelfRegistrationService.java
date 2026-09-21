package io.tgmcp.tenancy;

import java.io.Serializable;
import java.util.Locale;
import java.util.UUID;

import io.tgmcp.common.Ids;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Lets a new team create its own tenant and OWNER account, without an operator having to seed
 * it by hand -- the demo tenant/user in {@link io.tgmcp.config.DemoDataSeeder} was previously
 * the only way any {@code app_users} row came to exist.
 *
 * <p>Validation ({@link #validate}) and persistence ({@link #completeRegistration}) are
 * deliberately two steps: when Telegram sign-in is configured, {@code RegistrationController}
 * holds a validated {@link PendingRegistration} in the visitor's session across the "Sign in
 * with Telegram" round trip required to link an account, and only creates the row once that
 * comes back -- so a visitor who abandons that step never leaves behind a usable, unlinked
 * account. Deployments without Telegram sign-in configured skip straight to persistence.
 */
@Service
public class SelfRegistrationService {

    /** Package-visible so {@link AccountService} enforces the identical rule for a password change. */
    static final int MIN_PASSWORD_LENGTH = 10;

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public SelfRegistrationService(TenantRepository tenants, AppUserRepository users,
                                   PasswordEncoder passwordEncoder) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** A validated, not-yet-persisted registration. The password is already hashed. */
    public record PendingRegistration(String workspaceName, String normalizedEmail,
                                      String passwordHash) implements Serializable {
    }

    /**
     * Checks every field a registration form submits, but does not touch the database except
     * to check the email is not already taken. Throws {@link IllegalArgumentException} with a
     * message-catalog code, exactly like the previous single-step {@code register} method did.
     */
    public PendingRegistration validate(String workspaceName, String email, String password) {
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
        return new PendingRegistration(name, normalizedEmail, this.passwordEncoder.encode(password));
    }

    /**
     * Actually creates the tenant and OWNER user. Re-checks email uniqueness (and, when given,
     * Telegram-id uniqueness) immediately before inserting: real wall-clock time -- an entire
     * "Sign in with Telegram" round trip -- can pass between {@link #validate} and this call, so
     * the checks {@code validate} already ran are not guaranteed to still hold.
     *
     * @param telegramUserId the Telegram identity this account is linked to, or {@code null} on
     *                        a deployment where Telegram sign-in is not configured at all.
     */
    public AppUser completeRegistration(PendingRegistration pending, Long telegramUserId,
                                        String telegramUsername) {
        if (this.users.findByEmail(pending.normalizedEmail()).isPresent()) {
            throw new IllegalArgumentException("register.emailTaken");
        }
        if (telegramUserId != null && this.users.findByTelegramUserId(telegramUserId).isPresent()) {
            throw new IllegalArgumentException("register.telegramTaken");
        }
        UUID tenantId = Ids.newId();
        this.tenants.insert(new Tenant(tenantId, uniqueSlug(pending.workspaceName()), pending.workspaceName(),
                false, null));
        AppUser user = new AppUser(Ids.newId(), tenantId, pending.normalizedEmail(), pending.passwordHash(),
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
