package io.tgmcp;

import java.time.Duration;
import java.time.Instant;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationService;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-account testing reported that a Telegram channel could never be activated: its
 * subscribers were treated exactly like a group's or private chat's participants, requiring
 * each one's individual consent before the channel could ever leave AWAITING_CONSENT. A
 * broadcast channel's content is authored by the channel itself for a public audience -- the
 * same way a blog post needs no consent from its readers -- so per-participant consent coverage
 * does not apply to it at all. Only Telegram's own {@code is_channel} flag (surfaced as
 * {@code Chat.chatType() == "CHANNEL"}, never operator-editable) drives this: an ordinary group
 * or private chat still requires full participant consent exactly as before.
 */
class ChannelConsentTests extends AbstractIntegrationTest {

    @Autowired
    private ChatActivationService activationService;

    @Autowired
    private ConsentService consentService;

    @Test
    void aChannelNeedsNoParticipantConsentToActivate() {
        Instant now = this.clock.instant();
        Fixtures.Team team = this.fixtures.team("channel-consent");
        Chat channel = this.fixtures.chat(team, 555_001L, "[SYNTHETIC] Announcements",
                "synthetic_channel", "CHANNEL");
        // Deliberately no participants recorded at all -- unlike every group/private-chat
        // fixture, which always seeds a known membership before consent can even be considered.
        ChatActivation activation = this.fixtures.activation(team, channel, ActivationState.AWAITING_CONSENT,
                now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(30)), 90, McpScopes.ANTHROPIC_CLAUDE);

        assertThat(this.consentService.coverageFor(activation).complete())
                .as("a channel's consent coverage is satisfied without any participant consent")
                .isTrue();

        ChatActivation activated = this.activationService.activate(team.actor(), channel.id());
        assertThat(activated.state()).isEqualTo(ActivationState.ACTIVE);
    }
}
