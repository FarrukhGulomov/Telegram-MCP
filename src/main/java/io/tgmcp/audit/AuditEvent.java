package io.tgmcp.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.tgmcp.tenancy.ActorType;

public record AuditEvent(UUID id,
                         UUID tenantId,
                         Instant occurredAt,
                         ActorType actorType,
                         String actorId,
                         String action,
                         UUID chatId,
                         String outcome,
                         Map<String, Object> detail) {
}
