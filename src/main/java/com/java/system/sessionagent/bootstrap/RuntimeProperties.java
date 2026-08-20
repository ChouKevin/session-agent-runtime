package com.java.system.sessionagent.bootstrap;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("session-agent")
public record RuntimeProperties(
        @Valid @DefaultValue Semantic semantic,
        @Valid @DefaultValue Model model,
        @Valid @DefaultValue Worker worker) {

    public RuntimeProperties {
        Assert.notNull(semantic, "Semantic properties must not be null");
        Assert.notNull(model, "Model properties must not be null");
        Assert.notNull(worker, "Worker properties must not be null");
    }

    public record Semantic(
            @NotBlank @DefaultValue("http://localhost:8080") String baseUrl,
            @NotBlank String apiToken,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("120s") Duration responseTimeout) {
        public Semantic {
            Assert.hasText(baseUrl, "Semantic base URL must not be blank");
            Assert.hasText(apiToken, "Semantic API token must not be blank");
            Assert.notNull(connectTimeout, "Semantic connect timeout must not be null");
            Assert.notNull(responseTimeout, "Semantic response timeout must not be null");
            Assert.isTrue(!connectTimeout.isNegative() && !connectTimeout.isZero(), "Semantic connect timeout must be positive");
            Assert.isTrue(!responseTimeout.isNegative() && !responseTimeout.isZero(), "Semantic response timeout must be positive");
        }
    }

    public record Model(@NotBlank @DefaultValue("gemini-3.1-flash-lite") String name) {
        public Model {
            Assert.hasText(name, "Google model name must not be blank");
        }
    }

    public record Worker(
            @DefaultValue("1s") Duration pollDelay,
            @DefaultValue("30s") Duration lockDuration,
            @PositiveOrZero @DefaultValue("3") int transientRetries,
            @DefaultValue("60s") Duration maximumBackoff) {
        public Worker {
            Assert.notNull(pollDelay, "Worker poll delay must not be null");
            Assert.notNull(lockDuration, "Worker lock duration must not be null");
            Assert.notNull(maximumBackoff, "Worker maximum backoff must not be null");
            Assert.isTrue(!pollDelay.isNegative() && !pollDelay.isZero(), "Worker poll delay must be positive");
            Assert.isTrue(!lockDuration.isNegative() && !lockDuration.isZero(), "Worker lock duration must be positive");
            Assert.isTrue(!maximumBackoff.isNegative() && !maximumBackoff.isZero(), "Worker maximum backoff must be positive");
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
