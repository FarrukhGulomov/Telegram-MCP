package io.tgmcp.telegram.tdlib;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.sun.jna.Native;
import io.tgmcp.config.AppProperties;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.DownloadedMedia;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.MessageWindow;
import io.tgmcp.telegram.gateway.RemoteChat;
import io.tgmcp.telegram.gateway.RemoteMembership;
import io.tgmcp.telegram.gateway.RemoteMessage;
import io.tgmcp.telegram.gateway.RemoteMessagePage;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Routes every {@link TelegramGateway} call to a tenant's own {@link TdlibTelegramGateway},
 * creating one lazily on first use.
 *
 * <p>This replaces the previous single-shared-native-client design (security-audit finding
 * SEC-01, {@code TelegramConnectionService.requireNoOtherActiveOwner}): that design accepted
 * that at most one tenant, cluster-wide, could hold a live TDLIB connection at a time, and
 * rejected every other tenant's login attempt outright. Each tenant now gets its own native
 * TDLib client (TDLib's {@code td_create_client_id} is explicitly designed to support many
 * concurrent clients in one process) with its own on-disk session directory under {@link
 * AppProperties.Telegram#databaseDirectory()}, so tenants can never observe or disturb one
 * another's Telegram session.
 *
 * <p>Confirmed from live production logs: {@code td_receive} is a process-global call, not a
 * per-client one (see {@link TdlibReceiveLoop}'s javadoc) -- so the native library is loaded and
 * its one receive thread started exactly once here, in this gateway's constructor, and every
 * tenant's {@link NativeTdlibClient} shares that same {@link TdlibReceiveLoop} rather than each
 * starting its own. A second tenant's client used to start a second thread calling {@code
 * td_receive} against the same loaded library, which TDLib's own internal check answered with a
 * native {@code abort()}, crashing the whole process.
 *
 * <p>Clients are created lazily and never evicted -- a tenant's native client lives for the
 * life of the JVM once created, mirroring the previous single-instance lifecycle. Each connected
 * tenant therefore costs its own native TDLib session (memory, on-disk database, and its own
 * {@code td_create_client_id} actor, though not its own receive thread), which is a real
 * per-tenant resource cost operators should size the deployment for.
 */
public class RoutedTdlibTelegramGateway implements TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(RoutedTdlibTelegramGateway.class);

    private final ConcurrentHashMap<UUID, TdlibTelegramGateway> gatewaysByTenant = new ConcurrentHashMap<>();
    private final AppProperties.Telegram baseConfig;
    private final String sessionEncryptionKey;
    private final ObjectMapper objectMapper;
    private final long requestTimeoutSeconds;
    private final TdlibReceiveLoop receiveLoop;

    public RoutedTdlibTelegramGateway(AppProperties.Telegram baseConfig, String sessionEncryptionKey,
                                      ObjectMapper objectMapper, String tdlibLibraryName,
                                      long requestTimeoutSeconds) {
        this.baseConfig = baseConfig;
        this.sessionEncryptionKey = sessionEncryptionKey;
        this.objectMapper = objectMapper;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        TdJsonLibrary library = Native.load(tdlibLibraryName, TdJsonLibrary.class);
        this.receiveLoop = new TdlibReceiveLoop(library, objectMapper);
        this.receiveLoop.start();
    }

    /**
     * Security-audit finding H1: keyed by {@link ConnectionRef#routingKey()}, not {@code
     * tenantId} directly -- for an ordinary call that key is the same thing (the compact
     * constructor {@link ConnectionRef} defaults it that way), but a shadow login attempt for an
     * already-registered account (see that field's own javadoc) deliberately routes here under a
     * freshly minted key instead, so it gets its own brand-new native client and on-disk
     * directory rather than reusing -- and disturbing -- whatever client already lives at that
     * account's real tenant id.
     */
    private TdlibTelegramGateway forRoutingKey(UUID routingKey) {
        return this.gatewaysByTenant.computeIfAbsent(routingKey, id -> {
            log.info("Starting a new TDLib client for routing key {}", id);
            AppProperties.Telegram tenantConfig = this.baseConfig
                    .withDatabaseDirectory(this.baseConfig.databaseDirectory() + "/" + id);
            TdlibClient client = new NativeTdlibClient(this.receiveLoop, this.objectMapper,
                    this.requestTimeoutSeconds);
            return new TdlibTelegramGateway(client, tenantConfig, this.sessionEncryptionKey);
        });
    }

    @Override
    public TelegramAdapterKind kind() {
        return TelegramAdapterKind.TDLIB;
    }

    /**
     * Security-audit finding H3: {@link #forRoutingKey} above never evicts a real tenant's
     * client -- a documented, accepted per-tenant cost for an account actually using this
     * deployment. A tenant reaped as an abandoned signup ({@link
     * io.tgmcp.tenancy.AbandonedSignupReaper}) or discarded on an explicit {@code /login/cancel}
     * for a brand-new account is different: its database row is gone, so its client is pure
     * waste from this point on, and an anonymous caller could otherwise keep creating more of
     * them without limit. Removes it from the routing map (so no later call can reach it) and
     * closes it (releasing the JVM-side resources {@link TdlibTelegramGateway#close} describes)
     * before deleting its on-disk session directory.
     *
     * <p>Security-audit finding H1: the same method also cleans up an abandoned or rejected
     * <em>shadow</em> login attempt (see {@link ConnectionRef#routingKey()}), called with the
     * shadow's routing key in place of a real tenant id -- the two are equally "some opaque key
     * nothing will ever finish or reclaim" from this method's point of view. That reuse is why
     * this asks the live instance itself for its own directory rather than only ever recomputing
     * one from the key: after a {@link #promoteSession}, the tenant's own key points at an
     * instance whose actual on-disk directory is still named after whatever routing key first
     * created it, not the tenant id it was promoted to.
     */
    @Override
    public void discardTenant(UUID routingKey) {
        TdlibTelegramGateway gateway = this.gatewaysByTenant.remove(routingKey);
        if (gateway != null) {
            closeAndDelete(routingKey, gateway);
        }
        File conventionalDirectory = new File(this.baseConfig.databaseDirectory(), routingKey.toString());
        if (conventionalDirectory.exists() && !FileSystemUtils.deleteRecursively(conventionalDirectory)) {
            log.warn("Could not fully delete TDLib session directory for discarded session {} at {}",
                    routingKey, conventionalDirectory);
        }
    }

    private void closeAndDelete(UUID routingKey, TdlibTelegramGateway gateway) {
        File ownDirectory = new File(gateway.databaseDirectory());
        gateway.close();
        if (!FileSystemUtils.deleteRecursively(ownDirectory)) {
            log.warn("Could not fully delete TDLib session directory for discarded session {} at {}",
                    routingKey, ownDirectory);
        }
    }

    /**
     * Security-audit finding H1: promotes a shadow login's already-open native client to become
     * the live client for {@code targetRoutingKey} -- a pure re-keying of {@link
     * #gatewaysByTenant}, touching no TDLib state at all. Whatever client previously lived at
     * {@code targetRoutingKey} (the account's own now-superseded session, if it had one) is
     * closed and its directory deleted, exactly as {@link #discardTenant} would.
     *
     * <p>Deliberately does not rename the promoted client's on-disk directory to match {@code
     * targetRoutingKey}: TDLib was already told its {@code database_directory} once, at {@code
     * setTdlibParameters} time, and keeps using that exact path for as long as this process
     * runs -- renaming the directory out from under a native client that may still have it open
     * risks it silently failing to find files it expects to be there. The directory keeps
     * whatever name the shadow attempt's own routing key gave it for the rest of this client's
     * life; {@link #discardTenant} already knows to ask a live instance for its own directory
     * rather than assume one from its current map key, which is what makes that safe.
     */
    @Override
    public void promoteSession(ConnectionRef shadowRef, UUID targetRoutingKey) {
        TdlibTelegramGateway shadow = this.gatewaysByTenant.remove(shadowRef.routingKey());
        if (shadow == null) {
            throw new IllegalStateException(
                    "No pending login session found to promote for routing key " + shadowRef.routingKey());
        }
        TdlibTelegramGateway previous = this.gatewaysByTenant.put(targetRoutingKey, shadow);
        if (previous != null) {
            closeAndDelete(targetRoutingKey, previous);
        }
    }

    @Override
    public LoginState beginLogin(ConnectionRef ref, LoginMethod method, String phoneNumber) {
        return forRoutingKey(ref.routingKey()).beginLogin(ref, method, phoneNumber);
    }

    @Override
    public LoginState submitCode(ConnectionRef ref, String code) {
        return forRoutingKey(ref.routingKey()).submitCode(ref, code);
    }

    @Override
    public LoginState resendCode(ConnectionRef ref) {
        return forRoutingKey(ref.routingKey()).resendCode(ref);
    }

    @Override
    public LoginState submitPassword(ConnectionRef ref, String password) {
        return forRoutingKey(ref.routingKey()).submitPassword(ref, password);
    }

    @Override
    public LoginState currentState(ConnectionRef ref) {
        return forRoutingKey(ref.routingKey()).currentState(ref);
    }

    @Override
    public void onStateChange(ConnectionRef ref, Consumer<LoginState> listener) {
        forRoutingKey(ref.routingKey()).onStateChange(ref, listener);
    }

    @Override
    public void disconnect(ConnectionRef ref) {
        forRoutingKey(ref.routingKey()).disconnect(ref);
    }

    @Override
    public List<RemoteChat> listAvailableChats(ConnectionRef ref) {
        return forRoutingKey(ref.routingKey()).listAvailableChats(ref);
    }

    @Override
    public RemoteMembership listParticipants(ConnectionRef ref, long tgChatId) {
        return forRoutingKey(ref.routingKey()).listParticipants(ref, tgChatId);
    }

    @Override
    public RemoteMessagePage fetchMessages(ConnectionRef ref, long tgChatId, MessageWindow window) {
        return forRoutingKey(ref.routingKey()).fetchMessages(ref, tgChatId, window);
    }

    @Override
    public List<RemoteMessage> fetchRevisions(ConnectionRef ref, long tgChatId, Instant changedSince, int limit) {
        return forRoutingKey(ref.routingKey()).fetchRevisions(ref, tgChatId, changedSince, limit);
    }

    @Override
    public Optional<String> messageLink(ConnectionRef ref, RemoteChat chat, long tgMessageId) {
        return forRoutingKey(ref.routingKey()).messageLink(ref, chat, tgMessageId);
    }

    @Override
    public Optional<DownloadedMedia> downloadMessageMedia(ConnectionRef ref, String mediaRemoteFileId) {
        return forRoutingKey(ref.routingKey()).downloadMessageMedia(ref, mediaRemoteFileId);
    }
}
