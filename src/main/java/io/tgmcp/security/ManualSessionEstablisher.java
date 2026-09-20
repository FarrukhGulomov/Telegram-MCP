package io.tgmcp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import io.tgmcp.tenancy.AppUser;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Establishes or clears a signed-in {@link SecurityContext} outside the normal username/password
 * form-login path -- used once a visitor has actually proven who they are through some other
 * means ({@code TelegramLoginController}'s widget callback, or {@code LoginVerificationController}
 * after a second-factor code), and, in the other direction, to immediately undo the session a
 * password check alone established while a second factor is still outstanding.
 */
@Component
public class ManualSessionEstablisher {

    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository;

    public ManualSessionEstablisher(UserDetailsService userDetailsService,
                                    SecurityContextRepository securityContextRepository) {
        this.userDetailsService = userDetailsService;
        this.securityContextRepository = securityContextRepository;
    }

    /**
     * Rotating the session id first (when a session already exists) is the same session-fixation
     * defence Spring Security's own authentication filters apply automatically.
     */
    public void signIn(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        HttpSession existingSession = request.getSession(false);
        if (existingSession != null) {
            request.changeSessionId();
        }
        UserDetails principal = this.userDetailsService.loadUserByUsername(user.email());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        this.securityContextRepository.saveContext(context, request, response);
    }

    /**
     * Undoes whatever {@code UsernamePasswordAuthenticationFilter} just established for a
     * password check: a password alone is not sufficient once a second factor is pending, so
     * nothing about that check may leave a usable, authenticated session behind.
     */
    public void clearAuthentication(HttpServletRequest request, HttpServletResponse response) {
        SecurityContext empty = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(empty);
        this.securityContextRepository.saveContext(empty, request, response);
    }
}
