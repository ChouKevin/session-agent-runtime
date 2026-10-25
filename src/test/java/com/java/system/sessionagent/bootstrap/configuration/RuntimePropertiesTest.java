package com.java.system.sessionagent.bootstrap.configuration;

import com.java.system.sessionagent.mcp.configuration.McpConnectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
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
    void starts_with_bundled_config_data_and_no_mcp_connections() {
        SpringApplication application = bundledConfigApplication();

        try (ConfigurableApplicationContext context = application.run(
                "--spring.config.location=classpath:application.yml")) {
            McpConnectionProperties properties = context.getBean(McpConnectionProperties.class);

            assertThat(properties.connections()).isEmpty();
        }
    }

    @Test
    void binds_named_mcp_connections_over_bundled_config_data() {
        SpringApplication application = bundledConfigApplication();

        try (ConfigurableApplicationContext context = application.run(
                "--spring.config.location=classpath:application.yml",
                "--session-agent.mcp.connections.reader.url=https://reader.example/custom/mcp",
                "--session-agent.mcp.connections.reader.headers.Authorization=Bearer test-token",
                "--session-agent.mcp.connections.billing.enabled=false",
                "--session-agent.mcp.connections.billing.url=https://billing.example/mcp")) {
            McpConnectionProperties properties = context.getBean(McpConnectionProperties.class);

            assertThat(properties.connections()).containsOnlyKeys("reader", "billing");
            assertThat(properties.connections().get("reader").enabled()).isTrue();
            assertThat(properties.connections().get("reader").url())
                    .isEqualTo(URI.create("https://reader.example/custom/mcp"));
            assertThat(properties.connections().get("reader").headers())
                    .containsExactly(Map.entry("Authorization", "Bearer test-token"));
            assertThat(properties.connections().get("billing").enabled()).isFalse();
            assertThat(properties.connections().get("billing").url()).isEqualTo(URI.create("https://billing.example/mcp"));
        }
    }

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

    private static SpringApplication bundledConfigApplication() {
        SpringApplication application = new SpringApplication(McpPropertiesConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        // Binding tests do not own the production container's /app/logs location.
        application.setListeners(application.getListeners().stream()
                .filter(listener -> !(listener instanceof LoggingApplicationListener)).toList());
        return application;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpConnectionProperties.class)
    static class McpPropertiesConfiguration {
    }
}
