package io.tgmcp;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import io.tgmcp.mcp.McpCallContext;
import io.tgmcp.mcp.McpToolNames;
import io.tgmcp.mcp.McpTools;
import io.tgmcp.security.McpScopes;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.sync.ChatSyncService;
import io.tgmcp.telegram.gateway.RemoteMessage;
import io.tgmcp.telegram.mock.SyntheticDataset;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-audit finding L1: {@code McpTools.getMessageMedia} used to compare a downloaded
 * file's raw byte length against {@code tgmcp.mcp.max-response-bytes}, but what actually
 * reaches the client is the base64-encoded string -- about a third larger. A file just under
 * the cap in its raw form could therefore be served well over it once encoded. Runs against a
 * deliberately small cap chosen so a fixed-size payload sits on the wrong side of exactly this
 * gap: under the cap raw, over it once base64-encoded.
 */
@TestPropertySource(properties = "tgmcp.mcp.max-response-bytes=100")
class MessageMediaSizeCapTests extends AbstractIntegrationTest {

    @Autowired
    private McpTools tools;

    @Autowired
    private ChatSyncService syncService;

    @Test
    void rejectsMediaWhoseBase64EncodingExceedsTheCapEvenThoughItsRawBytesFitUnderIt() {
        Fixtures.ReadyChat chat = this.fixtures.readyEngineeringChat("l1-media-cap", this.clock.instant());
        // 90 raw bytes fits comfortably under the 100-byte cap; base64-encoded (ceil(90/3)*4 =
        // 120 bytes) does not. The old, raw-byte-only check would have let this straight through.
        byte[] rawBytes = new byte[90];
        RemoteMessage appended = this.mockGateway().appendMessageWithMedia(SyntheticDataset.ENGINEERING_CHAT,
                SyntheticDataset.ALICE, "Alice Nakamura (synthetic)", "Photo", "PHOTO", rawBytes);
        this.syncService.sync(chat.team().tenant().id(), chat.chat().id());
        McpCallContext context = new McpCallContext(chat.team().actor(), McpScopes.ANTHROPIC_CLAUDE);

        McpSchema.CallToolResult result = this.tools.getMessageMedia(context,
                new McpSchema.CallToolRequest(McpToolNames.GET_MESSAGE_MEDIA, Map.of(
                        "chat_id", chat.chat().id().toString(),
                        "message_id", String.valueOf(appended.messageId()))));

        assertThat(result.isError()).isFalse();
        Map<String, Object> payload = (Map<String, Object>) result.structuredContent();
        assertThat(payload)
                .as("90 raw bytes fits the 100-byte cap, but its base64 encoding (120 bytes) does not")
                .containsEntry("available", false);
        assertThat(result.content().stream().anyMatch(McpSchema.ImageContent.class::isInstance))
                .as("a rejected-for-size preview must never include the image content itself")
                .isFalse();
    }
}
