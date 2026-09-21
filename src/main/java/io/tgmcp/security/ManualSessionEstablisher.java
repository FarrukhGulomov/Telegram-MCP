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
 * Establishes a signed-in {@link SecurityContext} directly -- there is no password for any
 * {@code AuthenticationManager}/{@code UsernamePasswordAuthenticationFilter} to check, so this
 * is the only way a session is ever created, once {@code LoginVerificationController} confirms a
 * Telegram-delivered code checks out.
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
        UserDetails principal = this.userDetailsService.loadUserByUsername(user.phoneNumber());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        this.securityContextRepository.saveContext(context, request, response);
    }
}
