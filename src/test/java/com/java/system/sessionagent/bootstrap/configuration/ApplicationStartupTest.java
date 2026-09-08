package com.java.system.sessionagent.bootstrap.configuration;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModels;
import com.java.system.sessionagent.model.adapter.springai.SpringAiConversationModel;
import com.java.system.sessionagent.mcp.adapter.sdk.McpConnectionManager;
import com.java.system.sessionagent.slack.application.SlackSocketLifecycle;
import com.java.system.sessionagent.slack.domain.SlackLifecycleState;
import com.java.system.sessionagent.slack.port.out.SlackSocketClient;
import com.java.system.sessionagent.slack.port.out.SlackSocketConnectionListener;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.mybatis.spring.annotation.MapperScan;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
            .withBean(SlackSocketClient.class, UnreachableSocketClient::new)
            .withUserConfiguration(RuntimeConfiguration.class, McpConfiguration.class, TestDependencies.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://localhost:5432/session_agent",
                    "spring.datasource.password=test-datasource-password",
                    "session-agent.model.default-model=google-primary",
                    "session-agent.model.models.google-primary.provider=google-genai",
                    "session-agent.model.models.google-primary.model-id=gemini-3.1-flash-lite",
                    "session-agent.model.models.google-primary.api-key=test-google-key",
                    "session-agent.model.models.google-primary.context-window-tokens=1048576",
                    "session-agent.model.models.google-primary.max-output-tokens=8192",
                    "session-agent.model.models.google-primary.timeout=250ms");

    @Test
    void starts_with_zero_mcp_connections() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpConnectionManager.class);
            assertThat(context).hasSingleBean(ToolCatalog.class);
            assertThat(context).hasSingleBean(ConversationStore.class);
            assertThat(context.getBean(ConversationModel.class).routeId().value()).isEqualTo("google-primary");
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
    void binds_the_configured_model_key_without_inferring_it_from_the_provider() {
        contextRunner.withPropertyValues("session-agent.model.default-model=google-primary")
                .run(context -> assertThat(context.getBean(ConversationModel.class).routeId().value())
                        .isEqualTo("google-primary"));
    }

    @Test
    void resolves_the_configured_google_catalog_capacity_and_rejects_unknown_models_without_an_override() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ConversationModel.class).descriptor())
                    .isEqualTo(new ModelDescriptor(
                            new ModelRouteId("google-primary"),
                            "gemini-3.1-flash-lite", 1_048_576));
        });
        contextRunner.withPropertyValues("session-agent.model.models.google-primary.context-window-tokens=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configures_google_timeout_and_disables_both_spring_ai_and_sdk_retries() {
        contextRunner.run(context -> {
            ConfiguredModels configuredModels = context.getBean(ConfiguredModels.class);
            SpringAiConversationModel conversationModel = (SpringAiConversationModel) configuredModels.defaultConversationModel();
            org.springframework.ai.google.genai.GoogleGenAiChatModel googleModel =
                    (org.springframework.ai.google.genai.GoogleGenAiChatModel) ReflectionTestUtils.getField(conversationModel, "chatModel");
            RetryTemplate retryTemplate = (RetryTemplate) ReflectionTestUtils.getField(googleModel, "retryTemplate");
            com.google.genai.Client client = (com.google.genai.Client) ReflectionTestUtils.getField(googleModel, "genAiClient");
            com.google.genai.ApiClient apiClient =
                    (com.google.genai.ApiClient) ReflectionTestUtils.getField(client, "apiClient");

            AtomicInteger attempts = new AtomicInteger();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> retryTemplate.invoke(() -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("test failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(attempts).hasValue(1);
            assertThat(apiClient.httpOptions().timeout()).contains(250);
            assertThat(apiClient.httpOptions().retryOptions())
                    .flatMap(com.google.genai.types.HttpRetryOptions::attempts)
                    .contains(1);
        });
    }

    @Test
    void keeps_runtime_startup_nonfatal_when_complete_slack_configuration_is_unreachable() {
        contextRunner.withPropertyValues(
                        "session-agent.slack.app-token=xapp-unreachable",
                        "session-agent.slack.bot-token=xoxb-unreachable",
                        "session-agent.slack.bot-user-id=UBOT",
                        "session-agent.slack.reconnect-delay=10ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SlackSocketLifecycle lifecycle = context.getBean(SlackSocketLifecycle.class);
                    awaitState(lifecycle, SlackLifecycleState.DEGRADED);
                    assertThat(lifecycle.state()).isEqualTo(SlackLifecycleState.DEGRADED);
                });
    }

    private static void awaitState(SlackSocketLifecycle lifecycle, SlackLifecycleState expected) {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (lifecycle.state() != expected && System.nanoTime() < deadline) { // cs-allow Enum identity comparison is intentional.
            Thread.onSpinWait();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan(
            basePackages = {"com.java.system.sessionagent.storage.adapter.postgres", "com.java.system.sessionagent.slack.adapter.postgres"},
            annotationClass = org.apache.ibatis.annotations.Mapper.class)
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

    static class UnreachableSocketClient implements SlackSocketClient {
        @Override
        public void start(SlackSocketConnectionListener listener) {
            throw new IllegalStateException("unreachable");
        }

        @Override
        public void stop() {
        }
    }
}
