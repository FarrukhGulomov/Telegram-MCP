package io.tgmcp.tenancy;

import java.time.Instant;
import java.util.UUID;

public record Tenant(UUID id, String slug, String name, boolean demoMode, Instant createdAt) {
}
