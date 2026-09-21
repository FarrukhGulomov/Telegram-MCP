package io.tgmcp.security;

import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Resolves the authorities/roles a {@link ManualSessionEstablisher} attaches to a session once a
 * Telegram-delivered code has already proven identity -- there is no password to check here, so
 * the placeholder value handed to {@link User.UserBuilder#password} is never read by anything.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = this.users.findByEmail(username)
                // Same message either way: the login form must not confirm which accounts exist.
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
        return User.withUsername(user.email())
                .password("")
                .roles(user.role())
                .disabled(!user.enabled())
                .build();
    }
}
