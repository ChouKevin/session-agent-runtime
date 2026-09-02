package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.mcp.McpConnectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RuntimePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpPropertiesConfiguration.class);

    @Test
    void binds_zero_connections_with_safe_operational_defaults() {
        contextRunner.run(context -> {
            McpConnectionProperties properties = context.getBean(McpConnectionProperties.class);

            assertThat(properties.connections()).isEmpty();
            assertThat(properties.refreshInterval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.maximumBackoff()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void retains_custom_endpoint_paths_and_optional_headers_while_capping_maximum_backoff() {
        contextRunner.withPropertyValues(
                "session-agent.mcp.connections.semantic.url=https://mcp.example/custom/mcp",
                "session-agent.mcp.connections.semantic.headers.Authorization=Bearer secret-token",
                "session-agent.mcp.connections.billing.enabled=false",
                "session-agent.mcp.connections.billing.url=https://billing.example/mcp",
                "session-agent.mcp.maximum-backoff=5m")
                .run(context -> {
                    McpConnectionProperties properties = context.getBean(McpConnectionProperties.class);

                    assertThat(properties.connections()).hasSize(2);
                    assertThat(properties.connections().get("semantic").url())
                            .isEqualTo(URI.create("https://mcp.example/custom/mcp"));
                    assertThat(properties.connections().get("semantic").headers())
                            .containsExactly(Map.entry("Authorization", "Bearer secret-token"));
                    assertThat(properties.connections().get("billing").enabled()).isFalse();
                    assertThat(properties.maximumBackoff()).isEqualTo(Duration.ofSeconds(60));
                });
    }

    @Test
    void copies_optional_headers_without_changing_the_exact_endpoint() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer secret-token");
        McpConnectionProperties.Connection connection = new McpConnectionProperties.Connection(
                true, URI.create("https://mcp.example/custom/mcp"), headers);

        headers.put("X-Changed", "not-retained");

        assertThat(connection.url()).isEqualTo(URI.create("https://mcp.example/custom/mcp"));
        assertThat(connection.headers()).containsExactly(Map.entry("Authorization", "Bearer secret-token"));
    }

    @Test
    void rejects_invalid_connection_names_and_unsafe_durations_without_echoing_connection_values() {
        McpConnectionProperties.Connection connection = new McpConnectionProperties.Connection(
                true, URI.create("https://mcp.example/custom/mcp"), Map.of("Authorization", "Bearer secret-token"));

        assertThatIllegalArgumentException().isThrownBy(() -> new McpConnectionProperties(
                Map.of("1-invalid", connection), Duration.ofSeconds(60), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(5)))
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("https://mcp.example/custom/mcp", "Bearer secret-token"));
        assertThatIllegalArgumentException().isThrownBy(() -> new McpConnectionProperties(
                Map.of(), Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(60), Duration.ofSeconds(5)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpConnectionProperties.class)
    static class McpPropertiesConfiguration {
    }
}
