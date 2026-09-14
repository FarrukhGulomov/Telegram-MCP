package io.tgmcp;

import java.time.Duration;
import java.time.Instant;

import io.tgmcp.chat.Chat;
import io.tgmcp.consent.ConsentMethod;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding F02 (P0): an authenticated operator could record consent "on behalf
 * of" any participant via {@code SELF_SERVICE_LINK} with nothing checking that the participant
 * themselves ever saw or approved anything, and {@code DEMO_SYNTHETIC} was only ever hidden in
 * the HTML -- never rejected server-side -- so a direct POST could use it even against a real
 * Telegram account. {@link ConsentService#record} now rejects both cases outright; only
 * {@code SIGNED_ATTESTATION} (which requires a named evidence reference, an honest and
 * auditable "I obtained this out of band" record) is available going forward.
 */
class ConsentMethodGuardTests extends AbstractIntegrationTest {

    @Autowired
    private ConsentService consentService;

    /** Spied only to make kind() report TDLIB; every other call still runs the real mock logic. */
    @MockitoSpyBean
    private TelegramGateway gateway;

    private Chat chat(Fixtures.Team team) {
        return this.fixtures.chat(team, SyntheticDataset.ENGINEERING_CHAT, "F02 chat", "f02_chat_"
                + team.tenant().id());
    }

    @Test
    void selfServiceLinkIsAlwaysRejectedRegardlessOfAdapter() {
        Fixtures.Team team = this.fixtures.team("f02-self-service");
        Chat chat = chat(team);

        assertThatThrownBy(() -> this.consentService.record(team.actor(), chat.id(), 111L, "Alice",
                "purpose", "ANTHROPIC_CLAUDE", "v1", ConsentMethod.SELF_SERVICE_LINK, null,
                Instant.now().plus(Duration.ofDays(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("validation.selfServiceNotAvailable");
    }

    @Test
    void demoSyntheticConsentIsRejectedOnceARealAdapterIsInPlay() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = this.fixtures.team("f02-demo-blocked");
        Chat chat = chat(team);

        assertThatThrownBy(() -> this.consentService.record(team.actor(), chat.id(), 111L, "Alice",
                "purpose", "ANTHROPIC_CLAUDE", "v1", ConsentMethod.DEMO_SYNTHETIC, null,
                Instant.now().plus(Duration.ofDays(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("validation.demoConsentNotAllowed");
    }

    @Test
    void demoSyntheticConsentStillWorksUnderTheMockAdapter() {
        Fixtures.Team team = this.fixtures.team("f02-demo-allowed");
        Chat chat = chat(team);

        assertThat(this.consentService.record(team.actor(), chat.id(), 111L, "Alice",
                "purpose", "ANTHROPIC_CLAUDE", "v1", ConsentMethod.DEMO_SYNTHETIC, null,
                Instant.now().plus(Duration.ofDays(1)))).isNotNull();
    }

    @Test
    void signedAttestationStillRequiresAnEvidenceReference() {
        Fixtures.Team team = this.fixtures.team("f02-evidence-required");
        Chat chat = chat(team);

        assertThatThrownBy(() -> this.consentService.record(team.actor(), chat.id(), 111L, "Alice",
                "purpose", "ANTHROPIC_CLAUDE", "v1", ConsentMethod.SIGNED_ATTESTATION, "  ",
                Instant.now().plus(Duration.ofDays(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("validation.evidenceRequired");
    }

    @Test
    void signedAttestationWithEvidenceSucceeds() {
        Fixtures.Team team = this.fixtures.team("f02-evidence-ok");
        Chat chat = chat(team);

        assertThat(this.consentService.record(team.actor(), chat.id(), 111L, "Alice",
                "purpose", "ANTHROPIC_CLAUDE", "v1", ConsentMethod.SIGNED_ATTESTATION, "email dated 2026-01-01",
                Instant.now().plus(Duration.ofDays(1)))).isNotNull();
    }
}
