package io.tgmcp.security;

import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Resolves the authorities/roles a {@link ManualSessionEstablisher} attaches to a session once
 * a real Telegram login has already proven identity -- there is no password here, so the
 * placeholder value handed to {@link User.UserBuilder#password} is never read by anything. The
 * account's phone number is the "username" Spring Security's principal is keyed by, matching
 * how it is looked up everywhere else ({@code CurrentActor}, {@code PreAuthLoginService}).
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String phoneNumber) throws UsernameNotFoundException {
        AppUser user = this.users.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new UsernameNotFoundException("No such account"));
        return User.withUsername(user.phoneNumber())
                .password("")
                .roles(user.role())
                .disabled(!user.enabled())
                .build();
    }
}
