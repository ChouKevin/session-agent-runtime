package com.java.system.sessionagent.semantic.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.java.system.sessionagent.semantic.RepositoryCatalog;
import com.java.system.sessionagent.semantic.SemanticFailure;
import com.java.system.sessionagent.semantic.domain.RepositoryId;
import com.java.system.sessionagent.semantic.domain.RepositorySummary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

public final class SemanticRepositoryClient implements RepositoryCatalog {

    private static final BigInteger MAX_RETRY_AFTER_SECONDS = BigInteger.valueOf(60);
    private static final ParameterizedTypeReference<List<CurrentGenerationResponse>> CURRENT_GENERATION_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final JsonMapper RESPONSE_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final RestClient restClient;

    public SemanticRepositoryClient(RestClient restClient) {
        this.restClient = strictResponseClient(Objects.requireNonNull(restClient, "Semantic service RestClient must not be null"));
    }

    private static RestClient strictResponseClient(RestClient restClient) {
        return restClient.mutate().messageConverters(converters -> {
            converters.removeIf(SemanticRepositoryClient::isJacksonConverter);
            converters.add(new JacksonJsonHttpMessageConverter(RESPONSE_MAPPER));
        }).build();
    }

    private static boolean isJacksonConverter(HttpMessageConverter<?> converter) {
        return converter instanceof JacksonJsonHttpMessageConverter;
    }

    @Override
    public List<RepositorySummary> listRepositories() {
        try {
            List<CurrentGenerationResponse> response = restClient.get().uri("/v1/repositories")
                    .retrieve().body(CURRENT_GENERATION_LIST);
            return requiredResponse(response).stream()
                    .map(this::toSummary)
                    .toList();
        } catch (RestClientResponseException exception) {
            throw classifyCatalogResponse(exception);
        } catch (ResourceAccessException exception) {
            throw SemanticFailure.semanticIndexUnavailable(Optional.empty(), exception);
        } catch (RestClientException exception) {
            throw SemanticHttpFailures.classify(exception);
        } catch (SemanticFailure exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw SemanticFailure.invalidResponse();
        } catch (RuntimeException exception) {
            throw SemanticFailure.invalidResponse();
        }
    }

    private List<CurrentGenerationResponse> requiredResponse(List<CurrentGenerationResponse> response) {
        if (Objects.isNull(response)) {
            throw SemanticFailure.invalidResponse();
        }
        return response;
    }

    private RepositorySummary toSummary(CurrentGenerationResponse response) {
        if (Objects.isNull(response)) {
            throw SemanticFailure.invalidResponse();
        }
        try {
            return new RepositorySummary(new RepositoryId(response.repositoryId().value()),
                    new com.java.system.sessionagent.semantic.domain.RepositoryRevision(response.revision().value()));
        } catch (IllegalArgumentException exception) {
            throw SemanticFailure.invalidResponse();
        }
    }

    private static SemanticFailure classifyCatalogResponse(RestClientResponseException exception) {
        return classifySharedResponse(exception);
    }

    private static SemanticFailure classifySharedResponse(RestClientResponseException exception) {
        HttpStatusCode statusCode = exception.getStatusCode();
        if (statusCode.value() == 401 || statusCode.value() == 403) {
            return SemanticFailure.forbidden();
        }
        if (statusCode.value() == 429 || statusCode.value() == 503) {
            return SemanticFailure.semanticIndexUnavailable(retryAfter(exception.getResponseHeaders()), exception);
        }
        return SemanticFailure.invalidResponse();
    }

    private static Optional<Duration> retryAfter(HttpHeaders headers) {
        return Optional.ofNullable(headers)
                .map(httpHeaders -> httpHeaders.getFirst(HttpHeaders.RETRY_AFTER))
                .filter(SemanticRepositoryClient::isValidRetryAfter)
                .flatMap(SemanticRepositoryClient::toDuration);
    }

    private static boolean isValidRetryAfter(String value) {
        return StringUtils.hasText(value) && value.chars().allMatch(character -> character >= '0' && character <= '9');
    }

    private static Optional<Duration> toDuration(String seconds) {
        try {
            BigInteger parsedSeconds = new BigInteger(seconds);
            long boundedSeconds = parsedSeconds.min(MAX_RETRY_AFTER_SECONDS).longValueExact();
            return Optional.of(Duration.ofSeconds(boundedSeconds));
        } catch (NumberFormatException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private record CurrentGenerationResponse(
            ValueResponse repositoryId,
            ValueResponse revision,
            ValueResponse generationId,
            ValueResponse manifestDigest,
            java.time.Instant publishedAt) {

        @JsonAnySetter
        private void rejectUnknownProperty(String property, Object value) {
            throw new IllegalArgumentException();
        }
    }

    private record ValueResponse(String value) {

        @JsonAnySetter
        private void rejectUnknownProperty(String property, Object value) {
            throw new IllegalArgumentException();
        }
    }
}
