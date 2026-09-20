package io.tgmcp.security;

import java.time.Instant;
import java.util.UUID;

/** One issued second-factor code. See {@code V6__login_verification_codes.sql}. */
public record LoginVerificationCode(UUID id, UUID appUserId, String codeHash, Instant expiresAt,
                                    Instant consumedAt, int attempts, Instant createdAt) {
}
