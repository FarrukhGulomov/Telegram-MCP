package io.tgmcp.telegram.tdlib;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
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
 * The only class in the application that talks to a native library, aside from {@link
 * TdlibReceiveLoop} (see its own javadoc for why {@code td_receive} sometimes cannot be this
 * class's own private thread at all).
 *
 * <p>Exactly one thread ever drains {@code td_receive} for a given underlying native library --
 * this class's own private receive thread when it owns the library outright, or {@link
 * TdlibReceiveLoop}'s single shared thread when other clients (other tenants' TDLib sessions)
 * share it -- and correlates responses by the documented {@code @extra} field. Everything else
 * -- update listeners -- is handed off to a single dedicated executor rather than run inline on
 * the receive thread.
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

    /**
     * Security-audit finding SEC-14: {@code Executors.newSingleThreadExecutor()} backs its
     * queue with an unbounded {@code LinkedBlockingQueue} -- a busy upstream account, a slow
     * database write inside a listener, or a listener blocked making its own synchronous {@link
     * #request} could accumulate unbounded work here, since every update TDLib sends is
     * unconditionally enqueued before any adapter gets a chance to decide it is irrelevant. This
     * caps the queue instead.
     */
    private static final int LISTENER_QUEUE_CAPACITY = 10_000;

    /** Throttles the backpressure warning below so a sustained overload logs a signal an
     * operator can act on, not a log flood that makes the underlying slowdown worse. */
    private static final long BACKPRESSURE_WARNING_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(10);

    /** Non-null only when this client owns its own private receive thread (see {@link #receiveThread}). */
    private final TdJsonLibrary library;
    /**
     * Non-null only when this client shares a process-wide receive thread with other clients --
     * see {@link TdlibReceiveLoop}'s own javadoc for why {@code td_receive} cannot simply be
     * called from one thread per client the way {@link #library} directly allows.
     */
    private final TdlibReceiveLoop sharedReceiveLoop;
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
    private final AtomicLong lastBackpressureWarningAt = new AtomicLong();
    private final int listenerQueueCapacity;
    /** Non-null only in private mode -- see {@link #library}. */
    private final Thread receiveThread;
    /**
     * Bounded, single-threaded, and never drops a task: once the queue is full, {@link
     * #onListenerQueueSaturated} runs the update on the submitting thread (the native receive
     * thread) instead, which naturally slows {@code td_receive} draining until the listener
     * catches up rather than accumulating unbounded backlog or silently discarding an update --
     * including an authorization-state or deletion update -- to make room for it.
     */
    private final ExecutorService listenerExecutor;

    public NativeTdlibClient(String libraryName, ObjectMapper objectMapper, long requestTimeoutSeconds) {
        this(Native.load(libraryName, TdJsonLibrary.class), objectMapper, requestTimeoutSeconds);
    }

    NativeTdlibClient(TdJsonLibrary library, ObjectMapper objectMapper, long requestTimeoutSeconds) {
        this(library, objectMapper, requestTimeoutSeconds, LISTENER_QUEUE_CAPACITY);
    }

    /** Package-visible so tests can shrink {@code listenerQueueCapacity} to exercise saturation
     * without pushing thousands of updates through a fake library. Owns its own private receive
     * thread -- only safe when no other client shares the same underlying native library; see
     * {@link #NativeTdlibClient(TdlibReceiveLoop, ObjectMapper, long, int)} for that case. */
    NativeTdlibClient(TdJsonLibrary library, ObjectMapper objectMapper, long requestTimeoutSeconds,
                      int listenerQueueCapacity) {
        this.library = library;
        this.sharedReceiveLoop = null;
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
        this.listenerQueueCapacity = listenerQueueCapacity;
        this.receiveThread = new Thread(this::privateReceiveLoop, "tdlib-receive");
        this.receiveThread.setDaemon(true);
        this.listenerExecutor = newListenerExecutor(listenerQueueCapacity);
    }

    /** Package-visible: shares {@code receiveLoop}'s single process-wide receive thread with
     * every other client registered on it -- see {@link TdlibReceiveLoop}'s javadoc. */
    NativeTdlibClient(TdlibReceiveLoop receiveLoop, ObjectMapper objectMapper, long requestTimeoutSeconds) {
        this(receiveLoop, objectMapper, requestTimeoutSeconds, LISTENER_QUEUE_CAPACITY);
    }

    /** Package-visible so tests can shrink {@code listenerQueueCapacity}; see the 4-arg private
     * constructor's own note. */
    NativeTdlibClient(TdlibReceiveLoop receiveLoop, ObjectMapper objectMapper, long requestTimeoutSeconds,
                      int listenerQueueCapacity) {
        this.library = null;
        this.sharedReceiveLoop = receiveLoop;
        this.objectMapper = objectMapper;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.clientId = receiveLoop.createClientId();
        this.listenerQueueCapacity = listenerQueueCapacity;
        this.receiveThread = null;
        this.listenerExecutor = newListenerExecutor(listenerQueueCapacity);
    }

    private ExecutorService newListenerExecutor(int listenerQueueCapacity) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(listenerQueueCapacity),
                r -> {
                    Thread t = new Thread(r, "tdlib-listener");
                    t.setDaemon(true);
                    return t;
                },
                this::onListenerQueueSaturated);
    }

    /**
     * Starts draining updates for this client. Deferred out of the constructor (security-audit
     * finding F11) so that every {@link #onUpdate} listener a caller intends to register is in
     * place first -- otherwise an update TDLib sends immediately after client creation (in
     * practice, the very first {@code updateAuthorizationState}) can arrive before any listener
     * exists to receive it. Idempotent: a second call is a no-op.
     */
    @Override
    public void start() {
        if (this.started.compareAndSet(false, true)) {
            if (this.receiveThread != null) {
                this.receiveThread.start();
            }
            else {
                // Registering here rather than in the constructor preserves the same F11
                // ordering guarantee as the private-thread path above: this client cannot be
                // handed a message -- the shared loop may already be draining other clients'
                // updates by the time this one exists -- until onUpdate() has had its chance to
                // run first.
                this.sharedReceiveLoop.register(this.clientId, this::dispatch);
                this.sharedReceiveLoop.start();
            }
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
            sendRaw(this.objectMapper.writeValueAsString(payload));
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

    /**
     * Security-audit finding SEC-14: invoked by {@link #listenerExecutor} once {@link
     * #LISTENER_QUEUE_CAPACITY} is exceeded. Running the task on the calling thread (the native
     * receive loop) rather than a pool thread applies backpressure to {@code td_receive} itself
     * -- the receive loop cannot fetch the next update until this one is dispatched -- so a slow
     * or blocked listener throttles ingestion instead of this process accumulating unbounded
     * memory or dropping an update to make room.
     */
    private void onListenerQueueSaturated(Runnable task, ThreadPoolExecutor executor) {
        long now = System.currentTimeMillis();
        long last = this.lastBackpressureWarningAt.get();
        if (now - last > BACKPRESSURE_WARNING_INTERVAL_MILLIS
                && this.lastBackpressureWarningAt.compareAndSet(last, now)) {
            log.warn("TDLib update listener queue is saturated (capacity {}); applying backpressure "
                    + "to the native receive loop instead of dropping updates or growing unbounded. "
                    + "A slow or blocked update listener is the likely cause.", this.listenerQueueCapacity);
        }
        task.run();
    }

    private void sendRaw(String json) {
        if (this.sharedReceiveLoop != null) {
            this.sharedReceiveLoop.send(this.clientId, json);
        }
        else {
            this.library.td_send(this.clientId, json);
        }
    }

    private int createFreshClientId() {
        return this.sharedReceiveLoop != null
                ? this.sharedReceiveLoop.createClientId()
                : this.library.td_create_client_id();
    }

    /** Only used in private mode -- see {@link #library}; a shared-mode client is instead handed
     * its messages by {@link TdlibReceiveLoop} calling {@link #dispatch} directly. */
    private void privateReceiveLoop() {
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
            int oldClientId = this.clientId;
            int newClientId = createFreshClientId();
            this.clientId = newClientId;
            if (this.sharedReceiveLoop != null) {
                this.sharedReceiveLoop.reregister(oldClientId, newClientId, this::dispatch);
            }
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
        if (this.receiveThread != null) {
            this.receiveThread.interrupt();
        }
        else {
            this.sharedReceiveLoop.unregister(this.clientId);
        }
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
