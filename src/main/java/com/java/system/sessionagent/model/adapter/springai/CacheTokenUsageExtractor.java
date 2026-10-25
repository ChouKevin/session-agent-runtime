package com.java.system.sessionagent.model.adapter.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.java.system.sessionagent.conversation.domain.CacheTokenUsage;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.completions.CompletionUsage;
import java.util.Objects;
import java.util.Optional;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** Raw provider presence, not normalized primitive defaults, determines accounting coverage. */
final class CacheTokenUsageExtractor {
    private CacheTokenUsageExtractor() {
    }

    static Optional<CacheTokenUsage> extract(Usage usage) {
        if (Objects.isNull(usage)) {
            return Optional.empty();
        }
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage instanceof GenerateContentResponseUsageMetadata google) {
            return normalized(google.promptTokenCount().map(Integer::longValue),
                    google.cachedContentTokenCount().map(Integer::longValue));
        }
        if (nativeUsage instanceof CompletionUsage openAi) {
            return extractOpenAi(openAi);
        }
        return Optional.empty();
    }

    static Optional<CacheTokenUsage> extractResponse(ChatResponse response) {
        if (Objects.isNull(response) || Objects.isNull(response.getMetadata())) {
            return Optional.empty();
        }
        Object preserved = response.getMetadata().get(OpenAiUsagePreservingClient.RAW_USAGE_METADATA);
        Optional<?> rawValue = preserved instanceof JsonValue value ? value.asString() : Optional.ofNullable(preserved);
        Optional<String> rawJson = rawValue.orElse(null) instanceof String text ? Optional.of(text) : Optional.empty();
        if (rawJson.isPresent()) {
            try {
                return extractOpenAi(ObjectMappers.jsonMapper().readValue(rawJson.get(), CompletionUsage.class));
            } catch (JsonProcessingException ignored) {
                return Optional.empty();
            }
        }
        return extract(response.getMetadata().getUsage());
    }

    private static Optional<CacheTokenUsage> extractOpenAi(CompletionUsage usage) {
        return normalized(usage._promptTokens().asKnown(), usage._promptTokensDetails().asKnown()
                .flatMap(details -> details._cachedTokens().asKnown()));
    }

    private static Optional<CacheTokenUsage> normalized(Optional<Long> input, Optional<Long> cached) {
        if (input.isEmpty() || cached.isEmpty() || input.get() < 0 || cached.get() < 0 || cached.get() > input.get()) {
            return Optional.empty();
        }
        return Optional.of(new CacheTokenUsage(input.get(), cached.get()));
    }
}
