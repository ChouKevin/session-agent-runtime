package com.java.system.sessionagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleGenAiThoughtSignatureHandlerTest {

    private final GoogleGenAiThoughtSignatureHandler handler = new GoogleGenAiThoughtSignatureHandler(
            new ModelRouteId("gemini-primary"), new ObjectMapper());

    @Test
    void captures_and_restores_exact_thought_signature_bytes_only() {
        byte[] first = new byte[] {1, 2, 3};
        byte[] second = new byte[] {4, 5};
        AssistantMessage providerMessage = AssistantMessage.builder()
                .content("Inspecting.")
                .properties(Map.of(
                        "thoughtSignatures", List.of(first, second),
                        "finishReason", "STOP"))
                .toolCalls(List.of(toolCall("call-1", "semantic_search_code")))
                .build();

        ModelContinuation continuation = handler.capture(providerMessage).orElseThrow();
        Map<String, Object> restored = handler.restore(continuation);

        assertThat(restored).containsOnlyKeys("thoughtSignatures");
        assertThat((List<byte[]>) restored.get("thoughtSignatures"))
                .containsExactly(first, second);
    }

    @Test
    void ignores_unrelated_provider_metadata() {
        AssistantMessage providerMessage = AssistantMessage.builder()
                .content("Inspecting.")
                .properties(Map.of("finishReason", "STOP"))
                .toolCalls(List.of(toolCall("call-1", "semantic_search_code")))
                .build();

        assertThat(handler.capture(providerMessage)).isEqualTo(Optional.empty());
    }

    @Test
    void defensively_copies_captured_and_restored_bytes() {
        byte[] signature = new byte[] {1, 2, 3};
        AssistantMessage providerMessage = AssistantMessage.builder()
                .content("Inspecting.")
                .properties(Map.of("thoughtSignatures", List.of(signature)))
                .toolCalls(List.of(toolCall("call-1", "semantic_search_code")))
                .build();

        ModelContinuation continuation = handler.capture(providerMessage).orElseThrow();
        signature[0] = 9;
        List<byte[]> firstRestore = (List<byte[]>) handler.restore(continuation).get("thoughtSignatures");
        firstRestore.getFirst()[1] = 8;
        List<byte[]> secondRestore = (List<byte[]>) handler.restore(continuation).get("thoughtSignatures");

        assertThat(secondRestore).containsExactly(new byte[] {1, 2, 3});
    }

    @Test
    void rejects_a_different_route_or_format_before_building_metadata() {
        ModelContinuation otherRoute = new ModelContinuation(new ModelRouteId("codex-primary"),
                "spring-ai-google-genai-thought-signatures-v1", new byte[] {1});
        ModelContinuation otherFormat = new ModelContinuation(new ModelRouteId("gemini-primary"),
                "other-format", new byte[] {1});

        assertThatThrownBy(() -> handler.restore(otherRoute))
                .isInstanceOfSatisfying(ModelCallFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TERMINAL));
        assertThatThrownBy(() -> handler.restore(otherFormat))
                .isInstanceOfSatisfying(ModelCallFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TERMINAL));
    }

    @Test
    void rejects_malformed_opaque_bytes_without_exposing_them() {
        byte[] malformed = new byte[] {1, 2, 3};
        ModelContinuation continuation = new ModelContinuation(new ModelRouteId("gemini-primary"),
                "spring-ai-google-genai-thought-signatures-v1", malformed);

        assertThatThrownBy(() -> handler.restore(continuation))
                .isInstanceOfSatisfying(ModelCallFailure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TERMINAL);
                    assertThat(failure).hasMessageNotContaining("1, 2, 3");
                });
    }

    private static AssistantMessage.ToolCall toolCall(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }
}
