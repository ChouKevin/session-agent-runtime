package com.java.system.sessionagent.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.mcp.McpConnectionManager;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RuntimeConfiguration.class, McpConfiguration.class, TestDependencies.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://localhost:5432/session_agent",
                    "spring.datasource.password=test-datasource-password");

    @Test
    void starts_with_zero_mcp_connections() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpConnectionManager.class);
            assertThat(context).hasSingleBean(ToolCatalog.class);
            assertThat(context).hasSingleBean(ConversationStore.class);
        });
    }

    @Test
    void keeps_unavailable_connections_nonfatal() {
        contextRunner.withPropertyValues(
                "session-agent.mcp.connections.remote.enabled=true",
                "session-agent.mcp.connections.remote.url=http://127.0.0.1:1/mcp")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        DataSource dataSource() {
            return Mockito.mock(DataSource.class);
        }

        @Bean
        ChatModel chatModel() {
            return Mockito.mock(ChatModel.class);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
