package io.tgmcp.security;

import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final LoginLockoutService lockoutService;

    public DatabaseUserDetailsService(AppUserRepository users, LoginLockoutService lockoutService) {
        this.users = users;
        this.lockoutService = lockoutService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = this.users.findByEmail(username)
                // Same message either way: the login form must not confirm which accounts exist.
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
        return User.withUsername(user.email())
                .password(user.passwordHash())
                .roles(user.role())
                .disabled(!user.enabled())
                // See LoginLockoutService's own javadoc: Spring Security's own
                // AbstractUserDetailsAuthenticationProvider rejects a locked account with
                // LockedException before it even compares the submitted password.
                .accountLocked(this.lockoutService.isLocked(user.email()))
                .build();
    }
}
