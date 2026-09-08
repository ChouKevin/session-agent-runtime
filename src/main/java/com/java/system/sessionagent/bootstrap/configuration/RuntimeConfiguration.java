package com.java.system.sessionagent.bootstrap.configuration;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.application.ConversationQueryService;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.ExternalSessionReferenceQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.bootstrap.adapter.metrics.MicrometerConversationTelemetry;
import com.java.system.sessionagent.model.adapter.springai.SpringAiConversationModel;
import com.java.system.sessionagent.model.adapter.springai.DiscardingContinuationHandler;
import com.java.system.sessionagent.model.adapter.springai.GoogleGenAiThoughtSignatureHandler;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModelBinding;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModels;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.model.adapter.springai.SystemPrompt;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresConversationStore;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresConversationMapper;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresUuidTypeHandler;
import com.java.system.sessionagent.slack.adapter.postgres.PostgresMessageSubmission;
import com.java.system.sessionagent.slack.adapter.postgres.SlackDatabaseMapper;
import com.java.system.sessionagent.slack.adapter.postgres.SlackPostgresDeliveryStore;
import com.java.system.sessionagent.slack.adapter.postgres.SlackPostgresSessionLookup;
import com.java.system.sessionagent.slack.adapter.sdk.SlackBoltSocketClient;
import com.java.system.sessionagent.slack.adapter.sdk.SlackSdkWebApi;
import com.java.system.sessionagent.slack.application.MessageHandler;
import com.java.system.sessionagent.slack.application.SlackDeliveryLifecycle;
import com.java.system.sessionagent.slack.application.SlackDeliveryWorker;
import com.java.system.sessionagent.slack.application.SlackSocketLifecycle;
import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.configuration.SlackProperties;
import com.java.system.sessionagent.slack.domain.SlackPermalinkParser;
import com.java.system.sessionagent.slack.port.out.MessageSubmissionPort;
import com.java.system.sessionagent.slack.port.out.SlackDeliveryStore;
import com.java.system.sessionagent.slack.port.out.SlackSocketClient;
import com.java.system.sessionagent.slack.port.out.SlackWebApi;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.worker.application.MessageJobWorker;
import com.java.system.sessionagent.worker.configuration.WorkerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.apache.ibatis.session.LocalCacheScope;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.Assert;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
    SystemPrompt promptResource() {
        return new SystemPrompt();
    }

    @Bean
    ConfiguredModels configuredModels(
            SystemPrompt promptResource,
            ConversationTelemetry conversationTelemetry,
            ObjectMapper objectMapper,
            RuntimeProperties runtimeProperties) {
        Map<String, ConfiguredModelBinding> bindings = new LinkedHashMap<>();
        List<AutoCloseable> resources = new ArrayList<>();
        for (Map.Entry<String, RuntimeProperties.ModelProfile> entry : runtimeProperties.model().models().entrySet()) {
            String key = entry.getKey();
            RuntimeProperties.ModelProfile profile = entry.getValue();
            ModelRouteId routeId = new ModelRouteId(key);
            ChatModel chatModel = chatModel(profile, resources);
            SpringAiConversationModel conversationModel = new SpringAiConversationModel(chatModel, promptResource,
                    conversationTelemetry, objectMapper, continuationHandler(profile.provider(), routeId, objectMapper),
                    new ModelDescriptor(routeId, profile.modelId(), profile.contextWindowTokens()));
            bindings.put(key, new ConfiguredModelBinding(key, fingerprint(profile), conversationModel));
        }
        return new ConfiguredModels(bindings, runtimeProperties.model().defaultModel(), resources);
    }

    @Bean
    ConversationModel conversationModel(ConfiguredModels configuredModels) {
        return configuredModels.defaultConversationModel();
    }

    private static ChatModel chatModel(RuntimeProperties.ModelProfile profile, List<AutoCloseable> resources) {
        if ("google-genai".equals(profile.provider())) {
            GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder()
                    .model(profile.modelId())
                    .maxOutputTokens(profile.maxOutputTokens())
                    .includeThoughts(false);
            if (profile.temperature() != null) { // cs-allow provider option is optional
                options.temperature(profile.temperature());
            }
            if (profile.topP() != null) { // cs-allow provider option is optional
                options.topP(profile.topP());
            }
            com.google.genai.types.HttpOptions.Builder httpOptions = com.google.genai.types.HttpOptions.builder()
                    .retryOptions(com.google.genai.types.HttpRetryOptions.builder().attempts(1).build());
            if (profile.timeout() != null) { // cs-allow provider option is optional
                httpOptions.timeout(timeoutMillis(profile.timeout()));
            }
            GoogleGenAiChatModel model = GoogleGenAiChatModel.builder()
                    .genAiClient(com.google.genai.Client.builder().apiKey(profile.apiKey()).httpOptions(httpOptions.build()).build())
                    .options(options.build())
                    .retryTemplate(new org.springframework.core.retry.RetryTemplate(
                            org.springframework.core.retry.RetryPolicy.withMaxRetries(0)))
                    .build();
            resources.add(model::destroy);
            return model;
        }
        if ("openai".equals(profile.provider())) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .apiKey(profile.apiKey())
                    .baseUrl(profile.baseUrl())
                    .model(profile.modelId())
                    .maxCompletionTokens(profile.maxOutputTokens())
                    .maxRetries(0)
                    .store(false)
                    .strict(false);
            if (profile.timeout() != null) { // cs-allow provider option is optional
                options.timeout(profile.timeout());
            }
            if (profile.temperature() != null) { // cs-allow provider option is optional
                options.temperature(profile.temperature());
            }
            if (profile.topP() != null) { // cs-allow provider option is optional
                options.topP(profile.topP());
            }
            if (profile.reasoningEffort() != null) { // cs-allow provider option is optional
                options.reasoningEffort(profile.reasoningEffort());
            }
            return OpenAiChatModel.builder().options(options.build()).build();
        }
        throw new IllegalArgumentException("Unsupported configured model provider");
    }

    private static int timeoutMillis(Duration timeout) {
        long milliseconds = timeout.toMillis();
        Assert.isTrue(milliseconds > 0, "Google model timeout must be at least one millisecond");
        return Math.toIntExact(milliseconds);
    }

    private static com.java.system.sessionagent.model.adapter.springai.SpringAiContinuationHandler continuationHandler(
            String provider,
            ModelRouteId routeId,
            ObjectMapper objectMapper) {
        if ("google-genai".equals(provider)) {
            return new GoogleGenAiThoughtSignatureHandler(routeId, objectMapper);
        }
        return new DiscardingContinuationHandler(routeId);
    }

    private static String fingerprint(RuntimeProperties.ModelProfile profile) {
        String normalized = String.join("\n", profile.provider(), profile.modelId(),
                String.valueOf(profile.baseUrl()), String.valueOf(profile.contextWindowTokens()),
                String.valueOf(profile.maxOutputTokens()), String.valueOf(profile.temperature()), String.valueOf(profile.topP()),
                String.valueOf(profile.timeout()), String.valueOf(profile.reasoningEffort()));
        try {
            return "v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    @Bean
    ConfigurationCustomizer runtimeMybatisConfigurationCustomizer() {
        return configuration -> {
            configuration.setCacheEnabled(false);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setReturnInstanceForEmptyRow(true);
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setArgNameBasedConstructorAutoMapping(true);
            configuration.getTypeHandlerRegistry().register(UUID.class, PostgresUuidTypeHandler.class);
        };
    }

    @Bean
    ConversationStore conversationStore(
            PostgresConversationMapper mapper,
            DataSource dataSource,
            Clock runtimeClock,
            ObjectMapper objectMapper) {
        return new PostgresConversationStore(mapper, dataSource, runtimeClock, objectMapper);
    }

    @Bean
    ConversationTelemetry conversationTelemetry(MeterRegistry meterRegistry) {
        return new MicrometerConversationTelemetry(meterRegistry);
    }

    @Bean
    MessageIntakePort messageIntakePort(ConversationStore conversationStore, ConversationTelemetry conversationTelemetry) {
        return new MessageService(conversationStore, conversationTelemetry);
    }

    @Bean
    MessageSubmissionPort slackRootIntakePort(
            SlackDatabaseMapper mapper,
            DataSource dataSource,
            MessageIntakePort messageIntakePort,
            Clock runtimeClock) {
        return new PostgresMessageSubmission(mapper, dataSource, messageIntakePort, runtimeClock);
    }

    @Bean
    MessageHandler slackEventAdapter(SlackProperties properties, MessageSubmissionPort slackRootIntakePort) {
        return new MessageHandler(properties.botUserId(), slackRootIntakePort);
    }

    @Bean
    @ConditionalOnMissingBean(SlackSocketClient.class)
    SlackSocketClient slackSocketClient(SlackProperties properties, MessageHandler slackEventAdapter) {
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
    SlackDeliveryStore slackDeliveryStore(SlackDatabaseMapper mapper, DataSource dataSource) {
        return new SlackPostgresDeliveryStore(mapper, dataSource);
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
            SlackDatabaseMapper mapper,
            DataSource dataSource,
            SlackPermalinkParser slackPermalinkParser) {
        return new SlackPostgresSessionLookup(mapper, slackPermalinkParser);
    }

    @Bean
    MessageJobRetryPolicy messageJobRetryPolicy(RuntimeProperties properties) {
        return new MessageJobRetryPolicy(properties.worker().transientRetries(), properties.worker().maximumBackoff());
    }

    @Bean
    JobProcessor messageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            ToolCatalog toolCatalog,
            Clock runtimeClock,
            RuntimeProperties properties,
            MessageJobRetryPolicy messageJobRetryPolicy,
            ConversationTelemetry conversationTelemetry) {
        return new JobProcessor(conversationStore, conversationModel, toolCatalog, runtimeClock,
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
            JobProcessor messageJobService,
            WorkerProperties workerProperties,
            Clock runtimeClock,
            ScheduledExecutorService workerScheduler,
            ConversationTelemetry conversationTelemetry) {
        return new MessageJobWorker(conversationStore, messageJobService, workerProperties, runtimeClock, workerScheduler,
                "session-agent-worker", conversationTelemetry);
    }
}
