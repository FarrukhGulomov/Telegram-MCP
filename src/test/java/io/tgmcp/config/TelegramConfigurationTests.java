package io.tgmcp.config;

import java.time.Clock;
import java.util.List;

import io.tgmcp.telegram.gateway.TelegramAdapterKind;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding F08: the TDLIB adapter has no live edit/deletion pipeline, and a
 * production operator must be told this loudly at the moment it becomes relevant -- not left
 * to discover it from a source comment. These tests exercise only the config bean method
 * directly (no Spring context, no native library), since starting the real adapter needs a
 * libtdjson binary this environment does not have.
 */
class TelegramConfigurationTests {

    @Test
    void warnsLoudlyAboutTheMissingEditDeletePipelineBeforeAttemptingToLoadTheNativeLibrary() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(TelegramConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        AppProperties.Telegram telegramConfig = new AppProperties.Telegram(TelegramAdapterKind.TDLIB,
                "1", "hash", "./tdlib-data", true, true);
        AppProperties properties = new AppProperties(telegramConfig, null, null, null, null, null, null, null);
        TelegramConfiguration configuration = new TelegramConfiguration();

        try {
            // The native tdjson library is not present in this environment, so constructing the
            // real adapter is expected to fail -- but only *after* the warnings below are logged.
            configuration.telegramGateway(properties, Clock.systemUTC(), new ObjectMapper());
        }
        catch (RuntimeException | UnsatisfiedLinkError | NoClassDefFoundError expected) {
            // Expected: no native tdjson binary available here.
        }
        finally {
            logbackLogger.detachAppender(appender);
        }

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(m -> m.contains("connects a real Telegram account"));
        assertThat(messages)
                .as("must call out the missing edit/deletion pipeline, not just that a real account is in play")
                .anyMatch(m -> m.contains("NO live edit/deletion pipeline")
                        && m.contains("fetchRevisions")
                        && m.contains("blocking gap"));
    }
}
