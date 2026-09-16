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

    private final TdJsonLibrary library;
    private final ObjectMapper objectMapper;
    private final int clientId;
    private final long requestTimeoutSeconds;

    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<JsonNode>> updateListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong extraSequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong lastBackpressureWarningAt = new AtomicLong();
    private final int listenerQueueCapacity;
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
     * without pushing thousands of updates through a fake library. */
    NativeTdlibClient(TdJsonLibrary library, ObjectMapper objectMapper, long requestTimeoutSeconds,
                      int listenerQueueCapacity) {
        this.library = library;
        this.objectMapper = objectMapper;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.clientId = library.td_create_client_id();
        this.listenerQueueCapacity = listenerQueueCapacity;
        this.receiveThread = new Thread(this::receiveLoop, "tdlib-receive");
        this.receiveThread.setDaemon(true);
        this.listenerExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(listenerQueueCapacity),
                r -> {
                    Thread t = new Thread(r, "tdlib-listener");
                    t.setDaemon(true);
                    return t;
                },
                this::onListenerQueueSaturated);
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
