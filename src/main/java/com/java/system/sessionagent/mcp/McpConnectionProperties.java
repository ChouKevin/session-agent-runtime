package com.java.system.sessionagent.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@ConfigurationProperties("session-agent.mcp")
public record McpConnectionProperties(
        Map<String, Connection> connections,
        @DefaultValue("60s") Duration refreshInterval,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("60s") Duration maximumBackoff,
        @DefaultValue("5s") Duration shutdownTimeout) {

    private static final Pattern CONNECTION_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,31}");
    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(60);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_BACKOFF_CAP = Duration.ofSeconds(60);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    public McpConnectionProperties {
        connections = copyConnections(connections);
        refreshInterval = positiveOrDefault(refreshInterval, DEFAULT_REFRESH_INTERVAL, "MCP refresh interval must be positive");
        requestTimeout = positiveOrDefault(requestTimeout, DEFAULT_REQUEST_TIMEOUT, "MCP request timeout must be positive");
        initialBackoff = positiveOrDefault(initialBackoff, DEFAULT_INITIAL_BACKOFF, "MCP initial backoff must be positive");
        maximumBackoff = cappedMaximumBackoff(maximumBackoff);
        shutdownTimeout = positiveOrDefault(shutdownTimeout, DEFAULT_SHUTDOWN_TIMEOUT, "MCP shutdown timeout must be positive");
    }

    static void validateConnectionName(String connectionName) {
        Assert.hasText(connectionName, "MCP connection name must not be blank");
        Assert.isTrue(CONNECTION_NAME.matcher(connectionName).matches(), "MCP connection name must be portable");
    }

    private static Map<String, Connection> copyConnections(Map<String, Connection> sourceConnections) {
        Map<String, Connection> safeConnections = Optional.ofNullable(sourceConnections).orElseGet(Map::of);
        LinkedHashMap<String, Connection> copiedConnections = new LinkedHashMap<>();
        for (Map.Entry<String, Connection> entry : safeConnections.entrySet()) {
            String connectionName = Objects.requireNonNull(entry.getKey(), "MCP connection name must not be null");
            Connection connection = Objects.requireNonNull(entry.getValue(), "MCP connection must not be null");
            validateConnectionName(connectionName);
            copiedConnections.put(connectionName, connection);
        }
        return Collections.unmodifiableMap(copiedConnections);
    }

    private static Duration positiveOrDefault(Duration value, Duration defaultValue, String message) {
        Duration resolvedValue = Optional.ofNullable(value).orElse(defaultValue);
        Assert.isTrue(!resolvedValue.isNegative() && !resolvedValue.isZero(), message);
        return resolvedValue;
    }

    private static Duration cappedMaximumBackoff(Duration value) {
        Duration resolvedValue = positiveOrDefault(value, MAXIMUM_BACKOFF_CAP, "MCP maximum backoff must be positive");
        if (resolvedValue.compareTo(MAXIMUM_BACKOFF_CAP) > 0) {
            return MAXIMUM_BACKOFF_CAP;
        }
        return resolvedValue;
    }

    public record Connection(
            @DefaultValue("true") boolean enabled,
            URI url,
            Map<String, String> headers) {

        public Connection {
            Assert.notNull(url, "MCP endpoint must not be null");
            Assert.isTrue(url.isAbsolute(), "MCP endpoint must be absolute");
            Assert.isTrue(isHttpScheme(url), "MCP endpoint must use HTTP or HTTPS");
            Assert.hasText(url.getHost(), "MCP endpoint must include a host");
            headers = copyHeaders(headers);
        }

        private static boolean isHttpScheme(URI url) {
            String scheme = url.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        }

        private static Map<String, String> copyHeaders(Map<String, String> sourceHeaders) {
            Map<String, String> safeHeaders = Optional.ofNullable(sourceHeaders).orElseGet(Map::of);
            LinkedHashMap<String, String> copiedHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : safeHeaders.entrySet()) {
                String headerName = Objects.requireNonNull(entry.getKey(), "MCP header name must not be null");
                String headerValue = Objects.requireNonNull(entry.getValue(), "MCP header value must not be null");
                copiedHeaders.put(headerName, headerValue);
            }
            return Collections.unmodifiableMap(copiedHeaders);
        }
    }
}
