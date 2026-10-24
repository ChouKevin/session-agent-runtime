package com.java.system.sessionagent.model.adapter.springai;

import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiMessagePartsHandlerTest {

    private final SpringAiMessagePartsHandler handler = new SpringAiMessagePartsHandler(new ModelRouteId("gemini-primary"));

    @Test
    void preserves_ordered_reasoning_signatures_unknown_parts_media_and_attributes_without_response_metadata() {
        OpaquePayload signature = new OpaquePayload("google", "thought_signature", "AQID");
        AssistantMessage message = AssistantMessage.builder()
                .part(new ReasoningPart("Need evidence.", null, signature, Map.of("provider", "google")))
                .part(new TextPart("Inspecting.", signature, Map.of()))
                .part(new ToolCallPart(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}"), signature, Map.of()))
                .part(new UnknownPart("google", "executableCode", "{\"executableCode\":{\"code\":\"1+1\",\"language\":\"PYTHON\"}}", null, Map.of()))
                .part(MediaPart.of(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] {1, 2, 3}).build()))
                .properties(Map.of("finishReason", "STOP", "thoughtSignatures", List.of(new byte[] {9})))
                .build();

        ModelContinuation continuation = handler.capture(message).orElseThrow();
        List<MessagePart> restored = handler.restore(continuation);

        assertThat(restored).containsExactlyElementsOf(message.getParts());
        assertThat(new String(continuation.payload(), StandardCharsets.UTF_8)).doesNotContain("finishReason", "thoughtSignatures");
        assertThatThrownBy(() -> restored.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preserves_openai_reasoning_and_extra_tool_properties_in_their_parts() {
        AssistantMessage message = AssistantMessage.builder()
                .part(ReasoningPart.of("Look it up."))
                .part(TextPart.of("Checking."))
                .part(new ToolCallPart(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}"),
                        new OpaquePayload("openai.chat.completions", "tool_call_additional_properties",
                                "{\"extra_content\":{\"google\":{\"thought_signature\":\"AQID\"}}}"), Map.of()))
                .build();

        assertThat(handler.restore(handler.capture(message).orElseThrow())).containsExactlyElementsOf(message.getParts());
    }

    @Test
    void rejects_a_different_route_or_format_without_exposing_provider_state() {
        ModelContinuation valid = handler.capture(new AssistantMessage("text")).orElseThrow();
        for (ModelContinuation invalid : List.of(
                new ModelContinuation(new ModelRouteId("other"), valid.format(), valid.payload()),
                new ModelContinuation(valid.modelRouteId(), "spring-ai-google-genai-thought-signatures-v1", valid.payload()))) {
            assertTerminal(invalid);
        }
    }

    @Test
    void rejects_malformed_or_empty_stored_parts_without_exposing_provider_state() {
        for (String payload : List.of("not-json", "[]", "null", "[null]", "{}")) {
            assertTerminal(new ModelContinuation(new ModelRouteId("gemini-primary"), "spring-ai-message-parts-v1",
                    payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void assertTerminal(ModelContinuation continuation) {
        assertThatThrownBy(() -> handler.restore(continuation)).isInstanceOfSatisfying(ModelCallFailure.class, failure -> {
            assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TERMINAL);
            assertThat(failure).hasMessageNotContaining("thought_signature").hasMessageNotContaining("not-json");
        });
    }
}
