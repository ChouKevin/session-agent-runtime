package com.java.system.sessionagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ContextCompactionRequest;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SpringAiConversationModelTest {

    private static final ModelRouteId GOOGLE_ROUTE_ID = new ModelRouteId("google-genai");

    @Test
    void preserves_nonblank_provider_ids_and_generic_arguments_for_mixed_text_calls() {
        AssistantMessage message = AssistantMessage.builder().content("I will inspect both.").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "first", "{\"query\":\"fees\"}"),
                new AssistantMessage.ToolCall("call-2", "function", "second", "{\"limit\":2}"))).build();
        SpringAiConversationModel model = model(message, GOOGLE_ROUTE_ID);

        ModelCallResult result = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1, usage -> { });
        ModelReply reply = result.reply();

        assertThat(reply).isEqualTo(new ModelReply.UseTools(Optional.of("I will inspect both."), List.of(
                new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of("query", "fees")),
                new ToolRequest(new ToolCallId("call-2"), new ToolName("second"), Map.of("limit", 2)))));
    }

    @Test
    void preserves_json_looking_final_text_without_decoding_it() {
        SpringAiConversationModel model = model(new AssistantMessage("{\"message\":\"still plain text\"}"), GOOGLE_ROUTE_ID);

        ModelCallResult result = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1, usage -> { });
        ModelReply reply = result.reply();

        assertThat(reply).isEqualTo(new ModelReply.Text("{\"message\":\"still plain text\"}"));
    }

    @Test
    void treats_negative_provider_usage_as_unavailable() {
        Usage negativeUsage = new Usage() {
            @Override public Integer getPromptTokens() { return -1; }
            @Override public Integer getCompletionTokens() { return 2; }
            @Override public Integer getTotalTokens() { return 1; }
            @Override public Object getNativeUsage() { return Map.of(); }
        };
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))),
                        ChatResponseMetadata.builder().usage(negativeUsage).build());
            }
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        };
        ObjectMapper mapper = new ObjectMapper();
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new PromptResource(), new NoOpConversationTelemetry(), mapper,
                new GoogleGenAiThoughtSignatureHandler(GOOGLE_ROUTE_ID, mapper));

        ModelCallResult result = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1, usage -> { });

        assertThat(result.reply()).isEqualTo(new ModelReply.Text("done"));
        assertThat(result.usage()).isEqualTo(new ModelUsage(0, 0, 0, false));
    }

    @Test
    void summarize_uses_explicit_no_tool_options_instead_of_model_defaults() {
        ToolCallback defaultToolCallback = mock(ToolCallback.class);
        ToolCallingChatOptions defaultOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(defaultToolCallback))
                .build();
        List<Prompt> prompts = new ArrayList<>();
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("summary"))),
                        ChatResponseMetadata.builder().build());
            }

            @Override public ToolCallingChatOptions getOptions() {
                return defaultOptions;
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new PromptResource(), new NoOpConversationTelemetry(), mapper,
                new GoogleGenAiThoughtSignatureHandler(GOOGLE_ROUTE_ID, mapper));
        UserMessage history = new UserMessage(new SessionId("session-1"), new SessionSequence(1), Optional.of(new MessageJobId("job-1")),
                Instant.EPOCH, MessageRole.USER, "user", "history");

        String summary = model.summarize(new ContextCompactionRequest(Optional.empty(), List.of(history)), () -> 1);

        assertThat(summary).isEqualTo("summary");
        assertThat(prompts).hasSize(1);
        ChatOptions promptOptions = prompts.getFirst().getOptions();
        assertThat(promptOptions).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions noToolOptions = (ToolCallingChatOptions) promptOptions;
        assertThat(noToolOptions).isNotSameAs(defaultOptions);
        assertThat(noToolOptions.getToolCallbacks()).isEmpty();
    }

    @Test
    void generates_unique_ids_for_missing_provider_ids_and_rejects_duplicate_provider_ids() {
        AssistantMessage withoutIds = AssistantMessage.builder().content("Checking.").toolCalls(List.of(
                new AssistantMessage.ToolCall("", "function", "first", "{}"),
                new AssistantMessage.ToolCall(" ", "function", "second", "{}"))).build();

        ModelReply.UseTools generated = (ModelReply.UseTools) model(withoutIds, GOOGLE_ROUTE_ID)
                .respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1, usage -> { }).reply();
        assertThat(generated.requests()).extracting(request -> request.toolCallId()).doesNotHaveDuplicates();

        AssistantMessage duplicateIds = AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "first", "{}"),
                new AssistantMessage.ToolCall("call-1", "function", "second", "{}"))).build();
        assertThatThrownBy(() -> model(duplicateIds, GOOGLE_ROUTE_ID).respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())),
                () -> 1, usage -> { })).isInstanceOf(ModelCallFailure.class);
    }

    @Test
    void rejects_cross_job_native_tool_history_before_calling_the_provider() {
        AtomicBoolean providerCalled = new AtomicBoolean();
        SpringAiConversationModel model = model(new AssistantMessage("unreachable"), providerCalled, GOOGLE_ROUTE_ID);
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

    @Test
    void restores_thought_signatures_only_for_the_next_native_tool_call() {
        byte[] firstSignature = new byte[] {1, 2, 3};
        byte[] secondSignature = new byte[] {4, 5};
        AssistantMessage firstResponse = AssistantMessage.builder().content("Inspecting.")
                .properties(Map.of("thoughtSignatures", List.of(firstSignature, secondSignature), "finishReason", "STOP"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "first", "{}"))).build();
        AssistantMessage finalResponse = new AssistantMessage("done");
        List<Prompt> prompts = new ArrayList<>();
        AtomicBoolean firstCall = new AtomicBoolean(true);
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                AssistantMessage response = firstCall.getAndSet(false) ? firstResponse : finalResponse;
                return new ChatResponse(List.of(new Generation(response)), ChatResponseMetadata.builder().build());
            }
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        };
        ObjectMapper mapper = new ObjectMapper();
        ModelRouteId routeId = new ModelRouteId("gemini-primary");
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new PromptResource(), new NoOpConversationTelemetry(),
                mapper, new GoogleGenAiThoughtSignatureHandler(routeId, mapper));

        ModelCallResult firstResult = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1, usage -> { });
        ModelContinuation continuation = firstResult.continuation().orElseThrow();
        List<SessionMessage> history = List.of(
                new AssistantToolCallsMessage(new SessionId("session-1"), new SessionSequence(2), Optional.of(new MessageJobId("job-1")),
                        Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Inspecting."),
                        List.of(new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of()))),
                new ToolObservation(new SessionId("session-1"), new SessionSequence(3), Optional.of(new MessageJobId("job-1")),
                        Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first", Map.of("isError", false, "result", Map.of())));

        ModelCallResult secondResult = model.respond(new ModelRequest(history, Map.of(new SessionSequence(2), continuation),
                new ToolSnapshot(List.of())), () -> 2, usage -> { });

        assertThat(firstResult.reply()).isEqualTo(new ModelReply.UseTools(Optional.of("Inspecting."), List.of(
                new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of()))));
        assertThat(secondResult.reply()).isEqualTo(new ModelReply.Text("done"));
        assertThat(prompts).hasSize(2);
        org.springframework.ai.chat.messages.AssistantMessage restored = (org.springframework.ai.chat.messages.AssistantMessage)
                prompts.get(1).getInstructions().get(1);
        assertThat(restored.getMetadata()).containsKey("thoughtSignatures").doesNotContainKeys("finishReason", "candidateIndex");
        assertThat(thoughtSignatures(restored.getMetadata().get("thoughtSignatures")))
                .containsExactly(firstSignature, secondSignature);
    }

    @Test
    void rejects_a_malformed_continuation_before_reserving_or_calling_the_provider() {
        AtomicBoolean providerCalled = new AtomicBoolean();
        AtomicBoolean reserved = new AtomicBoolean();
        ModelRouteId routeId = new ModelRouteId("gemini-primary");
        SpringAiConversationModel model = model(new AssistantMessage("unreachable"), providerCalled, routeId);
        List<SessionMessage> history = List.of(
                new AssistantToolCallsMessage(new SessionId("session-1"), new SessionSequence(2), Optional.of(new MessageJobId("job-1")),
                        Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.empty(),
                        List.of(new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of()))),
                new ToolObservation(new SessionId("session-1"), new SessionSequence(3), Optional.of(new MessageJobId("job-1")),
                        Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first",
                        Map.of("isError", false, "result", Map.of())));
        ModelContinuation malformed = new ModelContinuation(routeId,
                "spring-ai-google-genai-thought-signatures-v1", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> model.respond(new ModelRequest(history, Map.of(new SessionSequence(2), malformed), new ToolSnapshot(List.of())),
                () -> { reserved.set(true); return 1; }, usage -> { }))
                .isInstanceOfSatisfying(ModelCallFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TERMINAL));

        assertThat(reserved).isFalse();
        assertThat(providerCalled).isFalse();
    }

    private static SpringAiConversationModel model(AssistantMessage message, ModelRouteId routeId) {
        return model(message, new AtomicBoolean(), routeId);
    }

    private static List<byte[]> thoughtSignatures(Object value) {
        assertThat(value).isInstanceOf(List.class);
        List<?> elements = (List<?>) value;
        assertThat(elements).allSatisfy(element -> assertThat(element).isInstanceOf(byte[].class));
        return elements.stream().map(element -> (byte[]) element).toList();
    }

    private static SpringAiConversationModel model(
            AssistantMessage message,
            AtomicBoolean providerCalled,
            ModelRouteId routeId) {
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                providerCalled.set(true);
                return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder().build());
            }
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        };
        ObjectMapper mapper = new ObjectMapper();
        return new SpringAiConversationModel(chatModel, new PromptResource(), new NoOpConversationTelemetry(), mapper,
                new GoogleGenAiThoughtSignatureHandler(routeId, mapper));
    }
}
