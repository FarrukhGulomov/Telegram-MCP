package io.tgmcp.ui;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.net.URLEncoder;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import io.tgmcp.common.Ids;
import io.tgmcp.config.AppProperties;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.tenancy.Tenant;
import io.tgmcp.tenancy.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * "Sign in with Telegram": a redirect to Telegram's own {@code oauth.telegram.org} login page
 * and back, rather than the embeddable JS login widget -- the management UI's CSP is {@code
 * script-src 'self'} (see {@code WebSecurityConfig}), and this way that never has to change.
 * The redirect itself carries no secret; the callback's query parameters are what Telegram signs
 * with the bot token, and {@link #verify} is what actually establishes trust.
 *
 * <p>A first-time Telegram identity provisions its own new tenant, exactly like {@link
 * io.tgmcp.tenancy.SelfRegistrationService} does for email/password sign-up -- this is a second,
 * independent front door to the same self-service model, not an alternative credential bolted
 * onto an existing account.
 */
@Controller
public class TelegramLoginController {

    private static final Logger log = LoggerFactory.getLogger(TelegramLoginController.class);

    /** Telegram does not date-stamp the redirect itself; a stale, replayed link is rejected. */
    private static final Duration MAX_AUTH_AGE = Duration.ofMinutes(10);

    private final AppProperties properties;
    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;
    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;
    private final String publicBaseUrl;

    public TelegramLoginController(AppProperties properties, AppUserRepository users, TenantRepository tenants,
                                   PasswordEncoder passwordEncoder, UserDetailsService userDetailsService,
                                   SecurityContextRepository securityContextRepository, Clock clock,
                                   @Value("${tgmcp.oauth.public-base-url:http://localhost:8080}")
                                   String publicBaseUrl) {
        this.properties = properties;
        this.users = users;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.userDetailsService = userDetailsService;
        this.securityContextRepository = securityContextRepository;
        this.clock = clock;
        this.publicBaseUrl = publicBaseUrl;
    }

    @GetMapping("/login/telegram")
    public String start() {
        AppProperties.TelegramLogin config = this.properties.telegramLogin();
        if (!config.enabled()) {
            return "redirect:/login?telegramError";
        }
        String botId = config.botToken().split(":", 2)[0];
        String callbackUrl = this.publicBaseUrl + "/login/telegram/callback";
        String redirectUrl = "https://oauth.telegram.org/auth?bot_id=" + encode(botId)
                + "&origin=" + encode(this.publicBaseUrl)
                + "&embed=0&request_access=write&return_to=" + encode(callbackUrl);
        return "redirect:" + redirectUrl;
    }

    @GetMapping("/login/telegram/callback")
    public String callback(@RequestParam Map<String, String> params, HttpServletRequest request,
                           HttpServletResponse response, RedirectAttributes redirectAttributes) {
        AppProperties.TelegramLogin config = this.properties.telegramLogin();
        if (!config.enabled() || !verify(params, config.botToken())) {
            log.warn("Rejected a /login/telegram/callback request: missing configuration or an invalid "
                    + "Telegram signature.");
            return "redirect:/login?telegramError";
        }
        long telegramUserId;
        try {
            telegramUserId = Long.parseLong(params.get("id"));
        }
        catch (NumberFormatException ex) {
            return "redirect:/login?telegramError";
        }
        AppUser user = this.users.findByTelegramUserId(telegramUserId).orElseGet(() -> provision(telegramUserId,
                params));
        if (!user.enabled()) {
            return "redirect:/login?telegramError";
        }
        signIn(user, request, response);
        return "redirect:/";
    }

    /**
     * Verifies the callback per Telegram's login-widget specification: every received field
     * except {@code hash}, sorted and joined as {@code key=value} lines, HMAC-SHA-256'd with
     * SHA-256(bot token) as the key, must equal the received {@code hash}; and {@code
     * auth_date} must be recent, so a captured callback URL cannot be replayed indefinitely.
     */
    private boolean verify(Map<String, String> params, String botToken) {
        String receivedHash = params.get("hash");
        String authDate = params.get("auth_date");
        if (receivedHash == null || authDate == null || params.get("id") == null) {
            return false;
        }
        try {
            long authEpochSeconds = Long.parseLong(authDate);
            if (Instant.ofEpochSecond(authEpochSeconds).isBefore(this.clock.instant().minus(MAX_AUTH_AGE))) {
                return false;
            }
        }
        catch (NumberFormatException ex) {
            return false;
        }

        SortedMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("hash");
        StringBuilder dataCheckString = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!dataCheckString.isEmpty()) {
                dataCheckString.append('\n');
            }
            dataCheckString.append(entry.getKey()).append('=').append(entry.getValue());
        }

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] secretKey = sha256.digest(botToken.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] computed = mac.doFinal(dataCheckString.toString().getBytes(StandardCharsets.UTF_8));
            byte[] received = HexFormat.of().parseHex(receivedHash.toLowerCase(Locale.ROOT));
            return MessageDigest.isEqual(computed, received);
        }
        catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException ex) {
            return false;
        }
    }

    /** First time this Telegram identity has signed in: a fresh tenant, exactly as self-registration creates one. */
    private AppUser provision(long telegramUserId, Map<String, String> params) {
        String firstName = params.getOrDefault("first_name", "");
        String lastName = params.getOrDefault("last_name", "");
        String displayName = (firstName + " " + lastName).strip();
        if (displayName.isBlank()) {
            displayName = "Telegram user " + telegramUserId;
        }
        // Telegram never provides an email; this account is reachable only via the Telegram
        // identity that created it. The ".invalid" TLD matches the synthetic addresses
        // DemoDataSeeder already uses for accounts that are not meant to receive real mail.
        String email = "telegram-" + telegramUserId + "@users.tgmcp.invalid";
        String slugBase = params.getOrDefault("username", "tg-" + telegramUserId).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-");
        String slug = uniqueSlug(slugBase.isBlank() ? "tg-" + telegramUserId : slugBase);

        UUID tenantId = Ids.newId();
        this.tenants.insert(new Tenant(tenantId, slug, displayName + "'s workspace", false, null));
        // Password sign-in is not offered for a Telegram-provisioned account: a random,
        // never-surfaced value here just keeps password_hash's NOT NULL constraint satisfied.
        AppUser user = new AppUser(Ids.newId(), tenantId, email,
                this.passwordEncoder.encode(UUID.randomUUID() + UUID.randomUUID().toString()),
                displayName, "OWNER", true, telegramUserId);
        this.users.insert(user);
        return user;
    }

    private String uniqueSlug(String base) {
        String candidate = base;
        int attempt = 1;
        while (this.tenants.findBySlug(candidate).isPresent()) {
            attempt++;
            candidate = base + "-" + attempt;
        }
        return candidate;
    }

    /**
     * Manually establishes the session's {@link SecurityContext} -- there is no password to
     * hand {@code AuthenticationManager} here, only a Telegram-verified identity, so this plays
     * the same role the standard form-login success handler plays for a password sign-in.
     * Rotating the session id first (when a session already exists) is the same session-fixation
     * defence Spring Security's own authentication filters apply automatically.
     */
    private void signIn(AppUser user, HttpServletRequest request, HttpServletResponse response) {
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
