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

    private TdlibTelegramGateway forTenant(UUID tenantId) {
        return this.gatewaysByTenant.computeIfAbsent(tenantId, id -> {
            log.info("Starting a new TDLib client for tenant {}", id);
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
     * Security-audit finding H3: {@link #forTenant} above never evicts a real tenant's client --
     * a documented, accepted per-tenant cost for an account actually using this deployment. A
     * tenant reaped as an abandoned signup ({@link io.tgmcp.tenancy.AbandonedSignupReaper}) or
     * discarded on an explicit {@code /login/cancel} for a brand-new account is different: its
     * database row is gone, so its client is pure waste from this point on, and an anonymous
     * caller could otherwise keep creating more of them without limit. Removes it from the
     * routing map (so no later call can reach it) and closes it (releasing the JVM-side
     * resources {@link TdlibTelegramGateway#close} describes) before deleting its on-disk
     * session directory.
     */
    @Override
    public void discardTenant(UUID tenantId) {
        TdlibTelegramGateway gateway = this.gatewaysByTenant.remove(tenantId);
        if (gateway != null) {
            gateway.close();
        }
        File directory = new File(this.baseConfig.databaseDirectory(), tenantId.toString());
        if (!FileSystemUtils.deleteRecursively(directory)) {
            log.warn("Could not fully delete TDLib session directory for discarded tenant {} at {}",
                    tenantId, directory);
        }
    }

    @Override
    public LoginState beginLogin(ConnectionRef ref, LoginMethod method, String phoneNumber) {
        return forTenant(ref.tenantId()).beginLogin(ref, method, phoneNumber);
    }

    @Override
    public LoginState submitCode(ConnectionRef ref, String code) {
        return forTenant(ref.tenantId()).submitCode(ref, code);
    }

    @Override
    public LoginState resendCode(ConnectionRef ref) {
        return forTenant(ref.tenantId()).resendCode(ref);
    }

    @Override
    public LoginState submitPassword(ConnectionRef ref, String password) {
        return forTenant(ref.tenantId()).submitPassword(ref, password);
    }

    @Override
    public LoginState currentState(ConnectionRef ref) {
        return forTenant(ref.tenantId()).currentState(ref);
    }

    @Override
    public void onStateChange(ConnectionRef ref, Consumer<LoginState> listener) {
        forTenant(ref.tenantId()).onStateChange(ref, listener);
    }

    @Override
    public void disconnect(ConnectionRef ref) {
        forTenant(ref.tenantId()).disconnect(ref);
    }

    @Override
    public List<RemoteChat> listAvailableChats(ConnectionRef ref) {
        return forTenant(ref.tenantId()).listAvailableChats(ref);
    }

    @Override
    public RemoteMembership listParticipants(ConnectionRef ref, long tgChatId) {
        return forTenant(ref.tenantId()).listParticipants(ref, tgChatId);
    }

    @Override
    public RemoteMessagePage fetchMessages(ConnectionRef ref, long tgChatId, MessageWindow window) {
        return forTenant(ref.tenantId()).fetchMessages(ref, tgChatId, window);
    }

    @Override
    public List<RemoteMessage> fetchRevisions(ConnectionRef ref, long tgChatId, Instant changedSince, int limit) {
        return forTenant(ref.tenantId()).fetchRevisions(ref, tgChatId, changedSince, limit);
    }

    @Override
    public Optional<String> messageLink(ConnectionRef ref, RemoteChat chat, long tgMessageId) {
        return forTenant(ref.tenantId()).messageLink(ref, chat, tgMessageId);
    }

    @Override
    public Optional<DownloadedMedia> downloadMessageMedia(ConnectionRef ref, String mediaRemoteFileId) {
        return forTenant(ref.tenantId()).downloadMessageMedia(ref, mediaRemoteFileId);
    }
}
