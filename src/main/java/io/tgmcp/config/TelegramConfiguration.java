package io.tgmcp.config;

import java.time.Clock;

import io.tgmcp.telegram.gateway.TelegramGateway;
import io.tgmcp.telegram.mock.MockTelegramGateway;
import io.tgmcp.telegram.tdlib.NativeTdlibClient;
import io.tgmcp.telegram.tdlib.TdlibClient;
import io.tgmcp.telegram.tdlib.TdlibTelegramGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the Telegram adapter. MOCK is the default so that the application is fully
 * runnable — onboarding, consent, sync, MCP and revocation — without any credential.
 */
@Configuration(proxyBeanMethods = false)
public class TelegramConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TelegramConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(TelegramGateway.class)
    public TelegramGateway telegramGateway(AppProperties properties, Clock clock, ObjectMapper objectMapper) {
        AppProperties.Telegram config = properties.telegram();
        return switch (config.adapter()) {
            case MOCK -> {
                log.info("Telegram adapter: MOCK. All chats and messages are synthetic; "
                        + "no Telegram account is contacted.");
                yield new MockTelegramGateway(clock);
            }
            case TDLIB -> {
                if (config.apiId() == null || config.apiHash() == null) {
                    throw new IllegalStateException("The TDLIB adapter requires tgmcp.telegram.api-id "
                            + "and tgmcp.telegram.api-hash");
                }
                if (!config.realAccountAccessAuthorized()) {
                    throw new IllegalStateException("Refusing to start the TDLIB adapter: set "
                            + "tgmcp.telegram.real-account-access-authorized=true only when you have explicit "
                            + "authorization to connect a real Telegram account.");
                }
                log.warn("Telegram adapter: TDLIB. This connects a real Telegram account.");
                // Security-audit finding F08: said out loud, once, at the moment it becomes
                // relevant -- a real deployment must not discover this from a code comment.
                log.warn("TDLIB adapter's edit/deletion pipeline (updateMessageContent / "
                        + "updateDeleteMessages) has never been verified against a real Telegram account "
                        + "(see docs/production-prerequisites.md). Treat edit/delete sync as unproven for "
                        + "any deployment where it matters until that verification has been done.");
                TdlibClient client = new NativeTdlibClient("tdjson", objectMapper, 30);
                yield new TdlibTelegramGateway(client, config, properties.secrets().sessionEncryptionKey());
            }
        };
    }
}
