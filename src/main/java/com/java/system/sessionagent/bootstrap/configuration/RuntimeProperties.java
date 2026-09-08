package com.java.system.sessionagent.bootstrap.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@ConfigurationProperties("session-agent")
public record RuntimeProperties(
        @Valid @DefaultValue Model model,
        @Valid @DefaultValue Worker worker) {

    public RuntimeProperties {
        Assert.notNull(model, "Model properties must not be null");
        Assert.notNull(worker, "Worker properties must not be null");
    }

    public record Model(
            @Positive @DefaultValue("12") int maxModelCallsPerMessage,
            @NotBlank String defaultModel,
            Map<String, ModelProfile> models) {

        @ConstructorBinding
        public Model {
            Assert.hasText(defaultModel, "Default model key must not be blank");
            Assert.notEmpty(models, "Configured models must not be empty");
            models = Map.copyOf(new LinkedHashMap<>(models));
            Assert.isTrue(models.containsKey(defaultModel), "Default model key must name a configured model");
        }

        public Model(int maxModelCallsPerMessage, String defaultModel) {
            this(maxModelCallsPerMessage, defaultModel, Map.of(defaultModel,
                    new ModelProfile("google-genai", "gemini-3.1-flash-lite", "test-key", 1_048_576, 8_192,
                            null, null, null, null, null)));
        }
    }

    public record ModelProfile(
            @NotBlank String provider,
            @NotBlank String modelId,
            @NotBlank String apiKey,
            @Positive long contextWindowTokens,
            @Positive int maxOutputTokens,
            Double temperature,
            Double topP,
            Duration timeout,
            String reasoningEffort,
            String baseUrl) {
        public ModelProfile {
            Assert.hasText(provider, "Model provider must not be blank");
            Assert.hasText(modelId, "Model ID must not be blank");
            Assert.hasText(apiKey, "Model API key must not be blank");
            Assert.isTrue(contextWindowTokens > 0, "Model context window tokens must be positive");
            Assert.isTrue(maxOutputTokens > 0, "Model max output tokens must be positive");
            if (temperature != null) { // cs-allow optional configuration value
                Assert.isTrue(temperature >= 0, "Model temperature must not be negative");
            }
            if (topP != null) { // cs-allow optional configuration value
                Assert.isTrue(topP > 0 && topP <= 1, "Model top P must be within (0, 1]");
            }
            if (timeout != null) { // cs-allow optional configuration value
                Assert.isTrue(!timeout.isNegative() && !timeout.isZero(), "Model timeout must be positive");
            }
        }
    }

    public record Worker(
            @DefaultValue("1s") Duration pollDelay,
            @DefaultValue("30s") Duration lockDuration,
            @PositiveOrZero @DefaultValue("3") int transientRetries,
            @DefaultValue("60s") Duration maximumBackoff,
            @Positive @DefaultValue("4") int concurrency) {
        public Worker(Duration pollDelay, Duration lockDuration, int transientRetries, Duration maximumBackoff) {
            this(pollDelay, lockDuration, transientRetries, maximumBackoff, 4); // cs-allow compatibility constructor
        }

        @ConstructorBinding
        public Worker {
            Assert.notNull(pollDelay, "Worker poll delay must not be null");
            Assert.notNull(lockDuration, "Worker lock duration must not be null");
            Assert.notNull(maximumBackoff, "Worker maximum backoff must not be null");
            Assert.isTrue(!pollDelay.isNegative() && !pollDelay.isZero(), "Worker poll delay must be positive");
            Assert.isTrue(!lockDuration.isNegative() && !lockDuration.isZero(), "Worker lock duration must be positive");
            Assert.isTrue(!maximumBackoff.isNegative() && !maximumBackoff.isZero(), "Worker maximum backoff must be positive");
            Assert.isTrue(concurrency > 0, "Worker concurrency must be positive");
        }
    }

    @Validated
    @ConfigurationProperties("spring.datasource")
    public record Datasource(@NotBlank String url, @NotBlank String password) {
        public Datasource {
            Assert.hasText(url, "Datasource URL must not be blank");
            Assert.hasText(password, "Datasource password must not be blank");
        }
    }
}
