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
     */
    private record Pending(UUID tenantId, UUID userId, String phoneNumber, boolean newAccount,
                           LoginMethod method, String finishNonce) implements Serializable {
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
     * Telegram login against it. For a returning account, {@link
     * TelegramConnectionService#prepareForFreshLogin} always runs first: Telegram has no way to
     * re-verify a phone number that already owns an authorized session, so a fresh code can
     * only ever be requested after ending whichever one exists -- see the class javadoc for why
     * this must never pause the account's already-activated chats.
     */
    public LoginState begin(HttpServletRequest request, LoginMethod method, String rawPhoneNumber) {
        String phoneNumber = normalizePhoneNumber(rawPhoneNumber);
        if (phoneNumber.isBlank()) {
            throw new IllegalArgumentException("login.phoneRequired");
        }
        Optional<AppUser> existing = this.users.findByPhoneNumber(phoneNumber);
        AuthenticatedActor actor;
        boolean newAccount;
        if (existing.isPresent()) {
            AppUser existingUser = existing.get();
            if (method == LoginMethod.QR_CODE && existingUser.telegramUserId() == null) {
                // No previously confirmed identity to check a scan against yet -- see the class
                // javadoc for why that specifically, not merely "an account already exists",  is
                // what QR needs before it can be trusted for a returning number.
                throw new IllegalArgumentException("login.qrNotForExistingAccount");
            }
            actor = actorFor(existingUser);
            this.connectionService.prepareForFreshLogin(actor);
            newAccount = false;
        }
        else {
            AppUser user = createAccount(phoneNumber);
            actor = actorFor(user);
            newAccount = true;
        }
        request.getSession(true).setAttribute(SESSION_KEY,
                new Pending(actor.tenantId(), actor.userId(), phoneNumber, newAccount, method,
                        UUID.randomUUID().toString()));
        return this.connectionService.beginLogin(actor, method,
                method == LoginMethod.PHONE_NUMBER ? phoneNumber : null);
    }

    public LoginState submitCode(HttpServletRequest request, String code) {
        return this.connectionService.submitCode(requirePendingActor(request), code);
    }

    public LoginState resendCode(HttpServletRequest request) {
        return this.connectionService.resendCode(requirePendingActor(request));
    }

    public LoginState submitPassword(HttpServletRequest request, String password) {
        return this.connectionService.submitPassword(requirePendingActor(request), password);
    }

    public LoginState status(HttpServletRequest request) {
        return this.connectionService.status(requirePendingActor(request));
    }

    /**
     * Gives up on this login attempt: a brand-new account that never reached a real login is
     * simply discarded (nothing of value was ever created), while a returning account's
     * abandoned fresh-login attempt is disconnected properly through {@link
     * TelegramConnectionService#disconnect} -- unlike {@link #begin}'s own teardown, this really
     * is the end of the story, so its chats are paused exactly as any other disconnect's are.
     */
    public void cancel(HttpServletRequest request) {
        pending(request).ifPresent(pending -> {
            AuthenticatedActor actor = new AuthenticatedActor(pending.tenantId(), pending.userId(),
                    pending.phoneNumber(), ActorType.UI_USER, Set.of("ui:manage"), null);
            if (pending.newAccount()) {
                this.tenants.delete(pending.tenantId());
            }
            else {
                this.connectionService.disconnect(actor);
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
        AuthenticatedActor actor = new AuthenticatedActor(pending.tenantId(), pending.userId(),
                pending.phoneNumber(), ActorType.UI_USER, Set.of("ui:manage"), null);
        LoginState state = this.connectionService.status(actor);
        if (!state.ready()) {
            return false;
        }
        TelegramConnection connection = this.connectionService.find(actor).orElseThrow();
        String confirmedPhoneNumber = connection.tgPhoneNumber() != null
                ? connection.tgPhoneNumber() : pending.phoneNumber();

        if (!pending.newAccount() && pending.method() == LoginMethod.QR_CODE) {
            // Deliberately scoped to QR only: a PHONE_NUMBER re-login is already Telegram's own
            // verification of this exact number (a code delivered to it), and the mock adapter's
            // (and, in principle, any adapter's) own tgUserId for a fresh connection need not
            // literally equal whatever value happened to be on file from a previous session --
            // there is nothing here worth double-guessing. QR is different: begin() only allows
            // it for a returning number once this account already has a confirmed identity on
            // file (see its own doc) specifically so this check has something trustworthy to
            // verify the scan against.
            Long previouslyConfirmedTelegramUserId = this.users.findById(pending.userId())
                    .map(AppUser::telegramUserId).orElse(null);
            if (previouslyConfirmedTelegramUserId != null
                    && !previouslyConfirmedTelegramUserId.equals(connection.tgUserId())) {
                // A scan can be completed by any Telegram account regardless of what phone
                // number was typed to reach it, so this is exactly the account-takeover attempt
                // the class javadoc describes: reject it outright, leaving the existing
                // account's own identity and session completely untouched (unlike the
                // DuplicateKeyException case below, there is no "other account" row to roll
                // back -- this tenant's own data was never at risk of being overwritten, only of
                // being handed to the wrong person).
                this.connectionService.disconnect(actor);
                clearPending(request);
                throw new IllegalStateException("login.qrScanDidNotMatchAccount");
            }
        }
        try {
            this.users.updateTelegramIdentity(pending.tenantId(), pending.userId(), confirmedPhoneNumber,
                    connection.tgUserId(), connection.tgUsername());
        }
        catch (DuplicateKeyException ex) {
            // Confirmed identity belongs to a different, already-registered account (only
            // reachable for a brand-new QR signup whose typed phone number matched nothing, but
            // whose actual scanning Telegram account turns out to already have its own tenant
            // under a different number). Roll back rather than silently reassigning either
            // account's identity.
            this.connectionService.disconnect(actor);
            if (pending.newAccount()) {
                this.tenants.delete(pending.tenantId());
            }
            clearPending(request);
            throw new IllegalStateException("login.accountAlreadyLinked");
        }
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

    private AuthenticatedActor requirePendingActor(HttpServletRequest request) {
        Pending pending = pending(request)
                .orElseThrow(() -> new IllegalStateException("login.noPendingLogin"));
        return new AuthenticatedActor(pending.tenantId(), pending.userId(), pending.phoneNumber(),
                ActorType.UI_USER, Set.of("ui:manage"), null);
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
