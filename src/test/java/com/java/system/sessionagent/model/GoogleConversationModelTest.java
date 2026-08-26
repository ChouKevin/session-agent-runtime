package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.AssistantReply;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelCallContext;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.ReplyRequest;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.domain.ToolResult;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
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
import static org.mockito.Mockito.when;

class GoogleConversationModelTest {

    private static final byte[] THOUGHT_SIGNATURE = new byte[]{1, 2, 3, 4};
    private static final String MODEL_CONTEXT = Base64.getEncoder().encodeToString(THOUGHT_SIGNATURE);

    @Test
    void advertises_only_the_issued_snapshot_and_returns_one_tool_request_without_executing_it() {
        RecordingChatModel chatModel = new RecordingChatModel(response(toolResponse("call-1", "catalog", "{}"), new DefaultUsage(7, 3, 10)));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());
        List<ModelUsage> observedUsage = new ArrayList<>();

        ModelDecision decision = model.plan(request(snapshot("catalog")), observedUsage::add);

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

        ModelDecision decision = model.plan(request(snapshot("catalog")), usage -> { });

        assertThat(decision).isInstanceOfSatisfying(ModelDecision.UseTool.class, tool -> {
            assertThat(tool.callId()).startsWith("runtime-").hasSize(44);
            assertThat(tool.toolName()).isEqualTo(new ToolName("catalog"));
            assertThat(tool.arguments()).isEqualTo("{}");
            assertThat(tool.modelContext()).isEqualTo(MODEL_CONTEXT);
        });
    }

    @Test
    void decodes_a_strict_final_reply_and_uses_no_callbacks() {
        RecordingChatModel chatModel = new RecordingChatModel(response(
                new AssistantMessage("{\"citations\":[{\"value\":\"result-1\"}],\"message\":\"Answer\"}"), null));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());
        List<ModelUsage> observedUsage = new ArrayList<>();

        AssistantReply decision = model.reply(replyRequest(), observedUsage::add);

        assertThat(decision).isEqualTo(new AssistantReply("Answer", List.of(new ResultId("result-1"))));
        ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.prompt.getOptions();
        assertThat(CollectionUtils.isEmpty(options.getToolCallbacks())).isTrue();
        assertThat(observedUsage).containsExactly(new ModelUsage(0, 0, 0, false));
    }

    @Test
    void treats_nonblank_no_tool_planning_text_as_answer_ready() {
        RecordingChatModel chatModel = new RecordingChatModel(response(new AssistantMessage("I can answer this.")));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());

        ModelDecision decision = model.plan(request(snapshot("catalog")), usage -> { });

        assertThat(decision).isEqualTo(new ModelDecision.AnswerReady());
        assertThat(chatModel.callCount).isEqualTo(1);
    }

    @Test
    void creates_one_provider_native_structured_reply_call_without_tools() {
        RecordingGoogleChatModel chatModel = new RecordingGoogleChatModel(response(
                new AssistantMessage("{\"citations\":[{\"value\":\"result-1\"}],\"message\":\"Answer\"}"), null));
        GoogleConversationModel model = new GoogleConversationModel(
                chatModel, new PromptResource(), new NoOpConversationTelemetry(), "gemini-3.1-flash-lite");

        AssistantReply reply = model.reply(replyRequest(), usage -> { });

        assertThat(reply).isEqualTo(new AssistantReply("Answer", List.of(new ResultId("result-1"))));
        assertThat(chatModel.callCount).isEqualTo(1);
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) chatModel.prompt.getOptions();
        assertThat(options.getIncludeThoughts()).isFalse();
        assertThat(options.getToolCallbacks()).isEmpty();
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getResponseSchema()).contains("\"message\"", "\"citations\"");
    }

    @Test
    void delegates_final_reply_conversion_to_spring_ai_instead_of_the_runtime_codec() {
        StrictJsonCodec jsonCodec = mock(StrictJsonCodec.class);
        when(jsonCodec.canonicalize(any())).thenReturn("[]");
        RecordingChatModel chatModel = new RecordingChatModel(response(
                new AssistantMessage("{\"citations\":[{\"value\":\"result-1\"}],\"message\":\"Answer\"}"), null));
        GoogleConversationModel model = new GoogleConversationModel(ChatClient.create(chatModel), new PromptResource(),
                new SpringAiToolCallbackFactory(), new ConversationHistoryProjector(), jsonCodec);

        AssistantReply reply = model.reply(replyRequest(), usage -> { });

        assertThat(reply).isEqualTo(new AssistantReply("Answer", List.of(new ResultId("result-1"))));
        verify(jsonCodec, never()).decode(any(), org.mockito.ArgumentMatchers.eq(AssistantReply.class));
    }

    @Test
    void appends_exact_citeable_result_ids_to_the_final_reply_request() {
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

        model.reply(new ReplyRequest(history, new ModelCallContext(sessionId, jobId, 2)), usage -> { });

        org.springframework.ai.chat.messages.Message finalInstruction = chatModel.prompt.getInstructions().getLast();
        assertThat(finalInstruction).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(finalInstruction.getText())
                .contains("source-result")
                .contains("complete empty code search")
                .contains("source citation does not replace")
                .doesNotContain("catalog-result", "catalog-payload", "private-source-payload");
    }

    @Test
    void asks_spring_ai_to_enforce_the_final_reply_json_schema() {
        RecordingGoogleChatModel chatModel = new RecordingGoogleChatModel(response(
                new AssistantMessage("{\"citations\":[{\"value\":\"result-1\"}],\"message\":\"Answer\"}"), null));
        GoogleConversationModel model = new GoogleConversationModel(
                chatModel, new PromptResource(), new NoOpConversationTelemetry(), "gemini-3.1-flash-lite");

        model.reply(replyRequest(), usage -> { });

        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) chatModel.prompt.getOptions();
        assertThat(options.getIncludeThoughts()).isFalse();
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getResponseSchema())
                .contains("\"message\"")
                .contains("\"citations\"")
                .contains("\"value\"")
                .contains("\"required\" : [ \"citations\", \"message\" ]")
                .contains("\"required\" : [ \"value\" ]");
        assertThat(options.getToolCallbacks()).isEmpty();
    }

    @Test
    void rejects_a_tool_call_during_final_reply() {
        RecordingChatModel chatModel = new RecordingChatModel(response(toolResponse("call-1", "catalog", "{}")));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());

        assertThatThrownBy(() -> model.reply(replyRequest(), usage -> { }))
                .isInstanceOf(ModelCallFailure.class)
                .extracting(exception -> ((ModelCallFailure) exception).kind())
                .isEqualTo(ModelCallFailure.Kind.CORRECTABLE);

        ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.prompt.getOptions();
        assertThat(CollectionUtils.isEmpty(options.getToolCallbacks())).isTrue();
    }

    @Test
    void rejects_invalid_model_output_as_correctable() {
        List<ChatResponse> invalidResponses = List.of(
                response(toolResponse("call-1", "catalog", "{}"), toolResponse("call-2", "catalog", "{}")),
                response(AssistantMessage.builder().content("prose")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "catalog", "{}"))).build()),
                response(new AssistantMessage("")),
                response(unsignedToolResponse("call-1", "catalog", "{}")),
                responseWithNullOutput());

        for (ChatResponse invalidResponse : invalidResponses) {
            GoogleConversationModel model = new GoogleConversationModel(new RecordingChatModel(invalidResponse), new PromptResource());

            assertThatThrownBy(() -> model.plan(request(snapshot("catalog")), usage -> { }))
                    .isInstanceOf(ModelCallFailure.class)
                    .extracting(exception -> ((ModelCallFailure) exception).kind())
                    .isEqualTo(ModelCallFailure.Kind.CORRECTABLE);
        }
    }

    @Test
    void does_not_repeat_the_provider_call_when_final_json_is_invalid() {
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        RecordingChatModel chatModel = new RecordingChatModel(response(new AssistantMessage("not-json")));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource(), telemetry);

        assertThatThrownBy(() -> model.reply(replyRequest(), usage -> { }))
                .isInstanceOf(ModelCallFailure.class);

        verify(telemetry).model("FAILURE", Optional.of("CORRECTABLE"), new ModelUsage(0, 0, 0, false));
        verify(telemetry, never()).model(org.mockito.ArgumentMatchers.eq("SUCCESS"), any(), any());
        assertThat(chatModel.callCount).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("replyProviderFailures")
    void classifies_reply_provider_failures_without_relabeling_them_as_decode_failures(
            RuntimeException providerFailure, ModelCallFailure.Kind expectedKind) {
        FailingChatModel chatModel = new FailingChatModel(providerFailure);
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());

        assertThatThrownBy(() -> model.reply(replyRequest(), usage -> { }))
                .isInstanceOf(ModelCallFailure.class)
                .extracting(exception -> ((ModelCallFailure) exception).kind())
                .isEqualTo(expectedKind);

        assertThat(chatModel.callCount).isEqualTo(1);
    }

    @Test
    void returns_a_valid_single_unissued_tool_request_for_registry_authorization() {
        RecordingChatModel chatModel = new RecordingChatModel(response(toolResponse("unissued-call", "not-issued", "{\"value\":1}")));
        GoogleConversationModel model = new GoogleConversationModel(chatModel, new PromptResource());

        ModelDecision decision = model.plan(request(snapshot("catalog")), usage -> { });

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
            model.plan(request(snapshot("catalog")), usage -> { });
            GoogleConversationModel failingModel = new GoogleConversationModel(
                    new FailingChatModel(new TransientAiException("provider-secret-failure")), new PromptResource());
            assertThatThrownBy(() -> failingModel.plan(request(snapshot("catalog")), usage -> { }))
                    .isInstanceOf(ModelCallFailure.class);

            assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                    .contains("google_model_request phase=PLAN messageCount={} callbackCount={}",
                            "google_model_response_shape resultCount={} outputPresent={} toolCallCount={} textPresent={}",
                            "google_model_response phase=PLAN resultCategory={} resultCount={} usageAvailable={}",
                            "google_model_failed phase=PLAN closedFailureKind={}");
            assertThat(logTemplatesAndArguments(appender)).doesNotContain("Question", "Alice", "provider-secret", "apiKey",
                    "provider-secret-failure", "catalog");
        } finally {
            detachAppender(GoogleConversationModel.class, appender);
        }
    }

    private static void assertFailure(RuntimeException providerFailure, ModelCallFailure.Kind expectedKind) {
        GoogleConversationModel model = new GoogleConversationModel(new FailingChatModel(providerFailure), new PromptResource());

        assertThatThrownBy(() -> model.plan(request(snapshot("catalog")), usage -> { }))
                .isInstanceOf(ModelCallFailure.class)
                .extracting(exception -> ((ModelCallFailure) exception).kind())
                .isEqualTo(expectedKind);
    }

    private static Stream<Arguments> replyProviderFailures() {
        return Stream.of(
                Arguments.of(new TransientAiException("provider unavailable"), ModelCallFailure.Kind.TRANSIENT),
                Arguments.of(new RuntimeException("provider rejected request"), ModelCallFailure.Kind.TERMINAL));
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

    private static ModelRequest request(ToolSnapshot snapshot) {
        return new ModelRequest(List.of(new UserMessage(new SessionId("session-1"), new SessionSequence(1), Optional.empty(),
                Instant.parse("2026-08-15T10:15:30Z"), MessageRole.USER, "Alice", "Question")), snapshot,
                new ModelCallContext(new SessionId("session-1"), new MessageJobId("job-1"), 1));
    }

    private static ReplyRequest replyRequest() {
        return new ReplyRequest(request(snapshot("catalog")).history(),
                new ModelCallContext(new SessionId("session-1"), new MessageJobId("job-1"), 2));
    }

    private static ToolSnapshot snapshot(String toolName) {
        ToolDefinition definition = new ToolDefinition(new ToolName(toolName), "1", "Catalog repositories", "{\"type\":\"object\"}", ToolKind.CATALOG);
        ToolRegistration<String> registration = new ToolRegistration<>(definition, String.class,
                ignored -> new ToolResult(Optional.empty(), Optional.empty(), "{}", false));
        return new DirectToolRegistry(List.of(registration)).snapshot();
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
        private int callCount;

        private RecordingChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            this.callCount++;
            return response;
        }

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static final class RecordingGoogleChatModel implements ChatModel {

        private final ChatResponse response;
        private Prompt prompt;
        private int callCount;

        private RecordingGoogleChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            this.callCount++;
            return response;
        }

        @Override
        public GoogleGenAiChatOptions getOptions() {
            return GoogleGenAiChatOptions.builder()
                    .model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
                    .build();
        }
    }

    private static final class FailingChatModel implements ChatModel {

        private final RuntimeException failure;
        private int callCount;

        private FailingChatModel(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.callCount++;
            throw failure;
        }

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
