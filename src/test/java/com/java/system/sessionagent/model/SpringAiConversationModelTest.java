package com.java.system.sessionagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiConversationModelTest {

    @Test
    void preserves_nonblank_provider_ids_and_generic_arguments_for_mixed_text_calls() {
        AssistantMessage message = AssistantMessage.builder().content("I will inspect both.").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "first", "{\"query\":\"fees\"}"),
                new AssistantMessage.ToolCall("call-2", "function", "second", "{\"limit\":2}"))).build();
        SpringAiConversationModel model = model(message);

        ModelReply reply = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1, usage -> { });

        assertThat(reply).isEqualTo(new ModelReply.UseTools(Optional.of("I will inspect both."), List.of(
                new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of("query", "fees")),
                new ToolRequest(new ToolCallId("call-2"), new ToolName("second"), Map.of("limit", 2)))));
    }

    @Test
    void preserves_json_looking_final_text_without_decoding_it() {
        SpringAiConversationModel model = model(new AssistantMessage("{\"message\":\"still plain text\"}"));

        ModelReply reply = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1, usage -> { });

        assertThat(reply).isEqualTo(new ModelReply.Text("{\"message\":\"still plain text\"}"));
    }

    @Test
    void generates_unique_ids_for_missing_provider_ids_and_rejects_duplicate_provider_ids() {
        AssistantMessage withoutIds = AssistantMessage.builder().content("Checking.").toolCalls(List.of(
                new AssistantMessage.ToolCall("", "function", "first", "{}"),
                new AssistantMessage.ToolCall(" ", "function", "second", "{}"))).build();

        ModelReply.UseTools generated = (ModelReply.UseTools) model(withoutIds)
                .respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1, usage -> { });
        assertThat(generated.requests()).extracting(ToolRequest::toolCallId).doesNotHaveDuplicates();

        AssistantMessage duplicateIds = AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "first", "{}"),
                new AssistantMessage.ToolCall("call-1", "function", "second", "{}"))).build();
        assertThatThrownBy(() -> model(duplicateIds).respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())),
                () -> 1, usage -> { })).isInstanceOf(ModelCallFailure.class);
    }

    @Test
    void rejects_cross_job_native_tool_history_before_calling_the_provider() {
        AtomicBoolean providerCalled = new AtomicBoolean();
        SpringAiConversationModel model = model(new AssistantMessage("unreachable"), providerCalled);
        List<SessionMessage> malformedHistory = List.of(
                new AssistantToolCallsMessage(new SessionId("session-1"), new SessionSequence(1), Optional.of(new MessageJobId("job-1")),
                        Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.empty(),
                        List.of(new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of()))),
                new ToolObservation(new SessionId("session-1"), new SessionSequence(2), Optional.of(new MessageJobId("job-2")),
                        Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first", Map.of("isError", false, "result", Map.of())));

        assertThatThrownBy(() -> model.respond(new ModelRequest(malformedHistory, new ToolSnapshot(List.of())), () -> 1, usage -> { }))
                .isInstanceOf(ModelCallFailure.class);
        assertThat(providerCalled).isFalse();
    }

    private static SpringAiConversationModel model(AssistantMessage message) {
        return model(message, new AtomicBoolean());
    }

    private static SpringAiConversationModel model(AssistantMessage message, AtomicBoolean providerCalled) {
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                providerCalled.set(true);
                return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder().build());
            }
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        };
        ObjectMapper mapper = new ObjectMapper();
        return new SpringAiConversationModel(chatModel, new PromptResource(), new NoOpConversationTelemetry(), mapper);
    }
}
