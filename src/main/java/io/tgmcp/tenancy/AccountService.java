package io.tgmcp.tenancy;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Self-service password rotation: the only way, before this existed, to recover from a leaked
 * password was for an operator to edit the database by hand. Requires the current password (not
 * just an active session) so a hijacked session cookie alone cannot lock the real owner out by
 * changing it to something only the attacker knows.
 */
@Service
public class AccountService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AccountService(AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public void changePassword(AppUser user, String currentPassword, String newPassword, String newPasswordConfirm) {
        if (currentPassword == null || !this.passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new IllegalArgumentException("account.password.currentIncorrect");
        }
        if (newPassword == null || !newPassword.equals(newPasswordConfirm)) {
            throw new IllegalArgumentException("register.passwordMismatch");
        }
        if (newPassword.length() < SelfRegistrationService.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("register.passwordTooShort");
        }
        this.users.updatePasswordHash(user.tenantId(), user.id(), this.passwordEncoder.encode(newPassword));
    }
}
