package io.tgmcp.security;

import java.util.Map;

import tools.jackson.databind.JsonNode;

/** One Telegram Bot API method call. A thin interface so tests can substitute a fake. */
public interface TelegramBotApiCaller {

    JsonNode call(String method, Map<String, Object> params) throws TelegramBotMessageException;
}
