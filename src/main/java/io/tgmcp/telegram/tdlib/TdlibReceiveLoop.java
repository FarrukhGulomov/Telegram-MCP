package io.tgmcp.telegram.tdlib;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one thread per process allowed to call {@code td_receive}.
 *
 * <p>Confirmed from live production logs: {@code td_receive} is not a per-client call -- unlike
 * the deprecated {@code td_json_client_*} API, the current {@code td_create_client_id}/{@code
 * td_send}/{@code td_receive} interface this codebase binds ({@link TdJsonLibrary}) multiplexes
 * every client a process has created through this one global, must-be-single-threaded function,
 * distinguishing them only by the {@code @client_id} field in each returned JSON object. Giving
 * every tenant's {@link NativeTdlibClient} its own private receive thread against the same
 * loaded native library -- the design {@link io.tgmcp.telegram.tdlib.RoutedTdlibTelegramGateway}
 * shipped with initially -- meant a second tenant connecting started a second thread calling
 * {@code td_receive} concurrently with the first, which TDLib's own internal check answered with
 * a native {@code abort()} (logged as "Receive must not be called simultaneously from two
 * different threads"), killing the whole JVM process, not just that request. One {@code
 * TdlibReceiveLoop}, shared by every tenant's client, is how this codebase now upholds that
 * constraint while still letting each tenant have its own {@code td_create_client_id}.
 */
final class TdlibReceiveLoop {

    private static final Logger log = LoggerFactory.getLogger(TdlibReceiveLoop.class);
    private static final double RECEIVE_TIMEOUT_SECONDS = 1.0;

    private final TdJsonLibrary library;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Integer, Consumer<JsonNode>> dispatchersByClientId = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Thread receiveThread = new Thread(this::loop, "tdlib-receive");

    TdlibReceiveLoop(TdJsonLibrary library, ObjectMapper objectMapper) {
        this.library = library;
        this.objectMapper = objectMapper;
        this.receiveThread.setDaemon(true);
        // Real-account testing found TDLib's default verbosity (5) floods the process's stdout
        // with FileManager/ReactionManager internals -- enough, under Railway, to hit its log
        // ingestion rate limit and start silently DROPPING messages ("Railway rate limit
        // reached ... Messages dropped: 380"), which could just as easily swallow a genuine
        // error as this noise, and cannot be told apart from one after the fact. td_execute is
        // TDLib's documented synchronous, client-id-free path for setLogVerbosityLevel, and
        // this is process-wide state -- set once here, not per tenant. Level 1 keeps fatal
        // errors and errors, dropping the info/debug chatter this codebase's own error handling
        // never relied on reading from stdout in the first place.
        library.td_execute("{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":1}");
    }

    /** Idempotent: the first client to attach starts the loop, every later one is a no-op. */
    void start() {
        if (this.started.compareAndSet(false, true)) {
            this.receiveThread.start();
        }
    }

    int createClientId() {
        return this.library.td_create_client_id();
    }

    void send(int clientId, String request) {
        this.library.td_send(clientId, request);
    }

    void register(int clientId, Consumer<JsonNode> dispatcher) {
        this.dispatchersByClientId.put(clientId, dispatcher);
    }

    /** See {@code NativeTdlibClient}'s {@code clientId} javadoc: a dead id is replaced, not reused. */
    void reregister(int oldClientId, int newClientId, Consumer<JsonNode> dispatcher) {
        this.dispatchersByClientId.remove(oldClientId);
        this.dispatchersByClientId.put(newClientId, dispatcher);
    }

    void unregister(int clientId) {
        this.dispatchersByClientId.remove(clientId);
    }

    private void loop() {
        while (this.running.get()) {
            String raw;
            try {
                raw = this.library.td_receive(RECEIVE_TIMEOUT_SECONDS);
            }
            catch (RuntimeException ex) {
                log.error("TDLib receive failed", ex);
                continue;
            }
            if (raw == null) {
                continue;
            }
            JsonNode node;
            try {
                node = this.objectMapper.readTree(raw);
            }
            catch (RuntimeException ex) {
                log.warn("Unparseable TDLib payload discarded");
                continue;
            }
            int clientId = node.path("@client_id").asInt(0);
            Consumer<JsonNode> dispatcher = this.dispatchersByClientId.get(clientId);
            if (dispatcher != null) {
                dispatcher.accept(node);
            }
            // else: a response/update for a client id nothing is listening for any more (e.g. one
            // that unregistered during shutdown) -- safe to drop.
        }
    }

    void shutdown() {
        this.running.set(false);
        this.receiveThread.interrupt();
    }
}
