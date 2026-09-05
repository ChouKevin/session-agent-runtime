package com.java.system.sessionagent.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.mcp.McpConnectionManager;
import com.java.system.sessionagent.model.SpringAiConversationModel;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
            assertThat(context.getBean(SpringAiConversationModel.class).routeId().value()).isEqualTo("google-genai");
        });
    }

    @Test
    void keeps_unavailable_connections_nonfatal() {
        contextRunner.withPropertyValues(
                "session-agent.mcp.connections.remote.enabled=true",
                "session-agent.mcp.connections.remote.url=http://127.0.0.1:1/mcp")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void binds_the_configured_model_route_without_inferring_it_from_the_provider() {
        contextRunner.withPropertyValues("session-agent.model.route-id=gemini-primary")
                .run(context -> assertThat(context.getBean(SpringAiConversationModel.class).routeId().value())
                        .isEqualTo("gemini-primary"));
    }

    @Test
    void resolves_the_configured_google_catalog_capacity_and_rejects_unknown_models_without_an_override() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SpringAiConversationModel.class).descriptor())
                    .isEqualTo(new ModelDescriptor(
                            new ModelRouteId("google-genai"),
                            "gemini-3.1-flash-lite", 1_048_576));
        });
        contextRunner.withPropertyValues("spring.ai.google.genai.chat.options.model=unknown-model")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        DataSource dataSource() {
            return Mockito.mock(DataSource.class);
        }

        @Bean
        ChatModel chatModel(Environment environment) {
            ChatModel chatModel = Mockito.mock(ChatModel.class);
            String modelId = environment.getProperty("spring.ai.google.genai.chat.options.model", "gemini-3.1-flash-lite");
            Mockito.when(chatModel.getOptions()).thenReturn(GoogleGenAiChatOptions.builder().model(modelId).build());
            return chatModel;
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
