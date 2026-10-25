package com.java.system.sessionagent.model.adapter.springai;

import com.java.system.sessionagent.conversation.domain.ModelCallObservation;
import com.java.system.sessionagent.conversation.domain.CacheTokenUsage;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import java.util.concurrent.atomic.AtomicInteger;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.IntentRequest;
import com.java.system.sessionagent.conversation.domain.IntentResult;
import com.java.system.sessionagent.conversation.domain.FollowUpIntent;
import com.java.system.sessionagent.conversation.domain.WaitingChoice;
import com.java.system.sessionagent.tool.port.ToolCapability;
import java.util.Set;
import com.google.genai.errors.ClientException;
import com.java.system.sessionagent.bootstrap.adapter.metrics.MicrometerConversationTelemetry;
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
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.RecordComponent;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class SpringAiConversationModelTest {

    private static final ModelRouteId GOOGLE_ROUTE_ID = new ModelRouteId("google-genai");

    @Test
    void supported_sdk_decorator_preserves_valid_response_identity_and_transport_ownership() {
        OpenAIClient client = mock(OpenAIClient.class);
        ChatService chat = mock(ChatService.class);
        ChatCompletionService completions = mock(ChatCompletionService.class);
        ChatCompletionService.WithRawResponse raw = mock(ChatCompletionService.WithRawResponse.class);
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);
        when(completions.withRawResponse()).thenReturn(raw);
        ChatCompletion completion = ChatCompletion.builder().id("completion").created(0).model("test").choices(List.of())
                .usage(CompletionUsage.builder().promptTokens(8).completionTokens(2).totalTokens(10).build()).build();
        Headers headers = mock(Headers.class);
        InputStream body = new ByteArrayInputStream(new byte[] {1, 2});
        AtomicInteger closes = new AtomicInteger();
        HttpResponseFor<ChatCompletion> response = new HttpResponseFor<>() {
            @Override public ChatCompletion parse() { return completion; }
            @Override public int statusCode() { return 200; }
            @Override public Headers headers() { return headers; }
            @Override public Optional<String> requestId() { return Optional.of("request-id"); }
            @Override public InputStream body() { return body; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder().model("test").messages(List.of()).build();
        RequestOptions options = RequestOptions.none();
        when(raw.create(request, options)).thenReturn(response);
        OpenAiUsagePreservingClient decorated = new OpenAiUsagePreservingClient(client);

        try (HttpResponseFor<ChatCompletion> actual = decorated.chat().completions().withRawResponse().create(request, options)) {
            assertThat(actual.parse()).isSameAs(completion);
            assertThat(actual.statusCode()).isEqualTo(200);
            assertThat(actual.headers()).isSameAs(headers);
            assertThat(actual.requestId()).contains("request-id");
            assertThat(actual.body()).isSameAs(body);
            assertThat(closes).hasValue(0);
        }
        assertThat(closes).hasValue(1);
        decorated.close();
        verify(client).close();
    }

    @ParameterizedTest(name = "{0}: known cache pair={1}")
    @MethodSource("responseCategories")
    void observes_each_returned_response_before_category_specific_validation(String category, boolean known) {
        GenerateContentResponseUsageMetadata.Builder raw = GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(8);
        if (known) {
            raw.cachedContentTokenCount(3);
        }
        Usage usage = GoogleGenAiUsage.from(raw.build());
        AssistantMessage message = switch (category) {
            case "intent" -> new AssistantMessage("{\"intent\":\"CONTINUE\"}");
            case "malformed-answer", "malformed-summary" -> new AssistantMessage(" ");
            case "malformed-intent" -> new AssistantMessage("not JSON");
            case "tools" -> AssistantMessage.builder().toolCalls(List.of(
                    new AssistantMessage.ToolCall("call", "function", "opaque", "{}"))).build();
            case "malformed-tools" -> AssistantMessage.builder().toolCalls(List.of(
                    new AssistantMessage.ToolCall("call", "function", "opaque", "[]"))).build();
            default -> new AssistantMessage("answer");
        };
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        ChatModel chat = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt prompt) {
                assertThat(reservations).hasValue(1);
                requests.incrementAndGet();
                return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder().usage(usage).build());
            }
        };
        SpringAiConversationModel adapter = new SpringAiConversationModel(chat, new SystemPrompt(), new NoOpConversationTelemetry(),
                new ObjectMapper(), new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));
        List<ModelCallObservation> observed = new ArrayList<>();
        Runnable invoke = () -> {
            if (category.endsWith("intent")) {
                adapter.classifyIntent(intentRequest(), () -> { reservations.incrementAndGet(); return 7; }, observed::add);
            } else if (category.endsWith("summary")) {
                adapter.summarize(new ContextCompactionRequest(Optional.empty(), List.of(intentRequest().initiatingMessage())),
                        () -> { reservations.incrementAndGet(); return 7; }, observed::add);
            } else {
                adapter.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()),
                        "wrap".equals(category) ? ModelRequest.Purpose.WRAP_UP : ModelRequest.Purpose.ANSWER),
                        () -> { reservations.incrementAndGet(); return 7; }, observed::add);
            }
        };
        if (category.startsWith("malformed")) {
            assertThatThrownBy(invoke::run).isInstanceOf(ModelCallFailure.class);
        } else {
            invoke.run();
        }
        assertThat(requests).hasValue(1);
        assertThat(reservations).hasValue(1);
        assertThat(observed).containsExactly(new ModelCallObservation(7,
                known ? Optional.of(new CacheTokenUsage(8, 3)) : Optional.empty()));
    }

    private static Stream<Arguments> responseCategories() {
        return Stream.of("answer", "tools", "summary", "intent", "wrap", "malformed-answer",
                "malformed-tools", "malformed-summary", "malformed-intent")
                .flatMap(category -> Stream.of(Arguments.of(category, true), Arguments.of(category, false)));
    }

    @Test
    void unsupported_metadata_is_unknown_even_when_normalized_usage_looks_usable() {
        List<ModelCallObservation> observed = new ArrayList<>();
        ModelCallResult result = model(new AssistantMessage("done"), usage(8, 2, 10, Optional.of(3L)), GOOGLE_ROUTE_ID)
                .respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 2, observed::add);
        assertThat(result.reply()).isEqualTo(new ModelReply.Text("done"));
        assertThat(observed).containsExactly(new ModelCallObservation(2, Optional.empty()));
    }

    @Test
    void denied_reservation_or_provider_exception_does_not_invent_an_observation() {
        AtomicInteger requests = new AtomicInteger();
        List<ModelCallObservation> observed = new ArrayList<>();
        ChatModel chat = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt prompt) {
                requests.incrementAndGet();
                throw new IllegalStateException("provider unavailable");
            }
        };
        SpringAiConversationModel adapter = new SpringAiConversationModel(chat, new SystemPrompt(), new NoOpConversationTelemetry(),
                new ObjectMapper(), new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));
        ModelRequest request = new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER);
        IllegalStateException denied = new IllegalStateException("reservation denied");
        assertThatThrownBy(() -> adapter.respond(request, () -> { throw denied; }, observed::add)).isSameAs(denied);
        assertThat(requests).hasValue(0);
        assertThat(observed).isEmpty();
        assertThatThrownBy(() -> adapter.respond(request, () -> 3, observed::add)).isInstanceOf(ModelCallFailure.class);
        assertThat(requests).hasValue(1);
        assertThat(observed).isEmpty();
    }

    @Test
    void exposes_cached_input_tokens_as_an_optional_usage_component() {
        assertThat(ModelUsage.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .contains("cachedInputTokens");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cacheUsageCases")
    void maps_cache_read_input_tokens_without_changing_existing_usage(
            String scenario,
            Optional<Long> providerValue,
            OptionalLong expectedCachedInputTokens) {
        Usage usage = usage(5, 3, 8, providerValue);
        ModelCallResult result = model(new AssistantMessage("done"), usage, GOOGLE_ROUTE_ID)
                .respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { });

        assertThat(result.usage()).as(scenario)
                .isEqualTo(new ModelUsage(5, 3, 8, true, expectedCachedInputTokens));
    }

    private static Stream<Arguments> cacheUsageCases() {
        return Stream.of(
                Arguments.of("hit", Optional.of(7L), OptionalLong.of(7)),
                Arguments.of("zero", Optional.of(0L), OptionalLong.of(0)),
                Arguments.of("unknown", Optional.empty(), OptionalLong.empty()),
                Arguments.of("invalid-negative", Optional.of(-1L), OptionalLong.empty()));
    }

    @Test
    void retains_known_cache_usage_when_other_provider_usage_is_unavailable() {
        Usage usage = usage(null, null, null, Optional.of(4L));
        ModelCallResult result = model(new AssistantMessage("done"), usage, GOOGLE_ROUTE_ID)
                .respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { });

        assertThat(result.usage()).isEqualTo(new ModelUsage(0, 0, 0, false, OptionalLong.of(4)));
    }

    @Test
    void records_cached_usage_for_compaction_telemetry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Usage usage = usage(5, 3, 8, Optional.of(4L));
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("summary"))),
                        ChatResponseMetadata.builder().usage(usage).build());
            }

            @Override public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new SystemPrompt(),
                new MicrometerConversationTelemetry(registry), mapper,
                new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));
        UserMessage history = new UserMessage(new SessionId("session-1"), new SessionSequence(1),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.USER, "user", "history");

        model.summarize(new ContextCompactionRequest(Optional.empty(), List.of(history)), () -> 1, ignored -> { });

        assertThat(registry.get("session_agent.model.cache_usage").tag("state", "HIT").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("session_agent.model.tokens").tag("kind", "cached_input").counter().count())
                .isEqualTo(4);
    }

    @Test
    void preserves_nonblank_provider_ids_and_generic_arguments_for_mixed_text_calls() {
        AssistantMessage message = AssistantMessage.builder().content("I will inspect both.").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "first", "{\"query\":\"fees\"}"),
                new AssistantMessage.ToolCall("call-2", "function", "second", "{\"limit\":2}"))).build();
        SpringAiConversationModel model = model(message, GOOGLE_ROUTE_ID);

        ModelCallResult result = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, usage -> { });
        ModelReply reply = result.reply();

        assertThat(reply).isEqualTo(new ModelReply.UseTools(Optional.of("I will inspect both."), List.of(
                new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of("query", "fees")),
                new ToolRequest(new ToolCallId("call-2"), new ToolName("second"), Map.of("limit", 2)))));
    }

    @Test
    void preserves_json_looking_final_text_without_decoding_it() {
        SpringAiConversationModel model = model(new AssistantMessage("{\"message\":\"still plain text\"}"), GOOGLE_ROUTE_ID);

        ModelCallResult result = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, usage -> { });
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
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new SystemPrompt(), new NoOpConversationTelemetry(), mapper,
                new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));

        ModelCallResult result = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, usage -> { });

        assertThat(result.reply()).isEqualTo(new ModelReply.Text("done"));
        assertThat(result.usage()).isEqualTo(new ModelUsage(0, 0, 0, false));
    }

    @Test
    void distinguishes_provider_input_token_limits_from_non_token_support_limits() {
        ClientException providerFailure = new ClientException(400, "INVALID_ARGUMENT",
                "The input token count (100) exceeds the maximum number of tokens allowed (10).");
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new RuntimeException("Failed to generate content", providerFailure);
            }

            @Override public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new SystemPrompt(), new NoOpConversationTelemetry(), mapper,
                new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));

        assertThatThrownBy(() -> model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, usage -> { }))
                .isInstanceOfSatisfying(ModelCallFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.CONTEXT_TOO_LARGE));

        ChatModel terminalChatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new RuntimeException("Model only supports up to one response candidate");
            }

            @Override public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        SpringAiConversationModel terminalModel = new SpringAiConversationModel(terminalChatModel, new SystemPrompt(),
                new NoOpConversationTelemetry(), mapper, new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));

        assertThatThrownBy(() -> terminalModel.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, usage -> { }))
                .isInstanceOfSatisfying(ModelCallFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TERMINAL));
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
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new SystemPrompt(), new NoOpConversationTelemetry(), mapper,
                new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));
        UserMessage history = new UserMessage(new SessionId("session-1"), new SessionSequence(1), Optional.of(new MessageJobId("job-1")),
                Instant.EPOCH, MessageRole.USER, "user", "history");

        String summary = model.summarize(new ContextCompactionRequest(Optional.empty(), List.of(history)), () -> 1, ignored -> { });

        assertThat(summary).isEqualTo("summary");
        assertThat(prompts).hasSize(1);
        ChatOptions promptOptions = prompts.getFirst().getOptions();
        assertThat(promptOptions).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions noToolOptions = (ToolCallingChatOptions) promptOptions;
        assertThat(noToolOptions).isNotSameAs(defaultOptions);
        assertThat(noToolOptions.getToolCallbacks()).isEmpty();
    }

    @Test
    void an_empty_snapshot_explicitly_clears_default_tool_callbacks_for_the_actual_provider_prompt() {
        ToolCallback defaultToolCallback = mock(ToolCallback.class);
        ToolCallingChatOptions defaultOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(defaultToolCallback))
                .build();
        List<Prompt> prompts = new ArrayList<>();
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("No tools used"))),
                        ChatResponseMetadata.builder().build());
            }

            @Override public ToolCallingChatOptions getOptions() {
                return defaultOptions;
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new SystemPrompt(),
                new NoOpConversationTelemetry(), mapper, new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));

        ModelCallResult result = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER),
                () -> 1, ignored -> { });

        assertThat(result.reply()).isEqualTo(new ModelReply.Text("No tools used"));
        assertThat(prompts).hasSize(1);
        ChatOptions promptOptions = prompts.getFirst().getOptions();
        assertThat(promptOptions).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions noToolOptions = (ToolCallingChatOptions) promptOptions;
        assertThat(noToolOptions).isNotSameAs(defaultOptions);
        assertThat(noToolOptions.getToolCallbacks()).isEmpty();
        assertThat(defaultOptions.getToolCallbacks()).containsExactly(defaultToolCallback);
    }

    @Test
    void generates_unique_ids_for_missing_provider_ids_and_rejects_duplicate_provider_ids() {
        AssistantMessage withoutIds = AssistantMessage.builder().content("Checking.").toolCalls(List.of(
                new AssistantMessage.ToolCall("", "function", "first", "{}"),
                new AssistantMessage.ToolCall(" ", "function", "second", "{}"))).build();

        ModelReply.UseTools generated = (ModelReply.UseTools) model(withoutIds, GOOGLE_ROUTE_ID)
                .respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, usage -> { }).reply();
        assertThat(generated.requests()).extracting(request -> request.toolCallId()).doesNotHaveDuplicates();

        AssistantMessage duplicateIds = AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "first", "{}"),
                new AssistantMessage.ToolCall("call-1", "function", "second", "{}"))).build();
        assertThatThrownBy(() -> model(duplicateIds, GOOGLE_ROUTE_ID).respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER),
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

        assertThatThrownBy(() -> model.respond(new ModelRequest(malformedHistory, new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, usage -> { }))
                .isInstanceOf(ModelCallFailure.class);
        assertThat(providerCalled).isFalse();
    }

    @Test
    void restores_ordered_reasoning_and_part_signatures_for_the_next_native_tool_call() {
        OpaquePayload thoughtSignature = new OpaquePayload("google", "thought_signature", "AQID");
        OpaquePayload callSignature = new OpaquePayload("google", "thought_signature", "BAU=");
        AssistantMessage firstResponse = AssistantMessage.builder()
                .part(new ReasoningPart("Need the fee.", null, thoughtSignature, Map.of()))
                .part(new TextPart("Inspecting.", thoughtSignature, Map.of()))
                .part(new ToolCallPart(new AssistantMessage.ToolCall("", "function", "first", "{}"),
                        callSignature, Map.of()))
                .properties(Map.of("finishReason", "STOP")).build();
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
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new SystemPrompt(), new NoOpConversationTelemetry(),
                mapper, new SpringAiMessagePartsHandler(routeId));

        ModelCallResult firstResult = model.respond(new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, usage -> { });
        ModelContinuation continuation = firstResult.continuation().orElseThrow();
        ToolRequest requested = ((ModelReply.UseTools) firstResult.reply()).requests().getFirst();
        List<SessionMessage> history = List.of(
                new AssistantToolCallsMessage(new SessionId("session-1"), new SessionSequence(2), Optional.of(new MessageJobId("job-1")),
                        Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Inspecting."),
                        List.of(requested)),
                new ToolObservation(new SessionId("session-1"), new SessionSequence(3), Optional.of(new MessageJobId("job-1")),
                        Instant.EPOCH, MessageRole.TOOL, requested.toolCallId(), "first", Map.of("isError", false, "result", Map.of())));

        ModelCallResult secondResult = model.respond(new ModelRequest(history, Map.of(new SessionSequence(2), continuation),
                new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 2, usage -> { });

        assertThat(firstResult.reply()).isEqualTo(new ModelReply.UseTools(Optional.of("Inspecting."), List.of(requested)));
        assertThat(secondResult.reply()).isEqualTo(new ModelReply.Text("done"));
        assertThat(prompts).hasSize(2);
        org.springframework.ai.chat.messages.AssistantMessage restored = (org.springframework.ai.chat.messages.AssistantMessage)
                prompts.get(1).getInstructions().get(1);
        assertThat(restored.getMetadata()).doesNotContainKeys("thoughtSignatures", "finishReason", "candidateIndex");
        assertThat(restored.getParts()).containsExactly(firstResponse.getParts().get(0), firstResponse.getParts().get(1),
                new ToolCallPart(new AssistantMessage.ToolCall(requested.toolCallId().value(), "function", "first", "{}"),
                        callSignature, Map.of()));
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
        List<ModelCallObservation> observed = new ArrayList<>();
        ModelContinuation malformed = new ModelContinuation(routeId,
                "spring-ai-message-parts-v1", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> model.respond(new ModelRequest(history, Map.of(new SessionSequence(2), malformed), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER),
                () -> { reserved.set(true); return 1; }, observed::add))
                .isInstanceOfSatisfying(ModelCallFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TERMINAL));

        assertThat(reserved).isFalse();
        assertThat(providerCalled).isFalse();
        assertThat(observed).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("validIntentReplies")
    void classifies_closed_internal_intent_results_without_parsing_ordinary_final_answers(
            String json, FollowUpIntent intent, Optional<String> clarification) {
        SpringAiConversationModel adapter = model(new AssistantMessage(json), GOOGLE_ROUTE_ID);
        AtomicBoolean reserved = new AtomicBoolean();
        IntentResult result = adapter.classifyIntent(intentRequest(), () -> { reserved.set(true); return 7; }, ignored -> { });
        assertThat(result).isEqualTo(new IntentResult(intent, clarification));
        assertThat(reserved).isTrue();
    }

    private static Stream<Arguments> validIntentReplies() {
        return Stream.of(
                Arguments.of("{\"intent\":\"CONTINUE\"}", FollowUpIntent.CONTINUE, Optional.empty()),
                Arguments.of("{\"intent\":\"DELIVER\"}", FollowUpIntent.DELIVER, Optional.empty()),
                Arguments.of("{\"intent\":\"NEW_QUESTION\"}", FollowUpIntent.NEW_QUESTION, Optional.empty()),
                Arguments.of("{\"intent\":\"CLARIFY\",\"clarification\":\"Which option?\"}",
                        FollowUpIntent.CLARIFY, Optional.of("Which option?")));
    }

    @ParameterizedTest
    @MethodSource("invalidIntentReplies")
    void malformed_internal_intent_cannot_grant_a_mode(String reply) {
        SpringAiConversationModel adapter = model(new AssistantMessage(reply), GOOGLE_ROUTE_ID);
        assertThatThrownBy(() -> adapter.classifyIntent(intentRequest(), () -> 1, ignored -> { })).isInstanceOf(ModelCallFailure.class);
    }

    private static Stream<String> invalidIntentReplies() {
        return Stream.of("[]", "null", "{\"intent\":\"continue\"}", "{\"intent\":\"OTHER\"}",
                "{\"intent\":\"CONTINUE\",\"extra\":true}", "{\"intent\":\"CLARIFY\"}",
                "{\"intent\":\"CLARIFY\",\"clarification\":\"  \"}",
                "{\"intent\":\"DELIVER\",\"clarification\":\"not allowed\"}",
                "{\"intent\":\"CONTINUE\",\"intent\":\"DELIVER\"}",
                "{\"intent\":\"CONTINUE\"} {\"intent\":\"DELIVER\"}",
                "```json\n{\"intent\":\"DELIVER\"}\n```");
    }

    @Test
    void intent_prompt_repeats_the_exact_current_user_after_later_queued_users_and_clears_defaults() {
        List<Prompt> prompts = new ArrayList<>();
        ToolCallingChatOptions defaults = ToolCallingChatOptions.builder().toolCallbacks(List.of(mock(ToolCallback.class))).build();
        ChatModel chat = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() { return defaults; }
            @Override public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"intent\":\"CONTINUE\"}"))));
            }
        };
        SpringAiConversationModel adapter = new SpringAiConversationModel(chat, new SystemPrompt(), new NoOpConversationTelemetry(),
                new ObjectMapper(), new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));
        IntentRequest request = intentRequest();
        adapter.classifyIntent(request, () -> 1, ignored -> { });
        Prompt prompt = prompts.getFirst();
        assertThat(prompt.getInstructions().getLast().getText()).contains("current choice").doesNotContain("later queued question");
        assertThat(prompt.getInstructions().getFirst().getText()).contains("Current user sequence: 2");
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()).isEmpty();
        assertThat(request.history().getLast()).isInstanceOfSatisfying(UserMessage.class,
                user -> assertThat(user.message()).isEqualTo("later queued question"));
    }

    @Test
    void internal_intent_rejects_a_provider_tool_call_instead_of_treating_it_as_permission() {
        AssistantMessage tool = AssistantMessage.builder().content("{\"intent\":\"DELIVER\"}")
                .toolCalls(List.of(new AssistantMessage.ToolCall("x", "function", "opaque", "{}"))).build();
        assertThatThrownBy(() -> model(tool, GOOGLE_ROUTE_ID).classifyIntent(intentRequest(), () -> 1, ignored -> { }))
                .isInstanceOf(ModelCallFailure.class);
    }

    @Test
    void wrap_directive_is_transient_and_the_response_remains_plain_text() {
        List<Prompt> prompts = new ArrayList<>();
        ChatModel chat = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"question\":\"Continue?\"}"))));
            }
        };
        SpringAiConversationModel adapter = new SpringAiConversationModel(chat, new SystemPrompt(), new NoOpConversationTelemetry(),
                new ObjectMapper(), new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));
        List<SessionMessage> history = List.of(intentRequest().initiatingMessage());
        ModelRequest request = new ModelRequest(history, Map.of(), new ToolSnapshot(List.of()), Optional.empty(), ModelRequest.Purpose.WRAP_UP);
        assertThat(adapter.respond(request, () -> 3, ignored -> { }).reply()).isEqualTo(new ModelReply.Text("{\"question\":\"Continue?\"}"));
        assertThat(request.history()).containsExactlyElementsOf(history);
        assertThat(prompts.getFirst().getInstructions().getFirst().getText())
                .isEqualTo(new SystemPrompt().content() + "\n\n" + request.transientDirective());
        assertThat(prompts.getFirst().getInstructions().subList(1, prompts.getFirst().getInstructions().size()))
                .containsExactlyElementsOf(new HistoryProjector(new ObjectMapper()).project(history));
        assertThat(((ToolCallingChatOptions) prompts.getFirst().getOptions()).getToolCallbacks()).isEmpty();
    }

    @Test
    void report_only_directive_requires_disclosing_gaps_without_becoming_history() {
        List<Prompt> prompts = new ArrayList<>();
        ChatModel chat = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Unknown areas disclosed"))));
            }
        };
        SpringAiConversationModel adapter = new SpringAiConversationModel(chat, new SystemPrompt(), new NoOpConversationTelemetry(),
                new ObjectMapper(), new SpringAiMessagePartsHandler(GOOGLE_ROUTE_ID));
        try (ToolSnapshot tools = new ToolSnapshot(List.of()).restrictedTo(Set.of(ToolCapability.REPORT_GENERATION))) {
            ModelRequest request = new ModelRequest(List.of(), tools, ModelRequest.Purpose.ANSWER);
            adapter.respond(request, () -> 2, ignored -> { });
            assertThat(request.history()).isEmpty();
            assertThat(prompts.getFirst().getInstructions().getFirst().getText())
                    .isEqualTo(new SystemPrompt().content() + "\n\n" + request.transientDirective())
                    .contains("unknown", "uncovered", "retained evidence");
            assertThat(((ToolCallingChatOptions) prompts.getFirst().getOptions()).getToolCallbacks()).isEmpty();
        }
    }

    private static IntentRequest intentRequest() {
        SessionId session = new SessionId("session-1");
        UserMessage current = new UserMessage(session, new SessionSequence(2), Optional.of(new MessageJobId("job-2")),
                Instant.EPOCH, MessageRole.USER, "alice", "current choice");
        UserMessage later = new UserMessage(session, new SessionSequence(3), Optional.of(new MessageJobId("job-3")),
                Instant.EPOCH, MessageRole.USER, "alice", "later queued question");
        return new IntentRequest(current, List.of(current, later), Optional.empty(),
                new WaitingChoice(new MessageJobId("job-1"), new SessionSequence(1), 1));
    }

    private static SpringAiConversationModel model(AssistantMessage message, ModelRouteId routeId) {
        return model(message, new AtomicBoolean(), routeId);
    }

    private static SpringAiConversationModel model(AssistantMessage message, Usage usage, ModelRouteId routeId) {
        ChatModel chatModel = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder().usage(usage).build());
            }

            @Override public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        return new SpringAiConversationModel(chatModel, new SystemPrompt(), new NoOpConversationTelemetry(), mapper,
                new SpringAiMessagePartsHandler(routeId));
    }

    private static Usage usage(Integer promptTokens, Integer completionTokens, Integer totalTokens, Optional<Long> cachedInputTokens) {
        return new Usage() {
            @Override public Integer getPromptTokens() {
                return promptTokens;
            }

            @Override public Integer getCompletionTokens() {
                return completionTokens;
            }

            @Override public Integer getTotalTokens() {
                return totalTokens;
            }

            @Override public Long getCacheReadInputTokens() {
                return cachedInputTokens.orElse(null);
            }

            @Override public Object getNativeUsage() {
                return Map.of();
            }
        };
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
        return new SpringAiConversationModel(chatModel, new SystemPrompt(), new NoOpConversationTelemetry(), mapper,
                new SpringAiMessagePartsHandler(routeId));
    }
}
