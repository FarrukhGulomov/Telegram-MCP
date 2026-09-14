package io.tgmcp.telegram.tdlib;

import java.util.Map;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;

/**
 * Narrow seam between TDLib and the rest of the adapter.
 *
 * <p>{@link TdlibTelegramGateway} depends only on this interface, so the JSON-to-domain
 * translation — which is where the interesting logic and the interesting bugs live — is
 * testable without any native library present.
 */
public interface TdlibClient extends AutoCloseable {

    /**
     * Sends a TDLib request and blocks for its correlated response.
     *
     * @throws io.tgmcp.telegram.gateway.TelegramGatewayException translated from a TDLib {@code error} object
     */
    JsonNode request(String method, Map<String, Object> params);

    /** Registers a listener for TDLib {@code update*} objects (new messages, edits, deletions). */
    void onUpdate(Consumer<JsonNode> listener);

    @Override
    void close();
}
