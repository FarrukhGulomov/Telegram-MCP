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
        return actor(requireUser());
    }

    /**
     * Same as {@link #require}, but also re-checks, fresh from the database, that the current
     * user still holds the OWNER or ADMIN role. Security-audit finding SEC-09:
     * WebSecurityConfig's {@code hasAnyRole("OWNER", "ADMIN")} check (F17) is only ever
     * evaluated against the authorities captured in the session's {@code Authentication} at
     * sign-in time -- if an operator demotes this user afterwards, their already-signed-in
     * session keeps acting as OWNER/ADMIN, for every state-changing action, until it expires
     * or they sign in again. Every controller method behind such an action must call this
     * instead of {@link #require}.
     */
    public AuthenticatedActor requireAdmin() {
        AppUser user = requireUser();
        if (!user.isAdmin()) {
            throw new IllegalStateException("error.roleNoLongerAuthorizes");
        }
        return actor(user);
    }

    public AppUser requireUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Not authenticated");
        }
        AppUser user = this.users.findByPhoneNumber(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated principal has no user record"));
        // Security-audit finding SEC-09: the session's Authentication reflects account status
        // only as of sign-in time. AppUser is already re-read from the database on every call
        // here (this runs at the top of every controller action), so checking it fresh costs
        // nothing extra and closes the gap: an account disabled after sign-in must stop
        // working immediately, not merely at the next sign-in attempt.
        if (!user.enabled()) {
            throw new IllegalStateException("error.accountDisabled");
        }
        return user;
    }

    private static AuthenticatedActor actor(AppUser user) {
        return new AuthenticatedActor(user.tenantId(), user.id(), user.phoneNumber(), ActorType.UI_USER,
                java.util.Set.of("ui:manage"), null);
    }
}
