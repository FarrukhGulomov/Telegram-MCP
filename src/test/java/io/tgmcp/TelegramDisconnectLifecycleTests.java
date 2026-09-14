package io.tgmcp;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.chat.ChatActivationService;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.TelegramConnectionService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding F16: disconnecting a Telegram connection used to only end the native
 * session and clear the stored session envelope -- every chat that connection had activated
 * stayed ACTIVE, and {@link io.tgmcp.access.ChatAccessService} never checks whether the
 * underlying connection is still ready. Old content stayed fully MCP-readable indefinitely
 * after a disconnect, with no signal anywhere that the account behind it was gone.
 */
class TelegramDisconnectLifecycleTests extends AbstractIntegrationTest {

    @Autowired
    private TelegramConnectionService connectionService;

    @Autowired
    private ChatActivationRepository activations;

    @Autowired
    private ChatActivationService activationService;

    @Test
    void disconnectingPausesEveryActiveChatUnderThatConnection() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("f16-disconnect", this.clock.instant());
        assertThat(this.activations.find(chat.team().tenant().id(), chat.chat().id())
                .orElseThrow().state()).isEqualTo(ActivationState.ACTIVE);

        this.connectionService.disconnect(chat.team().actor());

        assertThat(this.activations.find(chat.team().tenant().id(), chat.chat().id())
                        .orElseThrow().state())
                .as("a chat must stop being readable the moment its connection disconnects, "
                        + "not keep serving stored content as if the account were still there")
                .isEqualTo(ActivationState.PAUSED);
    }

    @Test
    void disconnectingLeavesAnAlreadyRevokedChatAlone() {
        // A REVOKED chat is not "paused by disconnect" -- it is already gone, and pausing it
        // would be a regression back into a state that (unlike REVOKED) still retains content.
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("f16-disconnect-revoked", this.clock.instant());
        this.activationService.revoke(chat.team().actor(), chat.chat().id(), "already gone");

        this.connectionService.disconnect(chat.team().actor());

        assertThat(this.activations.find(chat.team().tenant().id(), chat.chat().id())
                .orElseThrow().state()).isEqualTo(ActivationState.REVOKED);
    }
}
