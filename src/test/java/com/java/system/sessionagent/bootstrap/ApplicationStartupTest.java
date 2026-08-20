package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.model.GoogleConversationModel;
import com.java.system.sessionagent.model.PromptResource;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.domain.ToolResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class ApplicationStartupTest {

    private static final String DATA_SOURCE_AUTO_CONFIGURATION =
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
    private static final String FLYWAY_AUTO_CONFIGURATION =
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RuntimeConfiguration.class, TestDependencies.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://localhost:5432/session_agent",
                    "spring.datasource.password=test-datasource-password",
                    "session-agent.semantic.api-token=test-semantic-token");

    @Test
    void createsOneOfEachStandaloneRuntimeAssemblyAndTheFullToolSet() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DirectToolRegistry.class)).hasSize(1);
            assertThat(context.getBean(DirectToolRegistry.class).snapshot(true).definitions()).hasSize(16);
            assertThat(context.getBeansOfType(ConversationStore.class)).hasSize(1);
            assertThat(context.getBeansOfType(com.java.system.sessionagent.model.GoogleConversationModel.class)).hasSize(1);
            assertThat(context.getBeansOfType(com.java.system.sessionagent.conversation.application.MessageJobService.class)).hasSize(1);
            assertThat(context.getBeansOfType(com.java.system.sessionagent.worker.MessageJobWorker.class)).hasSize(1);
        });
    }

    @Test
    void createsAProductionDataSourceFromConfiguredProperties() {
        ClassLoader classLoader = getClass().getClassLoader();
        assertThat(ClassUtils.isPresent(DATA_SOURCE_AUTO_CONFIGURATION, classLoader))
                .as("production JDBC auto-configuration must be available")
                .isTrue();
        Class<?> dataSourceAutoConfiguration = ClassUtils.resolveClassName(DATA_SOURCE_AUTO_CONFIGURATION, classLoader);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(dataSourceAutoConfiguration))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/session_agent",
                        "spring.datasource.username=session_agent",
                        "spring.datasource.password=test-datasource-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DataSource.class);
                });
    }

    @Test
    void makesProductionDatabaseMigrationsAvailableAtStartup() {
        ClassLoader classLoader = getClass().getClassLoader();

        assertThat(ClassUtils.isPresent(FLYWAY_AUTO_CONFIGURATION, classLoader))
                .as("production Flyway auto-configuration must be available")
                .isTrue();
    }

    @Test
    void failsEagerlyForMissingPromptAndDoesNotExposeConversationPorts() {
        contextRunner.withAllowBeanDefinitionOverriding(true).withUserConfiguration(MissingPrompt.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("Conversation system prompt could not be loaded");
            assertThatThrownBy(() -> context.getBean(com.java.system.sessionagent.conversation.port.in.MessageIntakePort.class))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void appliesTheBoundRuntimeModelNameToEachGoogleRequestWhilePreservingProviderOptions() {
        contextRunner.withAllowBeanDefinitionOverriding(true)
                .withUserConfiguration(RecordingGoogleChatModelDependency.class)
                .withPropertyValues("session-agent.model.name=gemini-3.1-flash-lite")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GoogleConversationModel model = context.getBean(GoogleConversationModel.class);
                    RecordingGoogleChatModel chatModel = context.getBean(RecordingGoogleChatModel.class);
                    ToolDefinition definition = new ToolDefinition(new ToolName("catalog"), "v1", "catalog",
                            "{\"type\":\"object\"}", ToolKind.CATALOG);
                    DirectToolRegistry registry = new DirectToolRegistry(List.of(new ToolRegistration<>(definition, Object.class,
                            ignored -> new ToolResult(Optional.empty(), Optional.empty(), "{}", false))));

                    ModelDecision decision = model.decide(new ModelRequest(List.of(new UserMessage(new SessionId("session-1"),
                            new SessionSequence(1), Optional.empty(), Instant.parse("2026-08-16T00:00:00Z"), MessageRole.USER,
                            "alice", "question")), registry.snapshot(false), false), usage -> { });

                    assertThat(decision).isInstanceOf(ModelDecision.UseTool.class);
                    assertThat(chatModel.prompt.getOptions()).isInstanceOf(GoogleGenAiChatOptions.class);
                    GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) chatModel.prompt.getOptions();
                    assertThat(options.getModel()).isEqualTo("gemini-3.1-flash-lite");
                    assertThat(options.getMaxOutputTokens()).isEqualTo(19);
                    assertThat(options.getIncludeThoughts()).isTrue();
                    assertThat(options.getIncludeServerSideToolInvocations()).isFalse();
                    assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                            .containsExactly("catalog");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "session-agent.semantic.base-url= ",
            "session-agent.semantic.api-token= ",
            "session-agent.semantic.connect-timeout=0s",
            "session-agent.semantic.response-timeout=-1s",
            "session-agent.model.name= ",
            "session-agent.worker.lock-duration=0s",
            "spring.datasource.url= ",
            "spring.datasource.password= "
    })
    void failsEagerlyForEachInvalidRuntimePropertyBeforeConversationPortsAreUsable(String invalidProperty) {
        contextRunner.withPropertyValues(invalidProperty)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThatThrownBy(() -> context.getBean(com.java.system.sessionagent.conversation.port.in.MessageIntakePort.class))
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void failsEagerlyWhenRequiredSemanticTokenOrDatasourcePasswordIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(RuntimeConfiguration.class, TestDependencies.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/session_agent",
                        "spring.datasource.password=test-datasource-password")
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner()
                .withUserConfiguration(RuntimeConfiguration.class, TestDependencies.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/session_agent",
                        "session-agent.semantic.api-token=test-semantic-token")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        DataSource dataSource() {
            return Mockito.mock(DataSource.class);
        }

        @Bean
        ChatModel chatModel() {
            ChatModel chatModel = Mockito.mock(ChatModel.class);
            GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                    .model(GoogleGenAiChatModel.ChatModel.GEMINI_3_1_FLASH_LITE)
                    .build();
            when(chatModel.getOptions()).thenReturn(options);
            return chatModel;
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.NOOP;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingPrompt {
        @Bean
        PromptResource promptResource() {
            return new PromptResource(new ClassPathResource("prompts/conversation/missing.md"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingGoogleChatModelDependency {
        @Bean
        RecordingGoogleChatModel chatModel() {
            return new RecordingGoogleChatModel();
        }
    }

    static final class RecordingGoogleChatModel implements ChatModel {
        private Prompt prompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .properties(Map.of("thoughtSignatures", List.of(new byte[]{1, 2, 3, 4})))
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "catalog-call", "function", "catalog", "{}")))
                    .build())));
        }

        @Override
        public GoogleGenAiChatOptions getOptions() {
            return GoogleGenAiChatOptions.builder()
                    .model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
                    .maxOutputTokens(19)
                    .includeServerSideToolInvocations(true)
                    .build();
        }
    }
}
