package io.tgmcp.telegram.tdlib;

import com.sun.jna.Library;

/**
 * The TDLib JSON interface, exactly as declared in {@code td/telegram/td_json_client.h}
 * (tdlib/td, current API — the older {@code td_json_client_*} entry points are deprecated
 * and deliberately not bound here).
 *
 * <p>This is the only place in the codebase that names a native symbol.
 */
public interface TdJsonLibrary extends Library {

    /** {@code int td_create_client_id();} */
    int td_create_client_id();

    /** {@code void td_send(int client_id, const char *request);} */
    void td_send(int clientId, String request);

    /** {@code const char *td_receive(double timeout);} — must not be called from two threads at once. */
    String td_receive(double timeout);

    /** {@code const char *td_execute(const char *request);} — synchronous service requests only. */
    String td_execute(String request);
}
