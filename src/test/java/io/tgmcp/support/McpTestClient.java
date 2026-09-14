package io.tgmcp.support;

import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/** A real MCP client, so tool discovery and tool calls are exercised over the wire. */
public final class McpTestClient implements AutoCloseable {

    private final McpSyncClient client;

    public McpTestClient(int port, String bearerToken) {
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp")
                        .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                        .openConnectionOnStartup(false)
                        .httpRequestCustomizer((builder, method, uri, body, context) -> {
                            if (bearerToken != null) {
                                builder.header("Authorization", "Bearer " + bearerToken);
                            }
                        })
                        .build();
        this.client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("tgmcp-test-client", "test", "1.0.0"))
                .build();
    }

    public McpSchema.InitializeResult initialize() {
        return this.client.initialize();
    }

    public McpSchema.ListToolsResult listTools() {
        return this.client.listTools();
    }

    public McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
        return this.client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    }

    @Override
    public void close() {
        this.client.close();
    }
}
