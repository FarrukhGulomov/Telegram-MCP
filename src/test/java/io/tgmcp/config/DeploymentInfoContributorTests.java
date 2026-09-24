package io.tgmcp.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.tgmcp.telegram.gateway.TelegramAdapterKind;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentInfoContributorTests {

    private static AppProperties properties(TelegramAdapterKind adapter, boolean realAccountAccessAuthorized) {
        return new AppProperties(
                new AppProperties.Telegram(adapter, "1", "hash", "./tdlib-data",
                        realAccountAccessAuthorized, true),
                new AppProperties.Mcp(50, 200, 20, 512, 20, 262_144, 4000, 366,
                        Duration.ofMinutes(15), 120, "http://localhost:8080/mcp", "mcp:read"),
                new AppProperties.Consent("2026-09-v1", true, Duration.ofDays(365)),
                new AppProperties.Sync(Duration.ofSeconds(30), 200, 5, 2, 6,
                        Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(24)),
                new AppProperties.Secrets(null, "local-dev", null, null, List.of()),
                new AppProperties.Demo(false, "demo", "Demo", List.of(), false),
                new AppProperties.AbuseControls(10, 20, 5, 30, 5, 10, 5, Duration.ofHours(1)),
                new AppProperties.ForwardedHeaders(List.of()),
                new AppProperties.PlatformAdmin(List.of()),
                new AppProperties.Diagnostics(false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesTheConfiguredAdapterAndRealAccountAuthorizationWithoutAnythingSensitive() {
        DeploymentInfoContributor contributor =
                new DeploymentInfoContributor(properties(TelegramAdapterKind.TDLIB, true));
        Info.Builder builder = new Info.Builder();

        contributor.contribute(builder);

        Map<String, Object> telegram = (Map<String, Object>) builder.build().get("telegram");
        assertThat(telegram).containsEntry("adapter", "TDLIB").containsEntry("realAccountAccessAuthorized", true);
    }
}
