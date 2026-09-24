package io.tgmcp.security;

import java.io.Serializable;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import io.tgmcp.common.Ids;
import io.tgmcp.tenancy.ActorType;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.tenancy.Tenant;
import io.tgmcp.tenancy.TenantRepository;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionService;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginState;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The whole account lifecycle collapses into one act: signing in <em>is</em> connecting
 * Telegram. There is no separate registration step, no password and no identity string to
 * type -- an account is looked up (or, on first use, created) by the phone number entered
 * here, but only Telegram's own login (the code it sends, and the two-factor password if one
 * is set) ever proves who actually holds it.
 *
 * <p>QR-code login for an already-registered phone number is accepted only when that account
 * has previously completed a real, code-verified login -- i.e. {@link AppUser#telegramUserId()}
 * is already on file. Reason: unlike the phone-number method, where Telegram itself only ever
 * delivers a code to the real owner of that number, a QR scan can be completed by <em>any</em>
 * Telegram account regardless of what phone number was typed to reach it. Requiring a
 * previously confirmed identity gives {@link #finishIfReady} something trustworthy to check
 * the scan against: a scan that resolves to a <em>different</em> Telegram account than the one
 * already on file is rejected outright (see there), leaving the existing account's identity and
 * session completely untouched -- exactly the account-takeover attempt this guard exists to
 * stop. A phone number with no confirmed identity yet (never signed in for real, or an
 * abandoned signup) has nothing trustworthy on file to check against, so QR remains
 * phone-code-only for it, same as before.
 */
@Service
public class PreAuthLoginService {

    static final String SESSION_KEY = "tgmcp.pendingPreAuthLogin";

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final TelegramConnectionService connectionService;
    private final ManualSessionEstablisher sessionEstablisher;

    public PreAuthLoginService(TenantRepository tenants, AppUserRepository users,
                               TelegramConnectionService connectionService,
                               ManualSessionEstablisher sessionEstablisher) {
        this.tenants = tenants;
        this.users = users;
        this.connectionService = connectionService;
        this.sessionEstablisher = sessionEstablisher;
    }

    /**
     * What this request's session is one completed login away from becoming.
     *
     * @param finishNonce security-audit finding SEC-R07: a single-use token minted for this
     *                    one login attempt, required by {@link #finishIfReady(HttpServletRequest,
     *                    HttpServletResponse, String)} before it will actually sign anyone in.
     *                    {@code /login/finish} is reached asynchronously (client-side polling
     *                    notices a QR scan confirmed READY out of band, on another device), so
     *                    unlike every other state-changing step here it is never the direct
     *                    result of a form the visitor just submitted -- a bare POST with a valid
     *                    CSRF token would otherwise be everything a same-site script or a
     *                    stray automated request needed to complete a sign-in it was never shown
     *                    happening. Handed back only through {@code /login/status}'s own JSON
     *                    response, and only once the stage it reports actually is READY.
     * @param loginSlot   security-audit finding H1: non-null exactly when this attempt is a
     *                    fresh login for an <em>already-registered</em> account (never for a
     *                    brand-new signup, which has no existing session worth protecting). Every
     *                    gateway call this attempt makes -- {@link #begin} through {@link
     *                    #finishIfReady} -- is routed through {@link
     *                    TelegramConnectionService}'s shadow-login methods using this value as the
     *                    isolated routing key, so the account's real session (if it has one) is
     *                    never touched unless and until this attempt is confirmed good and
     *                    promoted.
     */
    private record Pending(UUID tenantId, UUID userId, String phoneNumber, boolean newAccount,
                           LoginMethod method, String finishNonce, UUID loginSlot) implements Serializable {
    }

    /**
     * Canonicalizes to "+&lt;digits&gt;" (adding the leading "+" when a visitor left it off) so
     * that a returning account's own {@link AppUserRepository#findByPhoneNumber} lookup -- an
     * exact string match -- keeps matching however Telegram's own login later reports it: TDLib
     * (see {@code TdlibTelegramGateway.withAccountIdentity}) reports {@code getMe().phone_number}
     * as bare digits with no "+", and once that is written back to the account (see {@link
     * #finishIfReady}), a subsequent visit typing the number without a "+" of their own must
     * still resolve to the same, already-registered account rather than colliding with it while
     * trying to create a second one.
     */
    public static String normalizePhoneNumber(String raw) {
        if (raw == null) {
            return "";
        }
        String digitsAndPlus = raw.replaceAll("[^0-9+]", "").strip();
        return digitsAndPlus.isEmpty() || digitsAndPlus.startsWith("+")
                ? digitsAndPlus : "+" + digitsAndPlus;
    }

    /**
     * Looks up (or creates) the account this phone number belongs to, then starts the real
     * Telegram login against it.
     *
     * <p>Security-audit finding H1: a returning account's fresh login used to run {@link
     * TelegramConnectionService#prepareForFreshLogin} first, tearing down whichever session that
     * account already held -- before this request had proven anything at all. Anyone who merely
     * knew a victim's phone number could disconnect their live session and pause every chat it
     * had activated, without ever completing (or even intending to complete) a login of their
     * own. This now mints a fresh {@code loginSlot} for any returning account instead and runs
     * the entire attempt through {@link TelegramConnectionService}'s shadow-login methods (see
     * {@link Pending#loginSlot} for what that isolates); the account's real session is adopted
     * only once {@link #finishIfReady} has confirmed the new one is genuinely the same account.
     */
    public LoginState begin(HttpServletRequest request, LoginMethod method, String rawPhoneNumber) {
        String phoneNumber = normalizePhoneNumber(rawPhoneNumber);
        if (phoneNumber.isBlank()) {
            throw new IllegalArgumentException("login.phoneRequired");
        }
        Optional<AppUser> existing = this.users.findByPhoneNumber(phoneNumber);
        AuthenticatedActor actor;
        boolean newAccount;
        UUID loginSlot;
        if (existing.isPresent()) {
            AppUser existingUser = existing.get();
            if (method == LoginMethod.QR_CODE && existingUser.telegramUserId() == null) {
                // No previously confirmed identity to check a scan against yet -- see the class
                // javadoc for why that specifically, not merely "an account already exists",  is
                // what QR needs before it can be trusted for a returning number.
                throw new IllegalArgumentException("login.qrNotForExistingAccount");
            }
            actor = actorFor(existingUser);
            newAccount = false;
            loginSlot = Ids.newId();
        }
        else {
            AppUser user = createAccount(phoneNumber);
            actor = actorFor(user);
            newAccount = true;
            loginSlot = null;
        }
        request.getSession(true).setAttribute(SESSION_KEY,
                new Pending(actor.tenantId(), actor.userId(), phoneNumber, newAccount, method,
                        UUID.randomUUID().toString(), loginSlot));
        String tgPhoneNumber = method == LoginMethod.PHONE_NUMBER ? phoneNumber : null;
        return loginSlot != null
                ? this.connectionService.beginShadowLogin(actor, method, tgPhoneNumber, loginSlot)
                : this.connectionService.beginLogin(actor, method, tgPhoneNumber);
    }

    public LoginState submitCode(HttpServletRequest request, String code) {
        Pending pending = requirePending(request);
        return pending.loginSlot() != null
                ? this.connectionService.submitShadowCode(actorFor(pending), pending.loginSlot(), code)
                : this.connectionService.submitCode(actorFor(pending), code);
    }

    public LoginState resendCode(HttpServletRequest request) {
        Pending pending = requirePending(request);
        return pending.loginSlot() != null
                ? this.connectionService.resendShadowCode(actorFor(pending), pending.loginSlot())
                : this.connectionService.resendCode(actorFor(pending));
    }

    public LoginState submitPassword(HttpServletRequest request, String password) {
        Pending pending = requirePending(request);
        return pending.loginSlot() != null
                ? this.connectionService.submitShadowPassword(actorFor(pending), pending.loginSlot(), password)
                : this.connectionService.submitPassword(actorFor(pending), password);
    }

    public LoginState status(HttpServletRequest request) {
        Pending pending = requirePending(request);
        return pending.loginSlot() != null
                ? this.connectionService.shadowStatus(pending.loginSlot())
                : this.connectionService.status(actorFor(pending));
    }

    /**
     * Gives up on this login attempt: a brand-new account that never reached a real login is
     * simply discarded (nothing of value was ever created).
     *
     * <p>Security-audit finding H1: a returning account's abandoned fresh-login attempt used to
     * call {@link TelegramConnectionService#disconnect}, ending whatever real session the account
     * already held and pausing every chat it had activated -- exactly the disruption {@link
     * #begin}'s own fix exists to prevent, just reachable a step later by cancelling instead of
     * completing. Now that such an attempt runs entirely as an isolated shadow session (see
     * {@link Pending#loginSlot}), cancelling it only ever discards that shadow attempt; the
     * account's real session, if it has one, is left exactly as it was.
     */
    public void cancel(HttpServletRequest request) {
        pending(request).ifPresent(pending -> {
            if (pending.newAccount()) {
                // Security-audit finding H3: deleting only the database row left this tenant's
                // TDLib client (if beginLogin had already started one) resident for the life of
                // the JVM, exactly like an abandoned signup no one ever explicitly cancelled.
                this.connectionService.discardAbandonedSignup(pending.tenantId());
                this.tenants.delete(pending.tenantId());
            }
            else if (pending.loginSlot() != null) {
                this.connectionService.discardShadowLogin(pending.loginSlot());
            }
            else {
                // Should not happen -- begin() now mints a loginSlot for every returning-account
                // login -- but fall back to a real, graceful disconnect rather than silently
                // leaving a session neither adopted nor cleaned up.
                this.connectionService.disconnect(actorFor(pending));
            }
        });
        clearPending(request);
    }

    /**
     * The current login attempt's single-use finish nonce, so {@code /login/status} can hand it
     * back to the client -- see {@link Pending#finishNonce} for why {@code /login/finish} needs
     * one at all. Absent whenever there is no pending login (nothing to finish) exactly like
     * every other {@code pending(request)}-backed accessor.
     */
    public Optional<String> pendingFinishNonce(HttpServletRequest request) {
        return pending(request).map(Pending::finishNonce);
    }

    /**
     * As {@link #finishIfReady(HttpServletRequest, HttpServletResponse, String)}, for the
     * synchronous completions right after {@code /login/code}/{@code /login/password}/{@code
     * /login/start} report READY within the very same request: no nonce to check, since
     * completing there is already gated by that request's own CSRF-protected form submission.
     */
    public boolean finishIfReady(HttpServletRequest request, HttpServletResponse response) {
        return finishIfReady(request, response, null);
    }

    /**
     * Called once the poll in {@code /login/status} (or a synchronous {@code /login/code} or
     * {@code /login/password} response) reports {@code READY}: reconciles the account's
     * identity with whatever Telegram's own login just confirmed, then establishes the
     * session. Never trusts the phone number originally typed on its own -- {@link
     * TelegramConnection#tgPhoneNumber()} (from TDLib's {@code getMe}) always wins when present.
     *
     * @param requiredNonce security-audit finding SEC-R07: when non-null, must exactly match
     *                      this login's own {@link Pending#finishNonce} or nothing happens (same
     *                      as no pending login at all) -- the asynchronous {@code /login/finish}
     *                      endpoint always passes one; the synchronous overload above never does.
     * @return {@code true} once signed in; {@code false} if the login has not reached READY yet
     *         or the supplied nonce does not match
     */
    public boolean finishIfReady(HttpServletRequest request, HttpServletResponse response, String requiredNonce) {
        Pending pending = pending(request).orElse(null);
        if (pending == null) {
            return false;
        }
        if (requiredNonce != null && !requiredNonce.equals(pending.finishNonce())) {
            return false;
        }
        return pending.loginSlot() != null
                ? finishShadowLoginIfReady(request, response, pending)
                : finishNewAccountLoginIfReady(request, response, pending);
    }

    /**
     * The original, unshadowed finish path -- reachable only for a brand-new account (see {@link
     * Pending#loginSlot}), which has no existing session for a mismatched or duplicate identity
     * to endanger, so working directly against this connection's own row (rather than an
     * isolated shadow one) is safe.
     */
    private boolean finishNewAccountLoginIfReady(HttpServletRequest request, HttpServletResponse response,
                                                 Pending pending) {
        AuthenticatedActor actor = actorFor(pending);
        LoginState state = this.connectionService.status(actor);
        if (!state.ready()) {
            return false;
        }
        TelegramConnection connection = this.connectionService.find(actor).orElseThrow();
        String confirmedPhoneNumber = connection.tgPhoneNumber() != null
                ? connection.tgPhoneNumber() : pending.phoneNumber();
        try {
            this.users.updateTelegramIdentity(pending.tenantId(), pending.userId(), confirmedPhoneNumber,
                    connection.tgUserId(), connection.tgUsername());
        }
        catch (DuplicateKeyException ex) {
            // Confirmed identity belongs to a different, already-registered account (a brand-new
            // QR signup whose typed phone number matched nothing, but whose actual scanning
            // Telegram account turns out to already have its own tenant under a different
            // number). Roll back rather than silently reassigning either account's identity.
            this.connectionService.disconnect(actor);
            this.tenants.delete(pending.tenantId());
            clearPending(request);
            throw new IllegalStateException("login.accountAlreadyLinked");
        }
        AppUser user = this.users.findById(pending.userId()).orElseThrow();
        this.sessionEstablisher.signIn(user, request, response);
        clearPending(request);
        return true;
    }

    /**
     * Security-audit finding H1: the finish path for a returning account's fresh login, which
     * runs entirely against the isolated shadow session {@link #begin} started (see {@link
     * Pending#loginSlot}) -- identity comes from the shadow's own confirmed {@link LoginState},
     * never from this connection's persisted row, since nothing has been written there yet. Only
     * once the identity check below passes does {@link
     * TelegramConnectionService#promoteShadowLogin} adopt the shadow session as the account's
     * real one; every rejection path here discards only the shadow attempt (see {@link
     * TelegramConnectionService#discardShadowLogin}), leaving whatever real session the account
     * already had completely untouched.
     */
    private boolean finishShadowLoginIfReady(HttpServletRequest request, HttpServletResponse response,
                                             Pending pending) {
        LoginState shadowState = this.connectionService.shadowStatus(pending.loginSlot());
        if (!shadowState.ready()) {
            return false;
        }
        String confirmedPhoneNumber = shadowState.tgPhoneNumber() != null
                ? shadowState.tgPhoneNumber() : pending.phoneNumber();

        // Security-audit finding H5: originally scoped to QR only, on the reasoning that a
        // PHONE_NUMBER re-login is already Telegram's own verification of *this number*, so
        // nothing more was worth checking. That verifies who currently holds the number, not
        // who originally set up this account -- a carrier-recycled or otherwise reassigned
        // phone number lets its new, legitimate holder log in and silently inherit the
        // previous holder's entire tenant (its chats, its connected AI apps, and any
        // platform-admin grant keyed on this same phone number), with Telegram's own code
        // delivery offering no protection against that at all. A real Telegram user id never
        // changes for a given account, so once one has been confirmed on file, every login
        // method -- not only QR -- must reproduce it before this session is allowed to take
        // over that identity.
        Long previouslyConfirmedTelegramUserId = this.users.findById(pending.userId())
                .map(AppUser::telegramUserId).orElse(null);
        if (previouslyConfirmedTelegramUserId != null
                && !previouslyConfirmedTelegramUserId.equals(shadowState.tgUserId())) {
            // Reject outright, discarding only the shadow attempt -- the existing account's own
            // identity and (per the H1 fix above) its real session were never touched in the
            // first place, so there is nothing of its own left to roll back here.
            this.connectionService.discardShadowLogin(pending.loginSlot());
            clearPending(request);
            throw new IllegalStateException(pending.method() == LoginMethod.QR_CODE
                    ? "login.qrScanDidNotMatchAccount" : "login.phoneNumberNoLongerMatchesAccount");
        }
        try {
            this.users.updateTelegramIdentity(pending.tenantId(), pending.userId(), confirmedPhoneNumber,
                    shadowState.tgUserId(), shadowState.tgUsername());
        }
        catch (DuplicateKeyException ex) {
            // The confirmed identity belongs to a different, already-registered account. Unlike
            // finishNewAccountLoginIfReady's own handling of this, there is no tenant to roll
            // back (this one is the pre-existing account the visitor typed their way back into,
            // not a freshly created one) -- only the shadow attempt needs discarding.
            this.connectionService.discardShadowLogin(pending.loginSlot());
            clearPending(request);
            throw new IllegalStateException("login.accountAlreadyLinked");
        }
        this.connectionService.promoteShadowLogin(actorFor(pending), pending.loginSlot(), shadowState);
        AppUser user = this.users.findById(pending.userId()).orElseThrow();
        this.sessionEstablisher.signIn(user, request, response);
        clearPending(request);
        return true;
    }

    private AppUser createAccount(String phoneNumber) {
        UUID tenantId = Ids.newId();
        UUID userId = Ids.newId();
        String slug = phoneNumber.replaceAll("[^0-9]", "");
        this.tenants.insert(new Tenant(tenantId, slug, phoneNumber, false, null));
        AppUser user = new AppUser(userId, tenantId, phoneNumber, phoneNumber, "OWNER", true, null, null);
        this.users.insert(user);
        return user;
    }

    private static AuthenticatedActor actorFor(AppUser user) {
        return new AuthenticatedActor(user.tenantId(), user.id(), user.phoneNumber(), ActorType.UI_USER,
                Set.of("ui:manage"), null);
    }

    private static AuthenticatedActor actorFor(Pending pending) {
        return new AuthenticatedActor(pending.tenantId(), pending.userId(), pending.phoneNumber(),
                ActorType.UI_USER, Set.of("ui:manage"), null);
    }

    private Pending requirePending(HttpServletRequest request) {
        return pending(request).orElseThrow(() -> new IllegalStateException("login.noPendingLogin"));
    }

    private Optional<Pending> pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object value = session.getAttribute(SESSION_KEY);
        return value instanceof Pending pending ? Optional.of(pending) : Optional.empty();
    }

    public void clearPending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_KEY);
        }
    }
}
