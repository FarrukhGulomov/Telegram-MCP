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
 * <p>QR-code login is accepted only when the typed phone number matches no existing account.
 * Reason: unlike the phone-number method, where Telegram itself only ever delivers a code to
 * the real owner of that number, a QR scan can be completed by <em>any</em> Telegram account
 * regardless of what was typed. If an already-registered phone number's tenant were reused for
 * a QR attempt, whoever scans it -- not whoever typed the phone number -- would end up signed
 * into that tenant's existing dashboard and OAuth-connected clients: a full account takeover.
 * Restricting QR to brand-new tenants means there is never any existing data or session for a
 * mismatched scan to inherit.
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

    /** What this request's session is one completed login away from becoming. */
    private record Pending(UUID tenantId, UUID userId, String phoneNumber, boolean newAccount)
            implements Serializable {
    }

    public static String normalizePhoneNumber(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9+]", "").strip();
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
            if (method == LoginMethod.QR_CODE) {
                throw new IllegalArgumentException("login.qrNotForExistingAccount");
            }
            actor = actorFor(existing.get());
            this.connectionService.prepareForFreshLogin(actor);
            newAccount = false;
        }
        else {
            AppUser user = createAccount(phoneNumber);
            actor = actorFor(user);
            newAccount = true;
        }
        request.getSession(true).setAttribute(SESSION_KEY,
                new Pending(actor.tenantId(), actor.userId(), phoneNumber, newAccount));
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
     * Called once the poll in {@code /login/status} (or a synchronous {@code /login/code} or
     * {@code /login/password} response) reports {@code READY}: reconciles the account's
     * identity with whatever Telegram's own login just confirmed, then establishes the
     * session. Never trusts the phone number originally typed on its own -- {@link
     * TelegramConnection#tgPhoneNumber()} (from TDLib's {@code getMe}) always wins when present.
     *
     * @return {@code true} once signed in; {@code false} if the login has not reached READY yet
     */
    public boolean finishIfReady(HttpServletRequest request, HttpServletResponse response) {
        Pending pending = pending(request).orElse(null);
        if (pending == null) {
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
