package io.tgmcp.mcp;

/** Any cursor problem — tampered, expired, or used against a different query. */
public class CursorException extends RuntimeException {
    public CursorException(String message) {
        super(message);
    }
}
