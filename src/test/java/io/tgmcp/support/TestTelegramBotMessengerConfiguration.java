package io.tgmcp.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Swaps in {@link FakeTelegramBotMessenger} everywhere {@code TelegramBotMessenger} is needed. */
@TestConfiguration(proxyBeanMethods = false)
public class TestTelegramBotMessengerConfiguration {

    @Bean
    @Primary
    public FakeTelegramBotMessenger fakeTelegramBotMessenger() {
        return new FakeTelegramBotMessenger();
    }
}
