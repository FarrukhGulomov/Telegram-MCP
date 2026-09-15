package io.tgmcp.telegram.tdlib;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.sun.jna.Native;
import io.tgmcp.telegram.gateway.TelegramTransientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The only class in the application that talks to a native library.
 *
 * <p>One receive thread drains {@code td_receive} (which the header requires not be called
 * concurrently) and correlates responses by the documented {@code @extra} field. Everything
 * else -- update listeners -- is handed off to a single dedicated executor rather than run
 * inline on the receive thread.
 *
 * <p>Security-audit finding F11: a listener is allowed to call {@link #request} itself (this
 * adapter's own auth-state listener does, to send {@code setTdlibParameters} the moment TDLib
 * asks for it). Running listeners inline on the receive thread meant that nested call would
 * block waiting for a response that only the receive thread itself -- currently stuck inside
 * the listener -- could ever deliver, so initialization reliably timed out rather than
 * completing. Response correlation now uses {@link CompletableFuture} (no rendezvous race with
 * the old {@code SynchronousQueue.offer}, which could silently drop a response that arrived
 * before the caller started polling) and listener dispatch runs on its own single-threaded,
 * order-preserving executor, so the receive thread is always free to keep draining {@code
 * td_receive} no matter what a listener does.
 */
public class NativeTdlibClient implements TdlibClient {

    private static final Logger log = LoggerFactory.getLogger(NativeTdlibClient.class);
    private static final double RECEIVE_TIMEOUT_SECONDS = 1.0;

    private final TdJsonLibrary library;
    private final ObjectMapper objectMapper;
    /**
     * Not final: real-account testing found that TDLib answers {@code logOut} with
     * {@code authorizationStateLoggingOut} followed by {@code authorizationStateClosed} --
     * which does not mean "logged out, ready to log back in" but "this client's actor has
     * permanently stopped". Every request sent to a closed client's id fails immediately (in
     * practice: an {@code error} with code 500), forever, since nothing restarts that actor.
     * {@link #dispatch} reacts to {@code authorizationStateClosed} by minting a fresh id here
     * so the next login attempt bootstraps a new actor exactly like the very first one after
     * process start, instead of the adapter being permanently unusable after one disconnect.
     */
    private volatile int clientId;
    private final long requestTimeoutSeconds;

    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<JsonNode>> updateListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong extraSequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Thread receiveThread;
    private final ExecutorService listenerExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tdlib-listener");
                t.setDaemon(true);
                return t;
            });

    public NativeTdlibClient(String libraryName, ObjectMapper objectMapper, long requestTimeoutSeconds) {
        this(Native.load(libraryName, TdJsonLibrary.class), objectMapper, requestTimeoutSeconds);
    }

    NativeTdlibClient(TdJsonLibrary library, ObjectMapper objectMapper, long requestTimeoutSeconds) {
        this.library = library;
        this.objectMapper = objectMapper;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        // Real-account testing found TDLib's default verbosity (5) floods the process's stdout
        // with FileManager/ReactionManager internals -- enough, under Railway, to hit its log
        // ingestion rate limit and start silently DROPPING messages ("Railway rate limit
        // reached ... Messages dropped: 380"), which could just as easily swallow a genuine
        // error as this noise, and cannot be told apart from one after the fact. td_execute
        // (not td_send) is TDLib's documented synchronous path for setLogVerbosityLevel: it
        // needs no client id and is set before creating one. Level 1 keeps fatal errors and
        // errors, dropping the info/debug chatter this codebase's own error handling never
        // relied on reading from stdout in the first place.
        library.td_execute("{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":1}");
        this.clientId = library.td_create_client_id();
        this.receiveThread = new Thread(this::receiveLoop, "tdlib-receive");
        this.receiveThread.setDaemon(true);
    }

    /**
     * Starts draining {@code td_receive}. Deferred out of the constructor (security-audit
     * finding F11) so that every {@link #onUpdate} listener a caller intends to register is in
     * place first -- otherwise an update TDLib sends immediately after client creation (in
     * practice, the very first {@code updateAuthorizationState}) can arrive before any listener
     * exists to receive it. Idempotent: a second call is a no-op.
     */
    @Override
    public void start() {
        if (this.started.compareAndSet(false, true)) {
            this.receiveThread.start();
        }
    }

    @Override
    public JsonNode request(String method, Map<String, Object> params) {
        String extra = "req-" + this.extraSequence.incrementAndGet();
        Map<String, Object> payload = new LinkedHashMap<>(params);
        payload.put("@type", method);
        payload.put("@extra", extra);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        this.pending.put(extra, future);
        try {
            this.library.td_send(this.clientId, this.objectMapper.writeValueAsString(payload));
            JsonNode response = future.get(this.requestTimeoutSeconds, TimeUnit.SECONDS);
            if (TdlibErrors.isError(response)) {
                // Logged here, server-side only, rather than folded into the translated
                // exception's own message: TdlibErrorTranslationTests deliberately asserts the
                // raw upstream text (which can carry chat/user identifiers) never reaches
                // callers through the exception itself. Quieting TDLib's native verbosity
                // elsewhere in this class removed the only other way to ever see this text.
                log.warn("TDLib request '{}' failed with code {}: {}", method,
                        response.path("code").asInt(0), response.path("message").asString(""));
                throw TdlibErrors.translate(response);
            }
            return response;
        }
        catch (TimeoutException ex) {
            throw new TelegramTransientException("TDLib request '" + method + "' timed out");
        }
        catch (ExecutionException ex) {
            throw new TelegramTransientException("TDLib client closed while awaiting a response to '"
                    + method + "'", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TelegramTransientException("Interrupted waiting for TDLib response", ex);
        }
        finally {
            this.pending.remove(extra);
        }
    }

    @Override
    public void onUpdate(Consumer<JsonNode> listener) {
        this.updateListeners.add(listener);
    }

    private void receiveLoop() {
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
            dispatch(node);
        }
    }

    private void dispatch(JsonNode node) {
        String extra = node.path("@extra").asString(null);
        if (extra != null) {
            CompletableFuture<JsonNode> future = this.pending.get(extra);
            if (future != null) {
                future.complete(node);
                return;
            }
        }
        if (isClosedAuthorizationState(node)) {
            // See the clientId javadoc: this id is now permanently dead. Done synchronously,
            // before the update is even handed to listeners, so a beginLogin() call triggered
            // by whatever this update causes downstream can never race a still-old clientId.
            this.clientId = this.library.td_create_client_id();
        }

        // Handed off rather than run inline: a listener is allowed to call request() itself
        // (see class javadoc, finding F11), which must not block the thread that alone can
        // ever deliver that request's response.
        this.listenerExecutor.execute(() -> {
            for (Consumer<JsonNode> listener : this.updateListeners) {
                try {
                    listener.accept(node);
                }
                catch (RuntimeException ex) {
                    log.warn("TDLib update listener failed: {}", ex.getClass().getSimpleName());
                }
            }
        });
    }

    private static boolean isClosedAuthorizationState(JsonNode node) {
        return "updateAuthorizationState".equals(node.path("@type").asString(""))
                && "authorizationStateClosed".equals(node.path("authorization_state").path("@type").asString(""));
    }

    @Override
    public void close() {
        this.running.set(false);
        this.receiveThread.interrupt();
        this.listenerExecutor.shutdown();
        TelegramTransientException closed = new TelegramTransientException("TDLib client closed");
        for (CompletableFuture<JsonNode> future : this.pending.values()) {
            future.completeExceptionally(closed);
        }
        try {
            this.listenerExecutor.awaitTermination((long) RECEIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
