package com.java.system.sessionagent.bootstrap.configuration;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.google.genai.errors.ApiException;
import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.application.SessionModelService;
import com.java.system.sessionagent.conversation.application.ConversationQueryService;
import com.java.system.sessionagent.conversation.application.JobCancellationService;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.ExternalSessionReferenceQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.in.MessageJobCancellationPort;
import com.java.system.sessionagent.conversation.port.in.SessionModelPort;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.bootstrap.adapter.metrics.MicrometerConversationTelemetry;
import com.java.system.sessionagent.model.adapter.springai.SpringAiConversationModel;
import com.java.system.sessionagent.model.adapter.springai.SpringAiMessagePartsHandler;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModelBinding;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModels;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.model.adapter.springai.SystemPrompt;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresConversationStore;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresConversationMapper;
import com.java.system.sessionagent.storage.adapter.postgres.jdbc.PostgresIntakeCapacity;
import com.java.system.sessionagent.storage.adapter.postgres.jdbc.PostgresUuidTypeHandler;
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
import com.java.system.sessionagent.skill.port.SkillLibrary;
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
import org.springframework.util.StringUtils;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RuntimeProperties.class, RuntimeProperties.Datasource.class, SlackProperties.class})
public class RuntimeConfiguration {

    private static final String GOOGLE_GENAI_API_ROUTE = "https://generativelanguage.googleapis.com";
    private static final String OPENAI_DEFAULT_API_ROUTE = "https://api.openai.com/v1";
    private static final String GOOGLE_GENAI_GENERATE_CONTENT_OPERATION = "models/{model}:generateContent";
    private static final String OPENAI_CHAT_COMPLETIONS_OPERATION = "/chat/completions";

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
        return assembleConfiguredModels(promptResource, conversationTelemetry, objectMapper, runtimeProperties,
                RuntimeConfiguration::chatModel);
    }

    static ConfiguredModels assembleConfiguredModels(
            SystemPrompt promptResource,
            ConversationTelemetry conversationTelemetry,
            ObjectMapper objectMapper,
            RuntimeProperties runtimeProperties,
            ConfiguredChatModelBuilder modelBuilder) {
        Map<String, EffectiveModelProfile> profiles = canonicalProfiles(runtimeProperties.model().models());
        Map<String, ConfiguredModelBinding> bindings = new LinkedHashMap<>();
        List<AutoCloseable> resources = new ArrayList<>();
        try {
            for (Map.Entry<String, EffectiveModelProfile> entry : profiles.entrySet()) {
                String key = entry.getKey();
                EffectiveModelProfile profile = entry.getValue();
                ModelRouteId routeId = new ModelRouteId(key);
                CreatedChatModel createdModel = modelBuilder.create(profile);
                createdModel.ownedResource().ifPresent(resources::add);
                SpringAiConversationModel conversationModel = new SpringAiConversationModel(createdModel.chatModel(), promptResource,
                        conversationTelemetry, objectMapper,
                        new SpringAiMessagePartsHandler(routeId),
                        new ModelDescriptor(routeId, profile.profile().modelId(), profile.profile().contextWindowTokens()),
                        RuntimeConfiguration::isGoogleTransientFailure);
                bindings.put(key, new ConfiguredModelBinding(key, fingerprint(profile), conversationModel));
            }
            return new ConfiguredModels(bindings, runtimeProperties.model().defaultModel(), resources);
        } catch (RuntimeException | Error failure) {
            closeResources(resources, failure);
            throw failure;
        }
    }

    @Bean
    ConversationModel conversationModel(ConfiguredModels configuredModels) {
        return configuredModels.defaultConversationModel();
    }

    private static CreatedChatModel chatModel(EffectiveModelProfile effectiveProfile) {
        RuntimeProperties.ModelProfile profile = effectiveProfile.profile();
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
            com.google.genai.Client client = com.google.genai.Client.builder()
                    .apiKey(profile.apiKey())
                    .httpOptions(httpOptions.build())
                    .build();
            try {
                GoogleGenAiChatModel model = GoogleGenAiChatModel.builder()
                        .genAiClient(client)
                        .options(options.build())
                        .retryTemplate(new org.springframework.core.retry.RetryTemplate(
                                org.springframework.core.retry.RetryPolicy.withMaxRetries(0)))
                        .build();
                AutoCloseable resource = model::destroy;
                return new CreatedChatModel(model, Optional.of(resource));
            } catch (RuntimeException | Error failure) {
                closeResource(client, failure);
                throw failure;
            }
        }
        if ("openai".equals(profile.provider())) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .apiKey(profile.apiKey())
                    .baseUrl(effectiveProfile.apiEndpoint())
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
            return new CreatedChatModel(OpenAiChatModel.builder().options(options.build()).build(), Optional.empty());
        }
        throw new IllegalArgumentException("Unsupported configured model provider");
    }

    private static Map<String, EffectiveModelProfile> canonicalProfiles(
            Map<String, RuntimeProperties.ModelProfile> configuredProfiles) {
        Map<String, EffectiveModelProfile> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeProperties.ModelProfile> entry : configuredProfiles.entrySet()) {
            String key = entry.getKey();
            new ModelRouteId(key);
            profiles.put(key, canonicalProfile(entry.getValue()));
        }
        return profiles;
    }

    private static EffectiveModelProfile canonicalProfile(RuntimeProperties.ModelProfile profile) {
        Assert.notNull(profile, "Configured model profile must not be null");
        if ("google-genai".equals(profile.provider())) {
            Assert.isTrue(!StringUtils.hasText(profile.reasoningEffort()),
                    "Google model profiles must not set OpenAI reasoning effort");
            Assert.isTrue(!StringUtils.hasText(profile.baseUrl()),
                    "Google model profiles must not set an OpenAI base URL");
            if (profile.timeout() != null) { // cs-allow Google SDK accepts a bounded millisecond timeout
                timeoutMillis(profile.timeout());
            }
            return new EffectiveModelProfile(profile, GOOGLE_GENAI_API_ROUTE, GOOGLE_GENAI_GENERATE_CONTENT_OPERATION);
        }
        if ("openai".equals(profile.provider())) {
            return new EffectiveModelProfile(profile, canonicalOpenAiRoute(profile.baseUrl()), OPENAI_CHAT_COMPLETIONS_OPERATION);
        }
        throw new IllegalArgumentException("Unsupported configured model provider");
    }

    private static String canonicalOpenAiRoute(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return OPENAI_DEFAULT_API_ROUTE;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static boolean isGoogleTransientFailure(Throwable failure) {
        return failure instanceof ApiException providerFailure && isTransientHttpStatus(providerFailure.code());
    }

    private static boolean isTransientHttpStatus(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }

    private static void closeResources(List<AutoCloseable> resources, Throwable failure) {
        for (AutoCloseable resource : resources) {
            closeResource(resource, failure);
        }
    }

    private static void closeResource(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Exception | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static int timeoutMillis(Duration timeout) {
        long milliseconds = timeout.toMillis();
        Assert.isTrue(milliseconds > 0, "Google model timeout must be at least one millisecond");
        Assert.isTrue(milliseconds <= Integer.MAX_VALUE, "Google model timeout must fit SDK milliseconds");
        return (int) milliseconds;
    }

    static String fingerprint(EffectiveModelProfile effectiveProfile) {
        RuntimeProperties.ModelProfile profile = effectiveProfile.profile();
        String normalized = String.join("\n", profile.provider(), effectiveProfile.apiEndpoint(), effectiveProfile.apiOperation(),
                profile.modelId(),
                String.valueOf(profile.contextWindowTokens()), String.valueOf(profile.maxOutputTokens()),
                String.valueOf(profile.temperature()), String.valueOf(profile.topP()), String.valueOf(profile.timeout()),
                String.valueOf(profile.reasoningEffort()));
        try {
            return "v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    record EffectiveModelProfile(RuntimeProperties.ModelProfile profile, String apiEndpoint, String apiOperation) {
    }

    @FunctionalInterface
    interface ConfiguredChatModelBuilder {
        CreatedChatModel create(EffectiveModelProfile profile);
    }

    record CreatedChatModel(ChatModel chatModel, Optional<AutoCloseable> ownedResource) {
        CreatedChatModel {
            Assert.notNull(chatModel, "Configured chat model must not be null");
            Assert.notNull(ownedResource, "Configured model ownership must not be null");
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
    PostgresIntakeCapacity postgresIntakeCapacity(DataSource dataSource, RuntimeProperties properties) {
        return new PostgresIntakeCapacity(dataSource, properties.worker().maxUnfinishedJobs());
    }

    @Bean
    ConversationStore conversationStore(
            PostgresConversationMapper mapper,
            DataSource dataSource,
            Clock runtimeClock,
            ObjectMapper objectMapper,
            PostgresIntakeCapacity intakeCapacity) {
        return new PostgresConversationStore(mapper, dataSource, runtimeClock, objectMapper, intakeCapacity);
    }

    @Bean
    ConversationTelemetry conversationTelemetry(MeterRegistry meterRegistry) {
        return new MicrometerConversationTelemetry(meterRegistry);
    }

    @Bean
    MessageIntakePort messageIntakePort(
            ConversationStore conversationStore,
            ConfiguredModels configuredModels,
            ConversationTelemetry conversationTelemetry) {
        return new MessageService(conversationStore, configuredModels, conversationTelemetry);
    }

    @Bean
    SessionModelPort sessionModelPort(ConversationStore conversationStore, ConfiguredModels configuredModels) {
        return new SessionModelService(conversationStore, configuredModels);
    }

    @Bean
    MessageSubmissionPort slackRootIntakePort(
            SlackDatabaseMapper mapper,
            DataSource dataSource,
            MessageIntakePort messageIntakePort,
            MessageJobCancellationPort cancellationPort,
            Clock runtimeClock,
            PostgresIntakeCapacity intakeCapacity) {
        return new PostgresMessageSubmission(mapper, dataSource, messageIntakePort, cancellationPort, runtimeClock, intakeCapacity);
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
            ConfiguredModels configuredModels,
            ToolCatalog toolCatalog) {
        return new ConversationQueryService(conversationStore, configuredModels, toolCatalog, new ContextUsageEstimator());
    }

    @Bean
    MessageJobCancellationPort messageJobCancellationPort(ConversationStore conversationStore) {
        return new JobCancellationService(conversationStore);
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
            ConfiguredModels configuredModels,
            ToolCatalog toolCatalog,
            SkillLibrary skillLibrary,
            Clock runtimeClock,
            RuntimeProperties properties,
            MessageJobRetryPolicy messageJobRetryPolicy,
            ConversationTelemetry conversationTelemetry) {
        return new JobProcessor(conversationStore, configuredModels, toolCatalog, skillLibrary, runtimeClock,
                properties.model().maxModelCallsPerMessage(), messageJobRetryPolicy, conversationTelemetry);
    }

    @Bean
    WorkerProperties workerProperties(RuntimeProperties properties) {
        Duration renewalInterval = properties.worker().lockDuration().dividedBy(3);
        return new WorkerProperties(properties.worker().lockDuration(), renewalInterval,
                properties.worker().pollDelay(), properties.worker().concurrency());
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
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread renewalWorker = new Thread(runnable, "session-agent-worker-renewal");
            renewalWorker.setDaemon(true);
            return renewalWorker;
        });
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService workerExecutor(WorkerProperties workerProperties) {
        return Executors.newFixedThreadPool(workerProperties.concurrency(), runnable -> {
            Thread worker = new Thread(runnable, "session-agent-worker");
            worker.setDaemon(true);
            return worker;
        });
    }

    @Bean
    MessageJobWorker messageJobWorker(
            ConversationStore conversationStore,
            JobProcessor messageJobService,
            WorkerProperties workerProperties,
            Clock runtimeClock,
            ScheduledExecutorService workerScheduler,
            ExecutorService workerExecutor,
            ConversationTelemetry conversationTelemetry) {
        return new MessageJobWorker(conversationStore, messageJobService, workerProperties, runtimeClock, workerScheduler,
                workerExecutor, "session-agent-worker", conversationTelemetry);
    }
}
