package io.tgmcp.ui;

import io.tgmcp.tenancy.ActorType;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.tenancy.AuthenticatedActor;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the signed-in management UI user.
 *
 * <p>The tenant comes from the user record found by the authenticated principal name, never
 * from a request parameter, so a UI user cannot address another tenant's data by editing a URL.
 */
@Component
public class CurrentActor {

    private final AppUserRepository users;

    public CurrentActor(AppUserRepository users) {
        this.users = users;
    }

    public AuthenticatedActor require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Not authenticated");
        }
        AppUser user = this.users.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated principal has no user record"));
        return new AuthenticatedActor(user.tenantId(), user.id(), user.email(), ActorType.UI_USER,
                java.util.Set.of("ui:manage"), null);
    }

    public AppUser requireUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return this.users.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated principal has no user record"));
    }
}
