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

    public DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
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
                .build();
    }
}
