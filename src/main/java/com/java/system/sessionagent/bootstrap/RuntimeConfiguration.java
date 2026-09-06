package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.application.ConversationMessageService;
import com.java.system.sessionagent.conversation.application.ConversationQueryService;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.ExternalSessionReferenceQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.model.SpringAiConversationModel;
import com.java.system.sessionagent.model.GoogleGenAiThoughtSignatureHandler;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.model.PromptResource;
import com.java.system.sessionagent.storage.PostgresConversationStore;
import com.java.system.sessionagent.slack.SlackBoltSocketClient;
import com.java.system.sessionagent.slack.SlackEventAdapter;
import com.java.system.sessionagent.slack.SlackPostgresRootIntake;
import com.java.system.sessionagent.slack.SlackPostgresSessionLookup;
import com.java.system.sessionagent.slack.SlackPermalinkParser;
import com.java.system.sessionagent.slack.SlackProperties;
import com.java.system.sessionagent.slack.SlackRootIntakePort;
import com.java.system.sessionagent.slack.SlackSocketClient;
import com.java.system.sessionagent.slack.SlackSocketLifecycle;
import com.java.system.sessionagent.slack.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.SlackDeliveryLifecycle;
import com.java.system.sessionagent.slack.SlackDeliveryStore;
import com.java.system.sessionagent.slack.SlackDeliveryWorker;
import com.java.system.sessionagent.slack.SlackPostgresDeliveryStore;
import com.java.system.sessionagent.slack.SlackSdkWebApi;
import com.java.system.sessionagent.slack.SlackWebApi;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.worker.MessageJobWorker;
import com.java.system.sessionagent.worker.WorkerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RuntimeProperties.class, RuntimeProperties.Datasource.class, SlackProperties.class})
public class RuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper runtimeObjectMapper() {
        ObjectMapper objectMapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        objectMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return objectMapper;
    }

    @Bean
    public JsonMapperBuilderCustomizer strictJsonMapper() {
        return builder -> builder
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual,
                        config -> config
                                .setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Integer,
                                        tools.jackson.databind.cfg.CoercionAction.Fail)
                                .setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Float,
                                        tools.jackson.databind.cfg.CoercionAction.Fail)
                                .setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Boolean,
                                        tools.jackson.databind.cfg.CoercionAction.Fail));
    }

    @Bean
    Clock runtimeClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    PromptResource promptResource() {
        return new PromptResource();
    }

    @Bean
    SpringAiConversationModel conversationModel(
            ChatModel chatModel,
            PromptResource promptResource,
            ConversationTelemetry conversationTelemetry,
            ObjectMapper objectMapper,
            RuntimeProperties runtimeProperties) {
        ModelRouteId routeId = new ModelRouteId(runtimeProperties.model().routeId());
        return new SpringAiConversationModel(chatModel, promptResource, conversationTelemetry, objectMapper,
                new GoogleGenAiThoughtSignatureHandler(routeId, objectMapper),
                googleGenAiDescriptor(chatModel, routeId, runtimeProperties.model().contextWindowTokens()));
    }

    private static ModelDescriptor googleGenAiDescriptor(
            ChatModel chatModel,
            ModelRouteId routeId,
            Integer contextWindowOverride) {
        ChatOptions options = chatModel.getOptions();
        String modelId = options instanceof GoogleGenAiChatOptions googleOptions
                ? googleOptions.getModel() : "gemini-3.1-flash-lite";
        org.springframework.util.Assert.hasText(modelId, "Google GenAI model ID must not be blank");
        long catalogWindow = "gemini-3.1-flash-lite".equals(modelId) ? 1_048_576L : 0L;
        long effectiveWindow = Objects.isNull(contextWindowOverride) ? catalogWindow : contextWindowOverride.longValue();
        org.springframework.util.Assert.isTrue(effectiveWindow > 0, "Model context window capacity must be configured");
        return new ModelDescriptor(routeId, modelId, effectiveWindow);
    }

    @Bean
    ConversationStore conversationStore(DataSource dataSource, Clock runtimeClock, ObjectMapper objectMapper) {
        return new PostgresConversationStore(dataSource, runtimeClock, objectMapper);
    }

    @Bean
    ConversationTelemetry conversationTelemetry(MeterRegistry meterRegistry) {
        return new MicrometerConversationTelemetry(meterRegistry);
    }

    @Bean
    MessageIntakePort messageIntakePort(ConversationStore conversationStore, ConversationTelemetry conversationTelemetry) {
        return new ConversationMessageService(conversationStore, conversationTelemetry);
    }

    @Bean
    SlackRootIntakePort slackRootIntakePort(DataSource dataSource, MessageIntakePort messageIntakePort, Clock runtimeClock) {
        return new SlackPostgresRootIntake(dataSource, messageIntakePort, runtimeClock);
    }

    @Bean
    SlackEventAdapter slackEventAdapter(SlackProperties properties, SlackRootIntakePort slackRootIntakePort) {
        return new SlackEventAdapter(properties.botUserId(), slackRootIntakePort);
    }

    @Bean
    @ConditionalOnMissingBean(SlackSocketClient.class)
    SlackSocketClient slackSocketClient(SlackProperties properties, SlackEventAdapter slackEventAdapter) {
        return new SlackBoltSocketClient(properties, slackEventAdapter);
    }

    @Bean
    SlackSocketLifecycle slackSocketLifecycle(SlackSocketClient slackSocketClient, SlackProperties properties) {
        return new SlackSocketLifecycle(slackSocketClient, properties);
    }

    @Bean
    SlackDeliveryProperties slackDeliveryProperties(SlackProperties properties) {
        SlackProperties.Delivery delivery = properties.delivery();
        return new SlackDeliveryProperties(delivery.leaseDuration(), delivery.initialBackoff(), delivery.maximumBackoff(),
                delivery.maximumAttempts());
    }

    @Bean
    SlackDeliveryStore slackDeliveryStore(DataSource dataSource) {
        return new SlackPostgresDeliveryStore(dataSource);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(SlackWebApi.class)
    SlackWebApi slackWebApi(SlackProperties properties) {
        return new SlackSdkWebApi(properties);
    }

    @Bean
    SlackDeliveryWorker slackDeliveryWorker(
            SlackDeliveryStore slackDeliveryStore,
            SlackWebApi slackWebApi,
            SlackDeliveryProperties slackDeliveryProperties) {
        return new SlackDeliveryWorker(slackDeliveryStore, slackWebApi, slackDeliveryProperties, "session-agent-slack-delivery");
    }

    @Bean
    SlackDeliveryLifecycle slackDeliveryLifecycle(SlackDeliveryWorker slackDeliveryWorker, SlackProperties properties) {
        return new SlackDeliveryLifecycle(slackDeliveryWorker, properties);
    }

    @Bean
    ConversationQueryPort conversationQueryPort(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            ToolCatalog toolCatalog) {
        return new ConversationQueryService(conversationStore, conversationModel, toolCatalog, new ContextUsageEstimator());
    }

    @Bean
    SlackPermalinkParser slackPermalinkParser() {
        return new SlackPermalinkParser();
    }

    @Bean
    ExternalSessionReferenceQueryPort externalSessionReferenceQueryPort(
            DataSource dataSource,
            SlackPermalinkParser slackPermalinkParser) {
        return new SlackPostgresSessionLookup(dataSource, slackPermalinkParser);
    }

    @Bean
    MessageJobRetryPolicy messageJobRetryPolicy(RuntimeProperties properties) {
        return new MessageJobRetryPolicy(properties.worker().transientRetries(), properties.worker().maximumBackoff());
    }

    @Bean
    MessageJobService messageJobService(
            ConversationStore conversationStore,
            SpringAiConversationModel conversationModel,
            ToolCatalog toolCatalog,
            Clock runtimeClock,
            RuntimeProperties properties,
            MessageJobRetryPolicy messageJobRetryPolicy,
            ConversationTelemetry conversationTelemetry) {
        return new MessageJobService(conversationStore, conversationModel, toolCatalog, runtimeClock,
                properties.model().maxModelCallsPerMessage(), messageJobRetryPolicy, conversationTelemetry);
    }

    @Bean
    WorkerProperties workerProperties(RuntimeProperties properties) {
        Duration renewalInterval = properties.worker().lockDuration().dividedBy(3);
        return new WorkerProperties(properties.worker().lockDuration(), renewalInterval);
    }

    @Bean
    TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("session-agent-poll-");
        return scheduler;
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService workerScheduler() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    @Bean
    MessageJobWorker messageJobWorker(
            ConversationStore conversationStore,
            MessageJobService messageJobService,
            WorkerProperties workerProperties,
            Clock runtimeClock,
            ScheduledExecutorService workerScheduler,
            ConversationTelemetry conversationTelemetry) {
        return new MessageJobWorker(conversationStore, messageJobService, workerProperties, runtimeClock, workerScheduler,
                "session-agent-worker", conversationTelemetry);
    }
}
