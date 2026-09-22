package io.tgmcp.platformadmin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.tgmcp.tenancy.ActorType;

/**
 * As {@link io.tgmcp.audit.AuditEvent}, but carrying the owning tenant's slug for display,
 * since the platform-admin feed spans every tenant rather than just one caller's own.
 */
public record PlatformAuditEvent(UUID id,
                                 String tenantSlug,
                                 Instant occurredAt,
                                 ActorType actorType,
                                 String actorId,
                                 String action,
                                 UUID chatId,
                                 String outcome,
                                 Map<String, Object> detail) {
}
