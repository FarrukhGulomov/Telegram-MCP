package io.tgmcp.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.tgmcp.config.AppProperties;

/**
 * Input schemas for the five tools.
 *
 * <p>Written by hand rather than generated, so that the bounds a client sees are exactly the
 * bounds the server enforces: explicit date ranges, capped page sizes, capped query length.
 */
public final class McpToolSchemas {

    private McpToolSchemas() {
    }

    public static Map<String, Object> listAllowedChats() {
        return object(Map.of(), List.of());
    }

    public static Map<String, Object> searchMessages(AppProperties.Mcp limits) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("chat_ids", arrayOf(string("Chat identifier from list_allowed_chats."),
                1, limits.maxChatIdsPerQuery(),
                "Chats to search. Every id must be one returned by list_allowed_chats."));
        properties.put("query", stringWithLimits(
                "Full-text query. Supports quoted phrases and -exclusions (PostgreSQL websearch syntax).",
                1, limits.maxQueryLength()));
        properties.put("from", dateTime("Inclusive start of the search window (RFC 3339). Required."));
        properties.put("to", dateTime("Inclusive end of the search window (RFC 3339). Required."));
        properties.put("cursor", string(
                "Opaque cursor from a previous response's pagination.cursor. "
                        + "Valid only for an identical query, chat set and date range."));
        properties.put("limit", integer("Maximum messages to return.", 1, limits.maxPageSize(),
                limits.defaultPageSize()));
        return object(properties, List.of("chat_ids", "query", "from", "to"));
    }

    public static Map<String, Object> getChatHistory(AppProperties.Mcp limits) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("chat_id", string("Chat identifier from list_allowed_chats."));
        properties.put("from", dateTime("Inclusive start of the window (RFC 3339). Required."));
        properties.put("to", dateTime("Inclusive end of the window (RFC 3339). Required."));
        properties.put("cursor", string("Opaque cursor from a previous response for the same request."));
        properties.put("limit", integer("Maximum messages to return.", 1, limits.maxPageSize(),
                limits.defaultPageSize()));
        return object(properties, List.of("chat_id", "from", "to"));
    }

    public static Map<String, Object> getMessageContext(AppProperties.Mcp limits) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("chat_id", string("Chat identifier from list_allowed_chats."));
        properties.put("message_id", string(
                "Telegram message id, as returned in a message item's messageId field (string)."));
        properties.put("before", integer("Messages to include before the target.", 0,
                limits.maxContextMessages(), 5));
        properties.put("after", integer("Messages to include after the target.", 0,
                limits.maxContextMessages(), 5));
        return object(properties, List.of("chat_id", "message_id"));
    }

    public static Map<String, Object> getSyncStatus() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("chat_id", string("Chat identifier from list_allowed_chats."));
        return object(properties, List.of("chat_id"));
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> string(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private static Map<String, Object> stringWithLimits(String description, int min, int max) {
        Map<String, Object> schema = string(description);
        schema.put("minLength", min);
        schema.put("maxLength", max);
        return schema;
    }

    private static Map<String, Object> dateTime(String description) {
        Map<String, Object> schema = string(description);
        schema.put("format", "date-time");
        return schema;
    }

    private static Map<String, Object> integer(String description, int min, int max, int defaultValue) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        schema.put("minimum", min);
        schema.put("maximum", max);
        schema.put("default", defaultValue);
        return schema;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items, int minItems, int maxItems,
                                               String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", items);
        schema.put("minItems", minItems);
        schema.put("maxItems", maxItems);
        return schema;
    }
}
