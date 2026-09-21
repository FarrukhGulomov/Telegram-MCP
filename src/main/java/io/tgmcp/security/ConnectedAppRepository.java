package io.tgmcp.security;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Lets an account holder see, and revoke, which MCP clients (Claude, ChatGPT, or any other
 * dynamically registered one) currently hold an OAuth authorization for their account -- the
 * same {@code oauth2_authorization}/{@code oauth2_authorization_consent} tables Spring
 * Authorization Server itself persists to (see V2 migration), queried directly since {@code
 * OAuth2AuthorizationService} exposes no "list by principal" operation of its own.
 */
@Repository
public class ConnectedAppRepository {

    private final JdbcClient jdbc;

    public ConnectedAppRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConnectedApp> findByPrincipal(String principalName) {
        return this.jdbc.sql("""
                        SELECT rc.id AS registered_client_id, rc.client_name,
                               MAX(a.authorized_scopes) AS scopes,
                               MAX(COALESCE(a.access_token_issued_at, a.authorization_code_issued_at))
                                   AS last_active_at
                          FROM oauth2_authorization a
                          JOIN oauth2_registered_client rc ON rc.id = a.registered_client_id
                         WHERE a.principal_name = :principal
                         GROUP BY rc.id, rc.client_name
                         ORDER BY last_active_at DESC NULLS LAST
                        """)
                .param("principal", principalName)
                .query((rs, rowNum) -> new ConnectedApp(
                        rs.getString("registered_client_id"),
                        rs.getString("client_name"),
                        rs.getString("scopes"),
                        rs.getTimestamp("last_active_at") == null ? null
                                : rs.getTimestamp("last_active_at").toInstant()))
                .list();
    }

    /**
     * Removes every authorization this account ever granted a specific client, and its consent
     * decision -- both are required: leaving {@code oauth2_authorization_consent} in place would
     * let that client silently obtain a fresh authorization code without the account holder ever
     * seeing the consent screen again (Spring Authorization Server skips re-prompting whenever a
     * prior consent already covers the requested scopes), making "revoke" only cosmetic.
     */
    public void revoke(String principalName, String registeredClientId) {
        this.jdbc.sql("DELETE FROM oauth2_authorization WHERE principal_name = :principal "
                        + "AND registered_client_id = :clientId")
                .param("principal", principalName)
                .param("clientId", registeredClientId)
                .update();
        this.jdbc.sql("DELETE FROM oauth2_authorization_consent WHERE principal_name = :principal "
                        + "AND registered_client_id = :clientId")
                .param("principal", principalName)
                .param("clientId", registeredClientId)
                .update();
    }
}
