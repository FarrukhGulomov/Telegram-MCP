package io.tgmcp.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import io.tgmcp.common.Ids;
import io.tgmcp.tenancy.AppUser;

import org.springframework.stereotype.Service;

/**
 * Sends and verifies the short numeric code delivered to an account's linked Telegram chat --
 * three different call sites reach it for three different reasons: a password check ({@code
 * WebSecurityConfig}'s form-login success handler) that alone never proves the visitor currently
 * has access to that Telegram account, a just-completed registration ({@code
 * RegistrationController}), and "sign in with a Telegram code" ({@code
 * TelegramCodeLoginController}), where the code is the entire login rather than a second factor.
 *
 * <p>"Pending" state (which {@link AppUser} is one code entry away from a session) lives in the
 * {@link HttpSession} attribute {@link #SESSION_KEY}, deliberately never in
 * {@link org.springframework.security.core.context.SecurityContextHolder}: nothing this class
 * does grants any authority until {@link #verify} actually succeeds.
 */
@Service
public class LoginTwoFactorService {

    static final String SESSION_KEY = "tgmcp.pending2faUserId";

    private static final int CODE_LENGTH = 6;
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 5;

    private final LoginVerificationCodeRepository codes;
    private final TelegramBotMessenger messenger;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LoginTwoFactorService(LoginVerificationCodeRepository codes, TelegramBotMessenger messenger,
                                 Clock clock) {
        this.codes = codes;
        this.messenger = messenger;
        this.clock = clock;
    }

    /**
     * Issues a fresh code, sends it, and marks {@code user} pending in this request's session.
     * Throws rather than silently letting the visitor in when delivery fails -- most commonly
     * because they have never opened a chat with the sign-in bot, in which case {@link
     * TelegramBotMessageException#getMessage()} is a Telegram-supplied description, not
     * end-user text; callers show a generic "couldn't send the code" message instead.
     */
    public void beginVerification(AppUser user, HttpServletRequest request) throws TelegramBotMessageException {
        issueAndSend(user);
        request.getSession(true).setAttribute(SESSION_KEY, user.id().toString());
    }

    /** Re-sends for whichever user this session currently has pending; throttled. */
    public void resend(HttpServletRequest request, AppUser user) throws TelegramBotMessageException {
        Optional<LoginVerificationCode> latest = this.codes.findLatestForUser(user.id());
        if (latest.isPresent()
                && latest.get().createdAt().isAfter(this.clock.instant().minus(RESEND_COOLDOWN))) {
            throw new IllegalStateException("login.verify.resendTooSoon");
        }
        issueAndSend(user);
    }

    private void issueAndSend(AppUser user) throws TelegramBotMessageException {
        String code = generateCode();
        UUID id = Ids.newId();
        Instant now = this.clock.instant();
        this.messenger.sendMessage(user.telegramUserId(), "Telegram Consent MCP: " + code
                + "\n\nEnter this code to sign in. It expires in 5 minutes; do not share it with anyone.");
        this.codes.insert(new LoginVerificationCode(id, user.id(), hash(id, code), now.plus(CODE_TTL), null, 0,
                now));
    }

    /**
     * @return the verified user's id on success. Every rejection reason is a distinct
     *         {@link IllegalStateException} message code -- expired, wrong, or exhausted --
     *         translated by the caller like every other {@code validation.*}/{@code
     *         login.*} code in this application.
     */
    public UUID verify(HttpServletRequest request, String submittedCode) {
        UUID pendingUserId = pendingUserId(request)
                .orElseThrow(() -> new IllegalStateException("login.verify.noPendingLogin"));
        LoginVerificationCode latest = this.codes.findLatestForUser(pendingUserId)
                .orElseThrow(() -> new IllegalStateException("login.verify.noPendingLogin"));
        if (latest.consumedAt() != null) {
            throw new IllegalStateException("login.verify.noPendingLogin");
        }
        if (latest.attempts() >= MAX_ATTEMPTS) {
            throw new IllegalStateException("login.verify.tooManyAttempts");
        }
        if (this.clock.instant().isAfter(latest.expiresAt())) {
            throw new IllegalStateException("login.verify.expired");
        }
        if (submittedCode == null || !MessageDigest.isEqual(
                hash(latest.id(), submittedCode.strip()).getBytes(StandardCharsets.UTF_8),
                latest.codeHash().getBytes(StandardCharsets.UTF_8))) {
            this.codes.incrementAttempts(latest.id());
            throw new IllegalStateException("login.verify.invalidCode");
        }
        this.codes.markConsumed(latest.id(), this.clock.instant());
        clearPending(request);
        return pendingUserId;
    }

    public Optional<UUID> pendingUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object value = session.getAttribute(SESSION_KEY);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString((String) value));
        }
        catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public void clearPending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_KEY);
        }
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        return String.format("%0" + CODE_LENGTH + "d", this.random.nextInt(bound));
    }

    /** Ties the hash to its own row id, so a leaked hash cannot be replayed against another row. */
    private static String hash(UUID rowId, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest((rowId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        }
        catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a JDK-mandatory algorithm; this cannot happen on any conforming JVM.
            throw new IllegalStateException(ex);
        }
    }
}
