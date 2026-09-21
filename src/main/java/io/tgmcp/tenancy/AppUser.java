package io.tgmcp.tenancy;

import java.util.UUID;

public record AppUser(UUID id, UUID tenantId, String email, String displayName,
                      String role, boolean enabled, Long telegramUserId, String telegramUsername) {

    public boolean isAdmin() {
        return "OWNER".equals(role) || "ADMIN".equals(role);
    }
}
