package com.java.system.sessionagent.bootstrap.configuration;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModels;
import com.java.system.sessionagent.model.adapter.springai.SystemPrompt;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import com.java.system.sessionagent.worker.configuration.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConfigurationTest {

    @Test
    void binds_loopback_and_generic_mcp_defaults() throws Exception {
        String configuration = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(configuration).contains("server:\n  address: 127.0.0.1", "connections: {}",
                "max-model-calls-per-message: ${SESSION_AGENT_MAX_MODEL_CALLS_PER_MESSAGE:12}",
                "optional:classpath:models.yml");
        assertThat(configuration).doesNotContain("semantic:", "SEMANTIC_");
    }

    @Test
    void maps_worker_and_retry_policies() {
        RuntimeProperties.Worker worker = new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60));
        RuntimeConfiguration configuration = new RuntimeConfiguration();
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(12, "google-genai"), worker);

        WorkerProperties workerProperties = configuration.workerProperties(properties);
        MessageJobRetryPolicy retryPolicy = configuration.messageJobRetryPolicy(properties);

        assertThat(workerProperties.renewalInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(retryPolicy.maximumBackoff()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejects_unsafe_worker_and_datasource_configuration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Worker(
                Duration.ZERO, Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Datasource(
                "jdbc:postgresql://localhost:5432/session_agent", " "));
    }

    @Test
    void maps_configured_model_keys_to_stable_nonsecret_fingerprints() throws Exception {
        RuntimeProperties.ModelProfile firstProfile = openAiProfile("first-key", 0.2);
        RuntimeProperties.ModelProfile rotatedCredential = openAiProfile("rotated-key", 0.2);
        RuntimeProperties.ModelProfile changedSampling = openAiProfile("rotated-key", 0.3);

        try (ConfiguredModels first = configuredModels(firstProfile);
                ConfiguredModels rotated = configuredModels(rotatedCredential);
                ConfiguredModels changed = configuredModels(changedSampling)) {
            assertThat(first.defaultBinding().model()).isEqualTo("openai-primary");
            assertThat(first.defaultBinding().conversationModel().routeId().value()).isEqualTo("openai-primary");
            assertThat(first.defaultBinding().fingerprint()).isEqualTo(rotated.defaultBinding().fingerprint());
            assertThat(first.defaultBinding().fingerprint()).isNotEqualTo(changed.defaultBinding().fingerprint());
            assertThat(first.defaultBinding().fingerprint()).doesNotContain("first-key", "rotated-key");
        }
    }

    @Test
    void rejects_openai_only_fields_on_google_profiles_before_model_construction() {
        RuntimeProperties.ModelProfile reasoningEffort = new RuntimeProperties.ModelProfile("google-genai",
                "gemini-3.1-flash-lite", "test-key", 1_048_576, 8_192, null, null, null, "low", null);
        RuntimeProperties.ModelProfile baseUrl = new RuntimeProperties.ModelProfile("google-genai",
                "gemini-3.1-flash-lite", "test-key", 1_048_576, 8_192, null, null, null, null,
                "https://example.invalid");

        assertThatIllegalArgumentException().isThrownBy(() -> configuredModels(reasoningEffort));
        assertThatIllegalArgumentException().isThrownBy(() -> configuredModels(baseUrl));
    }

    @Test
    void gives_the_openai_default_and_explicit_effective_route_the_same_fingerprint() throws Exception {
        RuntimeProperties.ModelProfile defaultRoute = new RuntimeProperties.ModelProfile("openai", "gpt-5-mini", "test-key",
                200_000, 1_234, 0.2, 0.8, Duration.ofSeconds(2), "low", null);
        RuntimeProperties.ModelProfile explicitRoute = new RuntimeProperties.ModelProfile("openai", "gpt-5-mini", "test-key",
                200_000, 1_234, 0.2, 0.8, Duration.ofSeconds(2), "low", "https://api.openai.com/v1");

        try (ConfiguredModels defaultModels = configuredModels(defaultRoute);
                ConfiguredModels explicitModels = configuredModels(explicitRoute)) {
            assertThat(defaultModels.defaultBinding().fingerprint())
                    .isEqualTo(explicitModels.defaultBinding().fingerprint());
        }
    }

    @Test
    void fingerprints_the_provider_operation_separately_from_its_endpoint() {
        RuntimeProperties.ModelProfile profile = openAiProfile("test-key", 0.2);
        RuntimeConfiguration.EffectiveModelProfile chatCompletions = new RuntimeConfiguration.EffectiveModelProfile(profile,
                "https://api.openai.com/v1", "/chat/completions");
        RuntimeConfiguration.EffectiveModelProfile anotherOperation = new RuntimeConfiguration.EffectiveModelProfile(profile,
                "https://api.openai.com/v1", "/responses");

        assertThat(RuntimeConfiguration.fingerprint(chatCompletions))
                .isNotEqualTo(RuntimeConfiguration.fingerprint(anotherOperation));
    }

    @Test
    void closes_partially_created_model_resources_when_a_later_model_construction_fails() {
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicInteger modelConstructions = new AtomicInteger();
        IllegalStateException constructionFailure = new IllegalStateException("later construction failure");
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup failure");
        AutoCloseable firstResource = () -> {
            closeAttempts.incrementAndGet();
            throw cleanupFailure;
        };
        Map<String, RuntimeProperties.ModelProfile> profiles = new LinkedHashMap<>();
        profiles.put("first", openAiProfile("first-key", 0.2));
        profiles.put("second", openAiProfile("second-key", 0.2));
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(12, "first", profiles),
                new RuntimeProperties.Worker(Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));

        assertThatThrownBy(() -> RuntimeConfiguration.assembleConfiguredModels(new SystemPrompt(),
                new NoOpConversationTelemetry(), new ObjectMapper(), properties, profile -> {
                    if (modelConstructions.incrementAndGet() == 1) {
                        return new RuntimeConfiguration.CreatedChatModel(mock(ChatModel.class), Optional.of(firstResource));
                    }
                    throw constructionFailure;
                }))
                .isSameAs(constructionFailure);
        assertThat(closeAttempts).hasValue(1);
        assertThat(constructionFailure.getSuppressed()).containsExactly(cleanupFailure);
    }

    @Test
    void validates_every_google_timeout_before_allocating_any_provider_client() {
        AtomicInteger modelAllocations = new AtomicInteger();
        RuntimeProperties.ModelProfile oversizedGoogleTimeout = new RuntimeProperties.ModelProfile("google-genai",
                "gemini-3.1-flash-lite", "test-key", 1_048_576, 8_192, null, null,
                Duration.ofMillis((long) Integer.MAX_VALUE + 1), null, null);
        Map<String, RuntimeProperties.ModelProfile> profiles = new LinkedHashMap<>();
        profiles.put("openai-first", openAiProfile("openai-key", 0.2));
        profiles.put("google-second", oversizedGoogleTimeout);
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(12, "openai-first", profiles),
                new RuntimeProperties.Worker(Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));

        assertThatIllegalArgumentException().isThrownBy(() -> RuntimeConfiguration.assembleConfiguredModels(new SystemPrompt(),
                new NoOpConversationTelemetry(), new ObjectMapper(), properties, profile -> {
                    modelAllocations.incrementAndGet();
                    return new RuntimeConfiguration.CreatedChatModel(mock(ChatModel.class), Optional.empty());
                }));
        assertThat(modelAllocations).hasValue(0);
    }

    @Test
    void wires_google_typed_http_failures_through_the_configured_model_binding() throws Exception {
        assertConfiguredGoogleFailure(new ClientException(429, "RESOURCE_EXHAUSTED", "rate limited"),
                ModelCallFailure.Kind.TRANSIENT);
        assertConfiguredGoogleFailure(new ServerException(500, "INTERNAL", "provider unavailable"),
                ModelCallFailure.Kind.TRANSIENT);
        assertConfiguredGoogleFailure(new ServerException(503, "UNAVAILABLE", "provider unavailable"),
                ModelCallFailure.Kind.TRANSIENT);
        assertConfiguredGoogleFailure(new ClientException(400, "INVALID_ARGUMENT", "invalid value 500 for parameter"),
                ModelCallFailure.Kind.TERMINAL);
    }

    @Test
    void rejects_invalid_configured_model_selection_and_profile_values() {
        RuntimeProperties.ModelProfile profile = openAiProfile("test-key", 0.2);

        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Model(12, "missing", Map.of("openai", profile)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.ModelProfile("openai", "gpt-5-mini", "test-key",
                200_000, 0, 0.2, 0.8, Duration.ofSeconds(2), "low", "https://api.openai.com/v1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.ModelProfile("openai", "gpt-5-mini", " ",
                200_000, 1_234, 0.2, 0.8, Duration.ofSeconds(2), "low", "https://api.openai.com/v1"));
    }

    private static ConfiguredModels configuredModels(RuntimeProperties.ModelProfile profile) {
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(12, "openai-primary",
                Map.of("openai-primary", profile)), new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
        return new RuntimeConfiguration().configuredModels(new SystemPrompt(), new NoOpConversationTelemetry(), new ObjectMapper(), properties);
    }

    private static RuntimeProperties.ModelProfile openAiProfile(String apiKey, double temperature) {
        return new RuntimeProperties.ModelProfile("openai", "gpt-5-mini", apiKey, 200_000, 1_234,
                temperature, 0.8, Duration.ofSeconds(2), "low", "https://api.openai.com/v1");
    }

    private static void assertConfiguredGoogleFailure(ApiException providerFailure, ModelCallFailure.Kind expectedKind) throws Exception {
        AtomicInteger reservations = new AtomicInteger();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Spring AI wrapper", providerFailure));
        RuntimeProperties.ModelProfile profile = new RuntimeProperties.ModelProfile("google-genai", "gemini-3.1-flash-lite",
                "test-key", 1_048_576, 8_192, null, null, null, null, null);
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(12, "google-primary",
                Map.of("google-primary", profile)), new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
        try (ConfiguredModels models = RuntimeConfiguration.assembleConfiguredModels(new SystemPrompt(),
                new NoOpConversationTelemetry(), new ObjectMapper(), properties,
                configuredProfile -> new RuntimeConfiguration.CreatedChatModel(chatModel, Optional.empty()))) {
            assertThatThrownBy(() -> models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of())), reservations::incrementAndGet))
                    .isInstanceOfSatisfying(ModelCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(expectedKind));
        }
        assertThat(reservations).hasValue(1);
        verify(chatModel).call(any(Prompt.class));
    }
}
