package io.tgmcp.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import io.tgmcp.tenancy.AppUser;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
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
        // The authorization server's own JwtGenerator derives the OIDC "auth_time" claim (an
        // ID token is minted whenever a client, e.g. ChatGPT's connector, requests the "openid"
        // scope) exclusively from a FactorGrantedAuthority's issuedAt among the authenticated
        // principal's authorities -- with none here (this sign-in is a one-time Telegram-
        // delivered code, never a password), that lookup came back null and the whole token
        // exchange failed with "authenticationTime cannot be null" for every such client.
        List<GrantedAuthority> authorities = new ArrayList<>(principal.getAuthorities());
        authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.OTT_AUTHORITY)
                .issuedAt(Instant.now())
                .build());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        this.securityContextRepository.saveContext(context, request, response);
    }
}
