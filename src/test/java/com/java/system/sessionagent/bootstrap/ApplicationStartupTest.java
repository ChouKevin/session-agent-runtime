package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallRecorder;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.model.GoogleConversationModel;
import com.java.system.sessionagent.model.PromptResource;
import com.java.system.sessionagent.storage.PostgresModelCallRecorder;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

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
            assertThat(context.getBean(DirectToolRegistry.class).snapshot().definitions()).hasSize(16);
            assertThat(context.getBeansOfType(ConversationStore.class)).hasSize(1);
            assertThat(context).hasSingleBean(ModelCallRecorder.class);
            assertThat(context.getBean(ModelCallRecorder.class)).isInstanceOf(PostgresModelCallRecorder.class);
            assertThat(context.getBeansOfType(com.java.system.sessionagent.model.SpringAiConversationModel.class)).hasSize(1);
            assertThat(context.getBeansOfType(com.java.system.sessionagent.conversation.application.MessageJobService.class)).hasSize(1);
            assertThat(context.getBeansOfType(com.java.system.sessionagent.worker.MessageJobWorker.class)).hasSize(1);
        });
    }

    @Test
    void keepsScheduledPollingOffTheClaimRenewalExecutor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TaskScheduler.class);
            assertThat(context).hasSingleBean(ScheduledExecutorService.class);
            assertThat(context.getBean("taskScheduler")).isNotSameAs(context.getBean("workerScheduler"));
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

    @ParameterizedTest
    @ValueSource(strings = {
            "session-agent.semantic.base-url= ",
            "session-agent.semantic.api-token= ",
            "session-agent.semantic.connect-timeout=0s",
            "session-agent.semantic.response-timeout=-1s",
            "session-agent.model.max-model-calls-per-message=0",
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

}
