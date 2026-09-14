package io.tgmcp.telegram.tdlib;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
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
 * concurrently), correlates responses by the documented {@code @extra} field, and dispatches
 * everything else to update listeners.
 */
public class NativeTdlibClient implements TdlibClient {

    private static final Logger log = LoggerFactory.getLogger(NativeTdlibClient.class);
    private static final double RECEIVE_TIMEOUT_SECONDS = 1.0;

    private final TdJsonLibrary library;
    private final ObjectMapper objectMapper;
    private final int clientId;
    private final long requestTimeoutSeconds;

    private final Map<String, SynchronousQueue<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<JsonNode>> updateListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong extraSequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread receiveThread;

    public NativeTdlibClient(String libraryName, ObjectMapper objectMapper, long requestTimeoutSeconds) {
        this(Native.load(libraryName, TdJsonLibrary.class), objectMapper, requestTimeoutSeconds);
    }

    NativeTdlibClient(TdJsonLibrary library, ObjectMapper objectMapper, long requestTimeoutSeconds) {
        this.library = library;
        this.objectMapper = objectMapper;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.clientId = library.td_create_client_id();
        this.receiveThread = new Thread(this::receiveLoop, "tdlib-receive");
        this.receiveThread.setDaemon(true);
        this.receiveThread.start();
    }

    @Override
    public JsonNode request(String method, Map<String, Object> params) {
        String extra = "req-" + this.extraSequence.incrementAndGet();
        Map<String, Object> payload = new LinkedHashMap<>(params);
        payload.put("@type", method);
        payload.put("@extra", extra);

        SynchronousQueue<JsonNode> slot = new SynchronousQueue<>();
        this.pending.put(extra, slot);
        try {
            this.library.td_send(this.clientId, this.objectMapper.writeValueAsString(payload));
            JsonNode response = slot.poll(this.requestTimeoutSeconds, TimeUnit.SECONDS);
            if (response == null) {
                throw new TelegramTransientException("TDLib request '" + method + "' timed out");
            }
            if (TdlibErrors.isError(response)) {
                throw TdlibErrors.translate(response);
            }
            return response;
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
            SynchronousQueue<JsonNode> slot = this.pending.get(extra);
            if (slot != null && slot.offer(node)) {
                return;
            }
        }
        for (Consumer<JsonNode> listener : this.updateListeners) {
            try {
                listener.accept(node);
            }
            catch (RuntimeException ex) {
                log.warn("TDLib update listener failed: {}", ex.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void close() {
        this.running.set(false);
        this.receiveThread.interrupt();
    }
}
