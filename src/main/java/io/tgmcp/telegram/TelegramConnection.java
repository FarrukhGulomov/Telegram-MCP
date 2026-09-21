package io.tgmcp.telegram;

import java.time.Instant;
import java.util.UUID;

import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;

public record TelegramConnection(UUID id,
                                 UUID tenantId,
                                 UUID ownerUserId,
                                 TelegramAdapterKind adapter,
                                 LoginStage status,
                                 Long tgUserId,
                                 String tgUsername,
                                 String tgPhoneNumber,
                                 String sessionKeyId,
                                 String lastError,
                                 Instant connectedAt,
                                 Instant updatedAt) {

    public ConnectionRef ref() {
        return new ConnectionRef(this.tenantId, this.id);
    }

    public boolean ready() {
        return this.status == LoginStage.READY;
    }
}
