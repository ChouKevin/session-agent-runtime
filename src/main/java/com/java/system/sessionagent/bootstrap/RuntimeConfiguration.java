package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.application.ConversationMessageService;
import com.java.system.sessionagent.conversation.application.ConversationQueryService;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.port.in.ConversationQueryPort;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.RepositoryRevisionReader;
import com.java.system.sessionagent.conversation.port.out.RevisionLookup;
import com.java.system.sessionagent.model.GoogleConversationModel;
import com.java.system.sessionagent.model.PromptResource;
import com.java.system.sessionagent.semantic.SemanticFailure;
import com.java.system.sessionagent.semantic.domain.RepositoryId;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.storage.PostgresConversationStore;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.worker.MessageJobWorker;
import com.java.system.sessionagent.worker.WorkerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RuntimeProperties.class, RuntimeProperties.Datasource.class})
public class RuntimeConfiguration {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictJsonObjectMapper() {
        return builder -> builder.featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .postConfigurer(mapper -> mapper.coercionConfigFor(LogicalType.Textual)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
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
    RestClient semanticRestClient(RuntimeProperties properties, ObservationRegistry observationRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.semantic().connectTimeout());
        requestFactory.setReadTimeout(properties.semantic().responseTimeout());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.semantic().baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-Api-Token", properties.semantic().apiToken())
                .observationRegistry(observationRegistry);
        return builder.build();
    }

    @Bean
    SemanticRepositoryClient semanticRepositoryClient(RestClient semanticRestClient) {
        return new SemanticRepositoryClient(semanticRestClient);
    }

    @Bean
    SemanticSourceClient semanticSourceClient(RestClient semanticRestClient, SemanticRepositoryClient semanticRepositoryClient) {
        return new SemanticSourceClient(semanticRestClient, semanticRepositoryClient);
    }

    @Bean
    DirectToolRegistry directToolRegistry(SemanticRepositoryClient repositoryClient, SemanticSourceClient sourceClient) {
        return new DirectToolRegistry(new SemanticToolProvider(repositoryClient, sourceClient).registrations());
    }

    @Bean
    GoogleConversationModel conversationModel(ChatModel chatModel, PromptResource promptResource, ConversationTelemetry conversationTelemetry,
                                              RuntimeProperties properties) {
        return new GoogleConversationModel(chatModel, promptResource, conversationTelemetry, properties.model().name());
    }

    @Bean
    ConversationStore conversationStore(DataSource dataSource, Clock runtimeClock) {
        return new PostgresConversationStore(dataSource, runtimeClock);
    }

    @Bean
    ConversationTelemetry conversationTelemetry(MeterRegistry meterRegistry) {
        return new MicrometerConversationTelemetry(meterRegistry);
    }

    @Bean
    RepositoryRevisionReader repositoryRevisionReader(SemanticRepositoryClient semanticRepositoryClient) {
        return repositoryId -> {
            try {
                return new RevisionLookup.CurrentRevision(semanticRepositoryClient.currentRevision(new RepositoryId(repositoryId)).value());
            } catch (SemanticFailure failure) {
                return switch (failure.kind()) {
                    case UNKNOWN_REPOSITORY -> new RevisionLookup.UnknownRepository();
                    case TRANSIENT -> new RevisionLookup.TemporaryFailure();
                    case FORBIDDEN -> new RevisionLookup.Forbidden();
                    case REVISION_CHANGED, INVALID_RESPONSE -> new RevisionLookup.InvalidResponse();
                };
            } catch (RuntimeException exception) {
                return new RevisionLookup.InvalidResponse();
            }
        };
    }

    @Bean
    MessageIntakePort messageIntakePort(ConversationStore conversationStore, ConversationTelemetry conversationTelemetry) {
        return new ConversationMessageService(conversationStore, conversationTelemetry);
    }

    @Bean
    ConversationQueryPort conversationQueryPort(ConversationStore conversationStore) {
        return new ConversationQueryService(conversationStore);
    }

    @Bean
    MessageJobRetryPolicy messageJobRetryPolicy(RuntimeProperties properties) {
        return new MessageJobRetryPolicy(properties.worker().transientRetries(), properties.worker().maximumBackoff());
    }

    @Bean
    MessageJobService messageJobService(
            ConversationStore conversationStore,
            ConversationModel conversationModel,
            DirectToolRegistry directToolRegistry,
            RepositoryRevisionReader repositoryRevisionReader,
            Clock runtimeClock,
            MessageJobRetryPolicy messageJobRetryPolicy,
            ConversationTelemetry conversationTelemetry) {
        return new MessageJobService(conversationStore, conversationModel, directToolRegistry, repositoryRevisionReader,
                runtimeClock, messageJobRetryPolicy, conversationTelemetry);
    }

    @Bean
    WorkerProperties workerProperties(RuntimeProperties properties) {
        Duration renewalInterval = properties.worker().lockDuration().dividedBy(3);
        return new WorkerProperties(properties.worker().lockDuration(), renewalInterval);
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
