package io.tgmcp.platformadmin;

import java.time.Instant;
import java.util.UUID;

/**
 * One tenant's footprint across the whole platform, for the platform-admin overview -- never
 * exposed to a tenant's own users, only to the operator identities configured under {@code
 * tgmcp.platform-admin.phone-numbers} (see {@link io.tgmcp.ui.CurrentActor#requirePlatformAdmin}).
 */
public record TenantSummary(UUID tenantId,
                            String slug,
                            String name,
                            boolean demoMode,
                            Instant createdAt,
                            long userCount,
                            long chatCount,
                            long activeChatCount,
                            long connectedTelegramAccounts) {
}
