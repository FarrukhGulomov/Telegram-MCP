package io.tgmcp.telegram.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The only way the rest of the application talks to Telegram.
 *
 * <p>The interface is deliberately read-only: there is no operation to send, edit or delete
 * a message, to join or leave a chat, or to change anything about the account. Read-only
 * behaviour is therefore a property of the type, not only of the calling code — a future
 * adapter physically cannot expose a write path through this port.
 *
 * <p>Implementations must translate their own failure modes into the
 * {@link TelegramGatewayException} hierarchy so that callers can distinguish
 * "wait and retry" from "stop and ask a human".
 */
public interface TelegramGateway {

    TelegramAdapterKind kind();

    // --- session lifecycle (management UI only; never reachable from MCP) -------------

    LoginState beginLogin(ConnectionRef ref, LoginMethod method, String phoneNumber);

    LoginState submitCode(ConnectionRef ref, String code);

    /** Two-factor authentication password step. */
    LoginState submitPassword(ConnectionRef ref, String password);

    LoginState currentState(ConnectionRef ref);

    /**
     * Registers a listener notified whenever this connection's login state changes,
     * including asynchronously, after this call returns (security-audit finding F13): QR-code
     * confirmation happens out of band (the user scans on another device), so a request/response
     * call made at the moment {@code beginLogin} is invoked cannot observe it. Adapters whose
     * every transition instead completes synchronously within their own request/response
     * methods (the mock adapter) may leave this a no-op default -- callers that also persist
     * the state returned directly from beginLogin/submitCode/submitPassword still see those.
     */
    default void onStateChange(ConnectionRef ref, java.util.function.Consumer<LoginState> listener) {
    }

    /**
     * Ends the local client session and destroys local session material. This does not by
     * itself revoke the session inside Telegram; the UI tells users how to do that.
     */
    void disconnect(ConnectionRef ref);

    // --- read-only data access --------------------------------------------------------

    /** Chats visible to the connected account. Nothing is synced until a chat is activated. */
    List<RemoteChat> listAvailableChats(ConnectionRef ref);

    List<RemoteParticipant> listParticipants(ConnectionRef ref, long tgChatId);

    /** Bounded read. Implementations must honour {@link MessageWindow#limit()}. */
    RemoteMessagePage fetchMessages(ConnectionRef ref, long tgChatId, MessageWindow window);

    /**
     * Messages that were edited or deleted since {@code changedSince}, so that already
     * ingested rows can be corrected. Deleted messages come back with
     * {@link RemoteMessage#deleted()} set.
     */
    List<RemoteMessage> fetchRevisions(ConnectionRef ref, long tgChatId, Instant changedSince, int limit);

    /**
     * A deep link to the original message, or empty when no valid public link exists.
     * Implementations must never synthesise a link they cannot vouch for.
     */
    Optional<String> messageLink(RemoteChat chat, long tgMessageId);
}
