package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.AssistantReply;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.domain.ToolResult;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GoogleConversationModelTest {

    private static final byte[] THOUGHT_SIGNATURE = new byte[]{1, 2, 3, 4};
    private static final String MODEL_CONTEXT = Base64.getEncoder().encodeToString(THOUGHT_SIGNATURE);

    @Test
    void advertises_only_the_issued_snapshot_and_returns_one_tool_request_without_executing_it() {
        RecordingChatModel chatModel = new RecordingChatModel(response(toolResponse("call-1", "catalog", "{}"), new DefaultUsage(7, 3, 10)));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());
        List<ModelUsage> observedUsage = new ArrayList<>();

        ModelDecision decision = model.decide(request(snapshot("catalog"), false), observedUsage::add);

        assertThat(decision).isEqualTo(new ModelDecision.UseTool("call-1", new ToolName("catalog"), "{}", MODEL_CONTEXT));
        assertThat(chatModel.prompt.getInstructions().getFirst()).isInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class);
        assertThat(chatModel.prompt.getInstructions()).hasSize(2);
        assertThat(chatModel.prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.prompt.getOptions();
        assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name()).containsExactly("catalog");
        assertThat(observedUsage).containsExactly(new ModelUsage(7, 3, 10, true));
    }

    @Test
    void assigns_a_runtime_id_when_google_omits_the_tool_call_id() {
        RecordingChatModel chatModel = new RecordingChatModel(response(toolResponse("", "catalog", "{}")));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());

        ModelDecision decision = model.decide(request(snapshot("catalog"), false), usage -> { });

        assertThat(decision).isInstanceOfSatisfying(ModelDecision.UseTool.class, tool -> {
            assertThat(tool.callId()).startsWith("runtime-").hasSize(44);
            assertThat(tool.toolName()).isEqualTo(new ToolName("catalog"));
            assertThat(tool.arguments()).isEqualTo("{}");
            assertThat(tool.modelContext()).isEqualTo(MODEL_CONTEXT);
        });
    }

    @Test
    void decodes_a_strict_final_reply_and_uses_no_callbacks_when_reply_only() {
        RecordingChatModel chatModel = new RecordingChatModel(response(
                new AssistantMessage("{\"citations\":[{\"value\":\"result-1\"}],\"message\":\"Answer\"}"), null));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());
        List<ModelUsage> observedUsage = new ArrayList<>();

        ModelDecision decision = model.decide(request(snapshot("catalog"), true), observedUsage::add);

        assertThat(decision).isEqualTo(new ModelDecision.Reply(new AssistantReply("Answer", List.of(new ResultId("result-1")))));
        ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.prompt.getOptions();
        assertThat(CollectionUtils.isEmpty(options.getToolCallbacks())).isTrue();
        assertThat(observedUsage).containsExactly(new ModelUsage(0, 0, 0, false));
    }

    @Test
    void appends_exact_citeable_result_ids_to_the_reply_only_request() {
        RecordingChatModel chatModel = new RecordingChatModel(response(
                new AssistantMessage("{\"citations\":[{\"value\":\"source-result\"}],\"message\":\"Answer\"}"), null));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());
        SessionId sessionId = new SessionId("session-1");
        MessageJobId jobId = new MessageJobId("job-1");
        Instant createdAt = Instant.parse("2026-08-15T10:15:30Z");
        List<SessionMessage> history = List.of(
                new UserMessage(sessionId, new SessionSequence(1), Optional.of(jobId), createdAt,
                        MessageRole.USER, "Alice", "Question"),
                new ToolMessage(sessionId, new SessionSequence(2), Optional.of(jobId), createdAt,
                        MessageRole.TOOL, new ResultId("catalog-result"), "catalog-call", MODEL_CONTEXT,
                        "list_repositories", "1", "{}", Optional.empty(), Optional.empty(),
                        "{\"resultId\":\"catalog-result\",\"data\":{\"secret\":\"catalog-payload\"}}", false),
                new ToolMessage(sessionId, new SessionSequence(3), Optional.of(jobId), createdAt,
                        MessageRole.TOOL, new ResultId("source-result"), "source-call", MODEL_CONTEXT,
                        "codebase_get_method_source", "1", "{}", Optional.of("payment-service"), Optional.of("revision-1"),
                        "{\"resultId\":\"source-result\",\"data\":{\"source\":\"private-source-payload\"}}", true));

        model.decide(new ModelRequest(history, snapshot("catalog"), true), usage -> { });

        org.springframework.ai.chat.messages.Message finalInstruction = chatModel.prompt.getInstructions().getLast();
        assertThat(finalInstruction).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(finalInstruction.getText())
                .contains("source-result")
                .doesNotContain("catalog-result", "catalog-payload", "private-source-payload");
    }

    @Test
    void ignores_google_thought_summary_and_decodes_the_single_actionable_reply() {
        AssistantMessage thought = AssistantMessage.builder()
                .properties(Map.of("isThought", true))
                .content("internal summary")
                .build();
        AssistantMessage reply = AssistantMessage.builder()
                .properties(Map.of("isThought", false))
                .content("{\"citations\":[{\"value\":\"result-1\"}],\"message\":\"Answer\"}")
                .build();
        RecordingChatModel chatModel = new RecordingChatModel(response(thought, reply));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());

        ModelDecision decision = model.decide(request(snapshot("catalog"), true), usage -> { });

        assertThat(decision).isEqualTo(new ModelDecision.Reply(
                new AssistantReply("Answer", List.of(new ResultId("result-1")))));
    }

    @Test
    void decodes_a_tool_call_when_reply_only_so_the_service_can_apply_terminal_policy() {
        RecordingChatModel chatModel = new RecordingChatModel(response(toolResponse("call-1", "catalog", "{}")));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());

        ModelDecision decision = model.decide(request(snapshot("catalog"), true), usage -> { });

        assertThat(decision).isEqualTo(new ModelDecision.UseTool("call-1", new ToolName("catalog"), "{}", MODEL_CONTEXT));
        ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.prompt.getOptions();
        assertThat(CollectionUtils.isEmpty(options.getToolCallbacks())).isTrue();
    }

    @Test
    void rejects_invalid_model_output_as_correctable() {
        List<ChatResponse> invalidResponses = List.of(
                response(toolResponse("call-1", "catalog", "{}"), toolResponse("call-2", "catalog", "{}")),
                response(AssistantMessage.builder().content("prose")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "catalog", "{}"))).build()),
                response(new AssistantMessage("not-json")),
                response(new AssistantMessage("{\"citations\":[],\"message\":\"Answer\"}")),
                response(unsignedToolResponse("call-1", "catalog", "{}")),
                responseWithNullOutput());

        for (ChatResponse invalidResponse : invalidResponses) {
            GoogleConversationModel model = new GoogleConversationModel(new RecordingChatModel(invalidResponse), new PromptResource());

            assertThatThrownBy(() -> model.decide(request(snapshot("catalog"), false), usage -> { }))
                    .isInstanceOf(ModelCallFailure.class)
                    .extracting(exception -> ((ModelCallFailure) exception).kind())
                    .isEqualTo(ModelCallFailure.Kind.CORRECTABLE);
        }
    }

    @Test
    void recordsExactlyOneTerminalModelOutcomeWhenResponseDecodingFails() {
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        GoogleConversationModel model = new GoogleConversationModel(
                new RecordingChatModel(response(new AssistantMessage("not-json"))), new PromptResource(), telemetry);

        assertThatThrownBy(() -> model.decide(request(snapshot("catalog"), false), usage -> { }))
                .isInstanceOf(ModelCallFailure.class);

        verify(telemetry).model("FAILURE", Optional.of("CORRECTABLE"), new ModelUsage(0, 0, 0, false));
        verify(telemetry, never()).model(org.mockito.ArgumentMatchers.eq("SUCCESS"), any(), any());
    }

    @Test
    void returns_a_valid_single_unissued_tool_request_for_registry_authorization() {
        RecordingChatModel chatModel = new RecordingChatModel(response(toolResponse("unissued-call", "not-issued", "{\"value\":1}")));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());

        ModelDecision decision = model.decide(request(snapshot("catalog"), false), usage -> { });

        assertThat(decision).isEqualTo(new ModelDecision.UseTool(
                "unissued-call", new ToolName("not-issued"), "{\"value\":1}", MODEL_CONTEXT));
        ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.prompt.getOptions();
        assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name()).containsExactly("catalog");
    }

    @Test
    void classifies_provider_failures_without_leaking_provider_content() {
        assertFailure(new NonTransientAiException("context window exceeded: secret prompt"), ModelCallFailure.Kind.CONTEXT_TOO_LARGE);
        assertFailure(new TransientAiException("429 request payload"), ModelCallFailure.Kind.TRANSIENT);
        assertFailure(new TransientAiException("503 request payload"), ModelCallFailure.Kind.TRANSIENT);
        assertFailure(new RuntimeException(new java.net.SocketTimeoutException("raw prompt")), ModelCallFailure.Kind.TRANSIENT);
    }

    @Test
    void emits_closed_google_boundary_events_without_request_or_response_content() {
        RecordingChatModel chatModel = new RecordingChatModel(response(toolResponse("call-1", "catalog", "{\"apiKey\":\"provider-secret\"}"),
                new DefaultUsage(7, 3, 10)));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());
        ListAppender<ILoggingEvent> appender = attachAppender(GoogleConversationModel.class);

        try {
            model.decide(request(snapshot("catalog"), true), usage -> { });
            GoogleConversationModel failingModel = new GoogleConversationModel(
                    new FailingChatModel(new TransientAiException("provider-secret-failure")), new PromptResource());
            assertThatThrownBy(() -> failingModel.decide(request(snapshot("catalog"), false), usage -> { }))
                    .isInstanceOf(ModelCallFailure.class);

            assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                    .contains("google_model_request replyOnly={} messageCount={} callbackCount={}",
                            "google_model_response_shape resultCount={} outputPresent={} toolCallCount={} textPresent={}",
                            "google_model_response replyOnly={} resultCategory={} resultCount={} usageAvailable={}",
                            "google_model_failed replyOnly={} closedFailureKind={}");
            assertThat(logTemplatesAndArguments(appender)).doesNotContain("Question", "Alice", "provider-secret", "apiKey",
                    "provider-secret-failure", "catalog");
        } finally {
            detachAppender(GoogleConversationModel.class, appender);
        }
    }

    private static void assertFailure(RuntimeException providerFailure, ModelCallFailure.Kind expectedKind) {
        GoogleConversationModel model = new GoogleConversationModel(new FailingChatModel(providerFailure), new PromptResource());

        assertThatThrownBy(() -> model.decide(request(snapshot("catalog"), false), usage -> { }))
                .isInstanceOf(ModelCallFailure.class)
                .extracting(exception -> ((ModelCallFailure) exception).kind())
                .isEqualTo(expectedKind);
    }

    private static ListAppender<ILoggingEvent> attachAppender(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(Class<?> type, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static String logTemplatesAndArguments(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().flatMap(event -> Stream.concat(Stream.of(event.getMessage()),
                        Arrays.stream(event.getArgumentArray()).map(String::valueOf)))
                .collect(Collectors.joining("\n"));
    }

    private static ModelRequest request(ToolSnapshot snapshot, boolean replyOnly) {
        return new ModelRequest(List.of(new UserMessage(new SessionId("session-1"), new SessionSequence(1), Optional.empty(),
                Instant.parse("2026-08-15T10:15:30Z"), MessageRole.USER, "Alice", "Question")), snapshot, replyOnly);
    }

    private static ToolSnapshot snapshot(String toolName) {
        ToolDefinition definition = new ToolDefinition(new ToolName(toolName), "1", "Catalog repositories", "{\"type\":\"object\"}", ToolKind.CATALOG);
        ToolRegistration<String> registration = new ToolRegistration<>(definition, String.class,
                ignored -> new ToolResult(Optional.empty(), Optional.empty(), "{}", false));
        return new DirectToolRegistry(List.of(registration)).snapshot(false);
    }

    private static AssistantMessage toolResponse(String callId, String name, String arguments) {
        return AssistantMessage.builder()
                .properties(Map.of("thoughtSignatures", List.of(THOUGHT_SIGNATURE)))
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", name, arguments)))
                .build();
    }

    private static AssistantMessage unsignedToolResponse(String callId, String name, String arguments) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", name, arguments)))
                .build();
    }

    private static ChatResponse response(AssistantMessage... messages) {
        return response(null, messages);
    }

    private static ChatResponse response(AssistantMessage message, DefaultUsage usage) {
        return response(usage, message);
    }

    private static ChatResponse response(DefaultUsage usage, AssistantMessage... messages) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(messages).stream().map(Generation::new).toList(), metadata);
    }

    private static ChatResponse responseWithNullOutput() {
        return new ChatResponse(List.of(new Generation(null)), ChatResponseMetadata.builder().build());
    }

    private static final class RecordingChatModel implements ChatModel {

        private final ChatResponse response;
        private Prompt prompt;

        private RecordingChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            return response;
        }

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static final class FailingChatModel implements ChatModel {

        private final RuntimeException failure;

        private FailingChatModel(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw failure;
        }

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
