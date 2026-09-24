package io.tgmcp.mcp;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.tgmcp.access.ChatAccessException;

/**
 * Strict, allocation-light parsing of tool arguments.
 *
 * <p>Every accessor either returns a value inside the documented bounds or throws
 * {@code INVALID_ARGUMENT}. Nothing here coerces silently, and nothing accepts an unbounded
 * range, an unbounded page or an unbounded query.
 */
public final class ToolArguments {

    private final Map<String, Object> raw;

    public ToolArguments(Map<String, Object> raw) {
        this.raw = raw == null ? Map.of() : raw;
    }

    public void rejectUnknown(Collection<String> allowed) {
        for (String key : this.raw.keySet()) {
            if (!allowed.contains(key)) {
                throw ChatAccessException.invalidArgument("Unknown argument: " + key);
            }
        }
    }

    public UUID requiredUuid(String name) {
        Object value = require(name);
        if (!(value instanceof String text)) {
            throw ChatAccessException.invalidArgument(name + " must be a string");
        }
        try {
            return UUID.fromString(text.trim());
        }
        catch (IllegalArgumentException ex) {
            // A malformed id is an argument error, not evidence about which ids exist.
            throw ChatAccessException.invalidArgument(name + " is not a valid identifier");
        }
    }

    public List<UUID> requiredUuidList(String name, int max) {
        Object value = require(name);
        if (!(value instanceof Collection<?> collection)) {
            throw ChatAccessException.invalidArgument(name + " must be an array of chat identifiers");
        }
        if (collection.isEmpty()) {
            throw ChatAccessException.invalidArgument(name + " must contain at least one chat identifier");
        }
        if (collection.size() > max) {
            throw ChatAccessException.invalidArgument(name + " accepts at most " + max + " chat identifiers");
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Object element : collection) {
            if (!(element instanceof String text)) {
                throw ChatAccessException.invalidArgument(name + " must contain strings");
            }
            try {
                ids.add(UUID.fromString(text.trim()));
            }
            catch (IllegalArgumentException ex) {
                throw ChatAccessException.invalidArgument(name + " contains an invalid identifier");
            }
        }
        return new ArrayList<>(ids);
    }

    public String requiredText(String name, int maxLength) {
        Object value = require(name);
        if (!(value instanceof String text)) {
            throw ChatAccessException.invalidArgument(name + " must be a string");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw ChatAccessException.invalidArgument(name + " must not be empty");
        }
        if (trimmed.length() > maxLength) {
            throw ChatAccessException.invalidArgument(name + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    /**
     * External audit finding L6: {@link io.tgmcp.mcp.CursorCodec} encodes a cursor's {@code
     * from}/{@code to} range at millisecond precision ({@code Instant.toEpochMilli()}), then a
     * follow-up page's cursor validation compares that decoded (already-truncated) value against
     * whatever this method returns for the client's freshly re-sent {@code from}/{@code to}
     * arguments. A client that happens to send an RFC 3339 timestamp with sub-millisecond digits
     * (legal RFC 3339, and something a client library could produce without anyone intending
     * it) got a value here that still carried them, so the two never compared equal and every
     * later page failed with INVALID_CURSOR -- for a reason that had nothing to do with the
     * cursor actually being tampered with. Truncating to milliseconds here, at the single point
     * every caller (both the original request and every follow-up page of it) parses this
     * argument, keeps both sides of that comparison looking at the same value regardless of how
     * much precision the client happened to send.
     */
    public Instant requiredInstant(String name) {
        Object value = require(name);
        if (!(value instanceof String text)) {
            throw ChatAccessException.invalidArgument(name + " must be an RFC 3339 date-time string");
        }
        try {
            return OffsetDateTime.parse(text.trim()).toInstant().truncatedTo(ChronoUnit.MILLIS);
        }
        catch (DateTimeException ex) {
            try {
                return Instant.parse(text.trim()).truncatedTo(ChronoUnit.MILLIS);
            }
            catch (DateTimeException ex2) {
                throw ChatAccessException.invalidArgument(
                        name + " must be an RFC 3339 date-time, for example 2026-01-31T00:00:00Z");
            }
        }
    }

    public String optionalText(String name, int maxLength) {
        Object value = this.raw.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw ChatAccessException.invalidArgument(name + " must be a string");
        }
        if (text.length() > maxLength) {
            throw ChatAccessException.invalidArgument(name + " is too long");
        }
        return text.isBlank() ? null : text;
    }

    public int optionalInt(String name, int defaultValue, int min, int max) {
        Object value = this.raw.get(name);
        if (value == null) {
            return defaultValue;
        }
        long parsed;
        if (value instanceof Number number) {
            parsed = number.longValue();
        }
        else if (value instanceof String text) {
            try {
                parsed = Long.parseLong(text.trim());
            }
            catch (NumberFormatException ex) {
                throw ChatAccessException.invalidArgument(name + " must be an integer");
            }
        }
        else {
            throw ChatAccessException.invalidArgument(name + " must be an integer");
        }
        if (parsed < min || parsed > max) {
            throw ChatAccessException.invalidArgument(name + " must be between " + min + " and " + max);
        }
        return (int) parsed;
    }

    public long requiredLong(String name) {
        Object value = require(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            }
            catch (NumberFormatException ex) {
                throw ChatAccessException.invalidArgument(name + " must be an integer");
            }
        }
        throw ChatAccessException.invalidArgument(name + " must be an integer");
    }

    /** Enforces that the caller asked for a real, bounded, sanely ordered window. */
    public static void validateRange(Instant from, Instant to, int maxDays) {
        if (!to.isAfter(from)) {
            throw ChatAccessException.invalidArgument("'to' must be after 'from'");
        }
        if (Duration.between(from, to).toDays() > maxDays) {
            throw ChatAccessException.invalidArgument(
                    "The requested range exceeds the maximum of " + maxDays + " days");
        }
    }

    private Object require(String name) {
        Object value = this.raw.get(name);
        if (value == null) {
            throw ChatAccessException.invalidArgument("Missing required argument: " + name);
        }
        return value;
    }
}
