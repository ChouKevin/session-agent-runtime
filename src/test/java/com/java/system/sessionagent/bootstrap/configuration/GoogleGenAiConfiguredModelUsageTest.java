package com.java.system.sessionagent.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.java.system.sessionagent.bootstrap.adapter.metrics.MicrometerConversationTelemetry;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.model.adapter.springai.GoogleGenAiThoughtSignatureHandler;
import com.java.system.sessionagent.model.adapter.springai.SpringAiConversationModel;
import com.java.system.sessionagent.model.adapter.springai.SystemPrompt;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleGenAiConfiguredModelUsageTest {

    private static final ModelRouteId GOOGLE_ROUTE_ID = new ModelRouteId("google-primary");

    @ParameterizedTest(name = "Google GenAI cached input {0}")
    @MethodSource("googleCacheUsageCases")
    void maps_google_provider_cache_usage_to_common_telemetry(
            String scenario,
            Optional<Integer> providerValue,
            String expectedState,
            OptionalLong expectedCachedInputTokens) {
        GenerateContentResponseUsageMetadata.Builder metadata = GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(8)
                .candidatesTokenCount(2)
                .totalTokenCount(10);
        providerValue.ifPresent(metadata::cachedContentTokenCount);
        GoogleGenAiUsage googleUsage = GoogleGenAiUsage.from(metadata.build());
        Optional<Long> expectedProviderValue = providerValue.map(value -> value.longValue());
        assertThat(Optional.ofNullable(googleUsage.getCacheReadInputTokens()))
                .as("pinned Google usage mapper")
                .isEqualTo(expectedProviderValue);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectMapper mapper = new ObjectMapper();
        ChatModel chatModel = scriptedChatModel(googleUsage);
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new SystemPrompt(),
                new MicrometerConversationTelemetry(registry), mapper,
                new GoogleGenAiThoughtSignatureHandler(GOOGLE_ROUTE_ID, mapper));

        ModelCallResult result = model.respond(
                new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1);

        assertThat(result.reply()).as(scenario).isEqualTo(new ModelReply.Text("Google response."));
        assertThat(result.usage()).as(scenario)
                .isEqualTo(new ModelUsage(8, 2, 10, true, expectedCachedInputTokens));
        assertThat(registry.get("session_agent.model.cache_usage").tag("state", expectedState)
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("session_agent.model.tokens").tag("kind", "prompt").counter().count())
                .isEqualTo(8);
        assertThat(registry.get("session_agent.model.tokens").tag("kind", "completion").counter().count())
                .isEqualTo(2);
        assertThat(registry.get("session_agent.model.tokens").tag("kind", "total").counter().count())
                .isEqualTo(10);
        if (expectedCachedInputTokens.isPresent()) {
            assertThat(registry.get("session_agent.model.tokens").tag("kind", "cached_input").counter().count())
                    .isEqualTo(expectedCachedInputTokens.getAsLong());
        } else {
            assertThat(registry.find("session_agent.model.tokens").tags("kind", "cached_input").counter())
                    .isNull();
        }
    }

    private static Stream<Arguments> googleCacheUsageCases() {
        return Stream.of(
                Arguments.of("hit", Optional.of(4), "HIT", OptionalLong.of(4)),
                Arguments.of("zero", Optional.of(0), "ZERO", OptionalLong.of(0)),
                Arguments.of("absent", Optional.empty(), "UNKNOWN", OptionalLong.empty()));
    }

    private static ChatModel scriptedChatModel(Usage usage) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Google response."))),
                        ChatResponseMetadata.builder().usage(usage).build());
            }

            @Override
            public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
    }
}
