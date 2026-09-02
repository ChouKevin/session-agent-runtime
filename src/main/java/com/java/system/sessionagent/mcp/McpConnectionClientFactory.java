package com.java.system.sessionagent.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Map;

@FunctionalInterface
interface McpConnectionClientFactory {

    McpConnectionClient create(McpConnectionProperties.Connection connection);

    static McpConnectionClientFactory sdk(McpConnectionProperties properties) {
        Assert.notNull(properties, "MCP connection properties must not be null");
        return connection -> createSdkClient(connection, properties);
    }

    private static McpConnectionClient createSdkClient(
            McpConnectionProperties.Connection connection,
            McpConnectionProperties properties) {
        Endpoint endpoint = splitEndpoint(connection.url());
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint.origin())
                .endpoint(endpoint.pathAndQuery())
                .connectTimeout(properties.requestTimeout())
                .httpRequestCustomizer((request, method, requestUri, body, context) -> applyHeaders(request, connection.headers()))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(properties.requestTimeout())
                .initializationTimeout(properties.requestTimeout())
                .build();
        client.initialize();
        return new SdkConnectionClient(client);
    }

    static Endpoint splitEndpoint(URI configuredUrl) {
        Assert.notNull(configuredUrl, "MCP endpoint URL must not be null");
        if (StringUtils.hasText(configuredUrl.getRawUserInfo()) || StringUtils.hasText(configuredUrl.getRawFragment())) {
            throw new IllegalArgumentException("MCP endpoint cannot safely be represented by the streamable HTTP transport");
        }
        String rawPath = configuredUrl.getRawPath();
        String path = StringUtils.hasText(rawPath) ? rawPath : "/";
        String rawQuery = configuredUrl.getRawQuery();
        String endpoint = StringUtils.hasText(rawQuery) ? path + "?" + rawQuery : path;
        try {
            URI origin = new URI(configuredUrl.getScheme(), null, configuredUrl.getHost(), configuredUrl.getPort(), "/", null, null);
            return new Endpoint(origin.toString(), endpoint);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("MCP endpoint cannot safely be represented by the streamable HTTP transport", exception);
        }
    }

    static void applyHeaders(java.net.http.HttpRequest.Builder request, Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
    }

    record Endpoint(String origin, String pathAndQuery) {
    }

    final class SdkConnectionClient implements McpConnectionClient {

        private final McpSyncClient client;

        private SdkConnectionClient(McpSyncClient client) {
            this.client = client;
        }

        @Override
        public McpSchema.InitializeResult initialize() {
            return client.initialize();
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            return client.listTools();
        }

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
            return client.callTool(request);
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
