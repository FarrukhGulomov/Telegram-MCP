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

import io.tgmcp.common.Ids;
import io.tgmcp.config.AppProperties;
import io.tgmcp.security.LoginTwoFactorService;
import io.tgmcp.security.TelegramBotMessageException;
import io.tgmcp.tenancy.ActorType;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.tenancy.SelfRegistrationService;
import io.tgmcp.tenancy.SelfRegistrationService.PendingRegistration;
import io.tgmcp.tenancy.Tenant;
import io.tgmcp.tenancy.TenantRepository;
import io.tgmcp.telegram.TelegramConnectionService;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * <p>Proving a Telegram identity here is never, by itself, enough to establish a session: every
 * path through {@link #callback} ends by handing off to {@link LoginTwoFactorService}, which
 * sends a one-time code to that same Telegram account and only {@code
 * LoginVerificationController} actually signs the visitor in once it is entered. This is what
 * stops the widget confirmation alone (which only proves control of whichever Telegram session
 * happens to be logged into the visitor's browser) from being sufficient on its own.
 *
 * <p>{@link #callback} serves two distinct situations, distinguished only by whether the
 * session carries a {@link RegistrationController#SESSION_KEY} left there by {@link
 * RegistrationController#register}:
 * <ul>
 *   <li>Signing in (no pending registration): a returning Telegram identity resolves to its
 *       existing account, or -- when "Sign in with Telegram" is used as its own front door,
 *       without ever visiting the registration form -- a first-time identity provisions a new
 *       tenant, exactly like {@link SelfRegistrationService} does for email/password sign-up.
 *   <li>Completing a registration (a pending registration is present): the validated,
 *       not-yet-persisted account from {@link RegistrationController#register} is actually
 *       created now, linked to this Telegram identity. Nothing was ever inserted before this
 *       point, so a visitor who never finishes this step leaves no account behind at all.
 * </ul>
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
    private final SelfRegistrationService registrationService;
    private final LoginTwoFactorService twoFactorService;
    private final Clock clock;
    private final String publicBaseUrl;

    public TelegramLoginController(AppProperties properties, AppUserRepository users, TenantRepository tenants,
                                   PasswordEncoder passwordEncoder, SelfRegistrationService registrationService,
                                   LoginTwoFactorService twoFactorService, Clock clock,
                                   @Value("${tgmcp.oauth.public-base-url:http://localhost:8080}")
                                   String publicBaseUrl) {
        this.properties = properties;
        this.users = users;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.registrationService = registrationService;
        this.twoFactorService = twoFactorService;
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
    public String callback(@RequestParam Map<String, String> params, HttpServletRequest request) {
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

        PendingRegistration pendingRegistration = RegistrationController.pendingRegistration(request);
        if (pendingRegistration != null) {
            return completeRegistrationLink(pendingRegistration, telegramUserId, request);
        }

        boolean[] freshlyProvisioned = {false};
        AppUser user = this.users.findByTelegramUserId(telegramUserId).orElseGet(() -> {
            freshlyProvisioned[0] = true;
            return provision(telegramUserId, params);
        });
        if (!user.enabled()) {
            return "redirect:/login?telegramError";
        }
        return beginSecondFactor(user, request, freshlyProvisioned[0]);
    }

    /** A Telegram identity already linked to someone else's account cannot also register this one. */
    private String completeRegistrationLink(PendingRegistration pending, long telegramUserId,
                                            HttpServletRequest request) {
        if (this.users.findByTelegramUserId(telegramUserId).isPresent()) {
            return "redirect:/register/link-telegram?telegramTaken";
        }
        AppUser user;
        try {
            user = this.registrationService.completeRegistration(pending, telegramUserId);
        }
        catch (IllegalArgumentException ex) {
            // The email was taken by a concurrent registration in the time since validate() ran.
            request.getSession().removeAttribute(RegistrationController.SESSION_KEY);
            return "redirect:/register";
        }
        request.getSession().removeAttribute(RegistrationController.SESSION_KEY);
        return beginSecondFactor(user, request, false);
    }

    /**
     * Sends the mandatory second-factor code and sends the visitor to enter it. {@code
     * autoStartQr} threads through to {@code LoginVerificationController}, which starts the QR
     * handshake once the code is verified -- see {@link #startQrConnectionFor} below for why a
     * brand-new Telegram sign-in gets this and a registration-linked or returning one does not.
     */
    private String beginSecondFactor(AppUser user, HttpServletRequest request, boolean autoStartQr) {
        try {
            this.twoFactorService.beginVerification(user, request);
        }
        catch (TelegramBotMessageException ex) {
            log.warn("Could not deliver a second-factor code to Telegram user {}: {}", user.telegramUserId(),
                    ex.getMessage());
            return "redirect:/login?telegramCodeError";
        }
        if (autoStartQr) {
            request.getSession().setAttribute(LoginVerificationController.AUTOSTART_QR_SESSION_KEY, Boolean.TRUE);
        }
        return "redirect:/login/verify";
    }

    /**
     * A brand-new Telegram sign-in already proved this visitor's Telegram identity -- nothing
     * more is gained by also asking them to open the Connection tab and pick a method by hand,
     * so this starts the QR handshake for them once the second factor above is verified. Scanning
     * the code itself is not skippable: only that does the MTProto handshake this login could
     * never do. Best-effort -- if the adapter cannot start a QR login right now (the TDLIB
     * adapter is unproven native code; see docs/production-prerequisites.md), the visitor still
     * lands on an ordinary, freshly signed-in dashboard and can start a connection manually.
     */
    static String startQrConnectionFor(AppUser user, TelegramConnectionService connectionService,
                                       MessageSource messages, RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = new AuthenticatedActor(user.tenantId(), user.id(), user.email(),
                ActorType.UI_USER, java.util.Set.of("ui:manage"), null);
        try {
            LoginState state = connectionService.beginLogin(actor, LoginMethod.QR_CODE, null);
            redirectAttributes.addFlashAttribute("message", Messages.text(messages, state.message()));
            return "redirect:/connect";
        }
        catch (RuntimeException ex) {
            log.warn("Could not auto-start a QR connection after a fresh Telegram sign-in; the visitor can "
                    + "still start one manually from /connect.", ex);
            return "redirect:/";
        }
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
