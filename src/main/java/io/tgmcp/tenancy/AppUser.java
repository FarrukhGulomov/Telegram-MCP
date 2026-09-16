package io.tgmcp.tenancy;

import java.util.UUID;

public record AppUser(UUID id, UUID tenantId, String email, String passwordHash, String displayName,
                      String role, boolean enabled, Long telegramUserId) {

    public boolean isAdmin() {
        return "OWNER".equals(role) || "ADMIN".equals(role);
    }
}
