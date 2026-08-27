package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.AssistantReply;
import com.java.system.sessionagent.conversation.domain.ModelCallContext;
import com.java.system.sessionagent.conversation.domain.ModelCallOutcome;
import com.java.system.sessionagent.conversation.domain.ModelCallPhase;
import com.java.system.sessionagent.conversation.domain.ModelCallRecord;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.ReplyRequest;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallRecorder;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class GoogleConversationModel implements com.java.system.sessionagent.conversation.port.out.ConversationModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleConversationModel.class);
    private static final String IS_THOUGHT = "isThought";
    private static final String THOUGHT_SIGNATURES = "thoughtSignatures";
    private final ChatClient chatClient;
    private final PromptResource promptResource;
    private final SpringAiToolCallbackFactory callbackFactory;
    private final ConversationHistoryProjector historyProjector;
    private final StrictJsonCodec jsonCodec;
    private final ConversationTelemetry telemetry;
    private final Optional<GoogleGenAiChatOptions> googleOptions;
    private final ModelCallRecorder recorder;
    private final Clock clock;
    private final String modelName;

    public GoogleConversationModel(ChatModel chatModel, PromptResource promptResource) {
        this(chatClientFor(chatModel), promptResource, new SpringAiToolCallbackFactory(),
                new ConversationHistoryProjector(), new StrictJsonCodec(), new NoOpConversationTelemetry(), Optional.empty(),
                ModelCallRecorder.noop(), Clock.systemUTC(), inferredModelName(chatModel));
    }

    public GoogleConversationModel(ChatModel chatModel, PromptResource promptResource, ConversationTelemetry telemetry) {
        this(chatClientFor(chatModel), promptResource, new SpringAiToolCallbackFactory(),
                new ConversationHistoryProjector(), new StrictJsonCodec(), telemetry, Optional.empty(), ModelCallRecorder.noop(),
                Clock.systemUTC(), inferredModelName(chatModel));
    }

    public GoogleConversationModel(ChatModel chatModel, PromptResource promptResource, ConversationTelemetry telemetry,
                                   String modelName) {
        this(chatClientFor(chatModel), promptResource, new SpringAiToolCallbackFactory(),
                new ConversationHistoryProjector(), new StrictJsonCodec(), telemetry, Optional.of(configureGoogleOptions(chatModel, modelName)),
                ModelCallRecorder.noop(), Clock.systemUTC(), modelName);
    }

    public GoogleConversationModel(
            ChatModel chatModel,
            PromptResource promptResource,
            ConversationTelemetry telemetry,
            ModelCallRecorder recorder,
            Clock clock,
            String modelName) {
        this(chatClientFor(chatModel), promptResource, new SpringAiToolCallbackFactory(), new ConversationHistoryProjector(),
                new StrictJsonCodec(), telemetry, configuredGoogleOptions(chatModel, modelName), recorder, clock, modelName);
    }

    private static ChatClient chatClientFor(ChatModel chatModel) {
        Assert.notNull(chatModel, "Google chat model must not be null");
        Assert.isInstanceOf(ToolCallingChatOptions.class, chatModel.getOptions(),
                "Google chat model must support tool-calling options");
        return ChatClient.create(chatModel);
    }

    GoogleConversationModel(
            ChatClient chatClient,
            PromptResource promptResource,
            SpringAiToolCallbackFactory callbackFactory,
            ConversationHistoryProjector historyProjector,
            StrictJsonCodec jsonCodec) {
        this(chatClient, promptResource, callbackFactory, historyProjector, jsonCodec, new NoOpConversationTelemetry(), Optional.empty(),
                ModelCallRecorder.noop(), Clock.systemUTC(), "unspecified");
    }

    GoogleConversationModel(
            ChatClient chatClient,
            PromptResource promptResource,
            SpringAiToolCallbackFactory callbackFactory,
            ConversationHistoryProjector historyProjector,
            StrictJsonCodec jsonCodec,
            ConversationTelemetry telemetry) {
        this(chatClient, promptResource, callbackFactory, historyProjector, jsonCodec, telemetry, Optional.empty(), ModelCallRecorder.noop(),
                Clock.systemUTC(), "unspecified");
    }

    private GoogleConversationModel(
            ChatClient chatClient,
            PromptResource promptResource,
            SpringAiToolCallbackFactory callbackFactory,
            ConversationHistoryProjector historyProjector,
            StrictJsonCodec jsonCodec,
            ConversationTelemetry telemetry,
            Optional<GoogleGenAiChatOptions> googleOptions,
            ModelCallRecorder recorder,
            Clock clock,
            String modelName) {
        Assert.notNull(chatClient, "Chat client must not be null");
        Assert.notNull(promptResource, "Prompt resource must not be null");
        Assert.notNull(callbackFactory, "Tool callback factory must not be null");
        Assert.notNull(historyProjector, "Conversation history projector must not be null");
        Assert.notNull(jsonCodec, "JSON codec must not be null");
        Assert.notNull(telemetry, "Conversation telemetry must not be null");
        Assert.notNull(googleOptions, "Google options must not be null");
        Assert.notNull(recorder, "Model call recorder must not be null");
        Assert.notNull(clock, "Clock must not be null");
        Assert.hasText(modelName, "Model name must not be blank");
        this.chatClient = chatClient;
        this.promptResource = promptResource;
        this.callbackFactory = callbackFactory;
        this.historyProjector = historyProjector;
        this.jsonCodec = jsonCodec;
        this.telemetry = telemetry;
        this.googleOptions = googleOptions;
        this.recorder = recorder;
        this.clock = clock;
        this.modelName = modelName;
    }

    @Override
    public ModelDecision plan(ModelRequest request, Consumer<ModelUsage> usageObserver) {
        Assert.notNull(request, "Model request must not be null");
        Assert.notNull(usageObserver, "Model usage observer must not be null");
        List<ToolCallback> callbacks = callbackFactory.create(request.toolSnapshot());
        List<Message> messages = planMessagesFor(request);
        LOGGER.info("google_model_request phase=PLAN messageCount={} callbackCount={}", messages.size(), callbacks.size());
        SpringAiCallCapture callCapture = new SpringAiCallCapture(clock);
        try {
            ChatResponse response = call(messages, callbacks, callCapture);
            logResponseShape(response);
            ModelUsage modelUsage = usage(response);
            ModelDecision modelDecision = planDecision(response);
            LOGGER.info("google_model_response phase=PLAN resultCategory={} resultCount={} usageAvailable={}", resultCategory(response),
                    resultCount(response), modelUsage.available());
            usageObserver.accept(modelUsage);
            telemetry.model("SUCCESS", Optional.of(finishReason(response)), modelUsage);
            record(request.callContext(), ModelCallPhase.PLAN, planOutcome(modelDecision), callCapture, Optional.empty(), Optional.empty());
            return modelDecision;
        } catch (ModelCallFailure failure) {
            LOGGER.info("google_model_failed phase=PLAN closedFailureKind={}", failure.kind());
            telemetry.model("FAILURE", Optional.of(failure.kind().name()), new ModelUsage(0, 0, 0, false));
            if (callCapture.providerFailure().isPresent()) {
                record(request.callContext(), ModelCallPhase.PLAN, ModelCallOutcome.PROVIDER_FAILURE, callCapture,
                        Optional.empty(), callCapture.providerFailure().map(RuntimeException::toString));
            } else {
                record(request.callContext(), ModelCallPhase.PLAN, ModelCallOutcome.INVALID_RESPONSE, callCapture,
                        Optional.of(failure.toString()), Optional.empty());
            }
            throw failure;
        }
    }

    @Override
    public AssistantReply reply(ReplyRequest request, Consumer<ModelUsage> usageObserver) {
        Assert.notNull(request, "Reply request must not be null");
        Assert.notNull(usageObserver, "Model usage observer must not be null");
        List<Message> messages = replyMessagesFor(request);
        LOGGER.info("google_model_request phase=FINAL_REPLY messageCount={} callbackCount={}", messages.size(), 0);
        SpringAiCallCapture callCapture = new SpringAiCallCapture(clock);
        ChatResponse response;
        AssistantReply reply;
        try {
            ResponseEntity<ChatResponse, AssistantReply> responseEntity = replyCall(messages, callCapture);
            response = Objects.requireNonNull(responseEntity.response(), "Spring AI final reply response must not be null");
            validateFinalReplyShape(response);
            reply = Objects.requireNonNull(responseEntity.entity(), "Spring AI final reply entity must not be null");
        } catch (RuntimeException exception) {
            ModelCallFailure failure = callCapture.providerFailure()
                    .map(GoogleConversationModel::classifyProviderFailure)
                    .orElseGet(ModelCallFailure::correctable);
            LOGGER.info("google_model_failed phase=FINAL_REPLY closedFailureKind={}", failure.kind());
            telemetry.model("FAILURE", Optional.of(failure.kind().name()), new ModelUsage(0, 0, 0, false));
            if (callCapture.providerFailure().isPresent()) {
                record(request.callContext(), ModelCallPhase.FINAL_REPLY, ModelCallOutcome.PROVIDER_FAILURE, callCapture,
                        Optional.empty(), callCapture.providerFailure().map(RuntimeException::toString));
            } else {
                record(request.callContext(), ModelCallPhase.FINAL_REPLY, ModelCallOutcome.INVALID_RESPONSE, callCapture,
                        Optional.of(exception.toString()), Optional.empty());
            }
            throw failure;
        }
        logResponseShape(response);
        ModelUsage modelUsage = usage(response);
        LOGGER.info("google_model_response phase=FINAL_REPLY resultCategory={} resultCount={} usageAvailable={}", resultCategory(response),
                resultCount(response), modelUsage.available());
        usageObserver.accept(modelUsage);
        telemetry.model("SUCCESS", Optional.of(finishReason(response)), modelUsage);
        record(request.callContext(), ModelCallPhase.FINAL_REPLY, ModelCallOutcome.FINAL_REPLY, callCapture,
                Optional.empty(), Optional.empty());
        return reply;
    }

    private List<Message> planMessagesFor(ModelRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new org.springframework.ai.chat.messages.SystemMessage(promptResource.content()));
        messages.addAll(historyProjector.project(request.history()));
        return List.copyOf(messages);
    }

    private List<Message> replyMessagesFor(ReplyRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new org.springframework.ai.chat.messages.SystemMessage(promptResource.content()));
        messages.addAll(historyProjector.project(request.history()));
        List<String> resultIds = request.history().stream()
                .filter(ToolMessage.class::isInstance)
                .map(ToolMessage.class::cast)
                .filter(ToolMessage::citeable)
                .map(message -> message.resultId().value())
                .distinct()
                .toList();
        messages.add(new org.springframework.ai.chat.messages.UserMessage(
                "Runtime final reply requirement: no tools are available. Return only the final JSON object described "
                        + "by the system instruction. Every citation value must be chosen from this exact list: "
                        + jsonCodec.canonicalize(resultIds)
                        + ". Re-read the tool results in history before choosing citations. If the answer reports codebase "
                        + "absence, cite a supporting complete empty code search; a source citation does not replace it."));
        return List.copyOf(messages);
    }

    private ChatResponse call(List<Message> messages, List<ToolCallback> callbacks, SpringAiCallCapture callCapture) {
        try {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                    .advisors(callCapture)
                    .messages(messages);
            if (googleOptions.isPresent()) {
                GoogleGenAiChatOptions.Builder options = googleOptions.get().mutate()
                        .includeThoughts(true)
                        .toolCallbacks(callbacks);
                return request.options(options)
                        .call().chatResponse();
            }
            return request.toolCallbacks(callbacks).call().chatResponse();
        } catch (RuntimeException exception) {
            throw classifyProviderFailure(exception);
        }
    }

    private ResponseEntity<ChatResponse, AssistantReply> replyCall(List<Message> messages, SpringAiCallCapture callCapture) {
        StructuredOutputValidationAdvisor replyValidator = StructuredOutputValidationAdvisor.builder()
                .outputType(AssistantReply.class)
                .maxRepeatAttempts(0)
                .build();
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .advisors(replyValidator, callCapture)
                .messages(messages)
                .toolCallbacks(List.of());
        if (googleOptions.isPresent()) {
            GoogleGenAiChatOptions.Builder options = googleOptions.get().mutate()
                    .includeThoughts(false)
                    .toolCallbacks(List.of());
            request = request.options(options);
        }
        return request.call().responseEntity(AssistantReply.class, spec -> spec.useProviderStructuredOutput());
    }

    private static GoogleGenAiChatOptions configureGoogleOptions(ChatModel chatModel, String modelName) {
        Assert.isInstanceOf(GoogleGenAiChatOptions.class, chatModel.getOptions(),
                "Google chat model must expose Google GenAI options");
        GoogleGenAiChatOptions configuredOptions = (GoogleGenAiChatOptions) chatModel.getOptions();
        return configuredOptions.mutate()
                .model(modelName)
                .includeThoughts(true)
                .includeServerSideToolInvocations(false)
                .toolCallbacks(List.of())
                .build();
    }

    private static Optional<GoogleGenAiChatOptions> configuredGoogleOptions(ChatModel chatModel, String modelName) {
        if (chatModel.getOptions() instanceof GoogleGenAiChatOptions) {
            return Optional.of(configureGoogleOptions(chatModel, modelName));
        }
        return Optional.empty();
    }

    private static String inferredModelName(ChatModel chatModel) {
        String configuredModel = chatModel.getOptions().getModel();
        return StringUtils.hasText(configuredModel) ? configuredModel : "unspecified";
    }

    private static ModelCallOutcome planOutcome(ModelDecision modelDecision) {
        return modelDecision instanceof ModelDecision.UseTool
                ? ModelCallOutcome.TOOL_CALL
                : ModelCallOutcome.ANSWER_READY;
    }

    private void record(
            ModelCallContext callContext,
            ModelCallPhase phase,
            ModelCallOutcome outcome,
            SpringAiCallCapture callCapture,
            Optional<String> decodeError,
            Optional<String> providerError) {
        Optional<ChatResponse> capturedResponse = callCapture.chatResponse();
        Instant startedAt = callCapture.startedAt().orElseGet(clock::instant);
        Instant completedAt = callCapture.completedAt().orElse(startedAt);
        ModelCallRecord record = new ModelCallRecord(
                UUID.randomUUID(),
                callContext.sessionId(),
                callContext.messageJobId(),
                callContext.ordinal(),
                1,
                phase,
                outcome,
                modelName,
                callCapture.request().map(Object::toString).filter(StringUtils::hasText).orElse("unavailable"),
                rawCompletion(capturedResponse),
                rawToolCalls(capturedResponse),
                capturedResponse.map(GoogleConversationModel::finishReason),
                decodeError,
                providerError,
                capturedResponse.map(GoogleConversationModel::usage).orElseGet(() -> new ModelUsage(0, 0, 0, false)),
                startedAt,
                completedAt);
        try {
            recorder.record(record);
        } catch (RuntimeException exception) {
            LOGGER.warn("google_model_diagnostic_record_failed id={} sessionId={} messageJobId={} ordinal={}",
                    record.id(), record.sessionId().value(), record.messageJobId().value(), record.runtimeCallOrdinal());
        }
    }

    private static Optional<String> rawCompletion(Optional<ChatResponse> response) {
        return response.flatMap(GoogleConversationModel::actionableMessage)
                .map(AssistantMessage::getText)
                .filter(StringUtils::hasText);
    }

    private Optional<String> rawToolCalls(Optional<ChatResponse> response) {
        List<Map<String, String>> toolCalls = response.stream()
                .flatMap(chatResponse -> chatResponse.getResults().stream())
                .filter(result -> !isThought(result))
                .flatMap(result -> Optional.ofNullable(result.getOutput()).stream())
                .flatMap(message -> message.getToolCalls().stream())
                .map(toolCall -> normalizedToolCall(toolCall))
                .toList();
        return toolCalls.isEmpty() ? Optional.empty() : Optional.of(jsonCodec.canonicalize(toolCalls));
    }

    private static Map<String, String> normalizedToolCall(AssistantMessage.ToolCall toolCall) {
        Map<String, String> normalized = new LinkedHashMap<>();
        normalized.put("id", toolCall.id());
        normalized.put("name", toolCall.name());
        normalized.put("arguments", toolCall.arguments());
        return normalized;
    }

    private static Optional<AssistantMessage> actionableMessage(ChatResponse response) {
        return response.getResults().stream()
                .filter(result -> !isThought(result))
                .flatMap(result -> Optional.ofNullable(result.getOutput()).stream())
                .findFirst();
    }

    private ModelDecision planDecision(ChatResponse response) {
        List<Generation> results = response.getResults().stream()
                .filter(result -> !isThought(result))
                .toList();
        if (CollectionUtils.isEmpty(results) || results.size() != 1) {
            throw ModelCallFailure.correctable();
        }
        AssistantMessage message = Optional.ofNullable(results.getFirst().getOutput())
                .orElseThrow(ModelCallFailure::correctable);
        if (message.hasToolCalls()) {
            return toolDecision(message);
        }
        if (!StringUtils.hasText(message.getText())) {
            throw ModelCallFailure.correctable();
        }
        return new ModelDecision.AnswerReady();
    }

    private static void validateFinalReplyShape(ChatResponse response) {
        List<Generation> actionableResults = response.getResults().stream()
                .filter(result -> !isThought(result))
                .toList();
        if (actionableResults.size() != 1) {
            throw ModelCallFailure.correctable();
        }
        AssistantMessage message = Optional.ofNullable(actionableResults.getFirst().getOutput())
                .orElseThrow(ModelCallFailure::correctable);
        if (message.hasToolCalls() || !StringUtils.hasText(message.getText())) {
            throw ModelCallFailure.correctable();
        }
    }

    private static boolean isThought(Generation generation) {
        return Optional.ofNullable(generation.getOutput())
                .map(AssistantMessage::getMetadata)
                .map(metadata -> metadata.get(IS_THOUGHT))
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(false);
    }

    private ModelDecision toolDecision(AssistantMessage message) {
        if (message.getToolCalls().size() != 1 || StringUtils.hasText(message.getText())) {
            throw ModelCallFailure.correctable();
        }
        AssistantMessage.ToolCall toolCall = message.getToolCalls().getFirst();
        try {
            ToolName toolName = new ToolName(toolCall.name());
            String callId = StringUtils.hasText(toolCall.id()) ? toolCall.id() : "runtime-" + UUID.randomUUID();
            return new ModelDecision.UseTool(callId, toolName, toolCall.arguments(), modelContext(message));
        } catch (IllegalArgumentException exception) {
            throw ModelCallFailure.correctable();
        }
    }

    private static String modelContext(AssistantMessage message) {
        Object value = message.getMetadata().get(THOUGHT_SIGNATURES);
        if (!(value instanceof List<?> signatures)
                || signatures.isEmpty()
                || !(signatures.getFirst() instanceof byte[] signature)
                || signature.length == 0) {
            throw ModelCallFailure.correctable();
        }
        return Base64.getEncoder().encodeToString(signature);
    }

    private static ModelUsage usage(ChatResponse response) {
        Optional<Usage> usage = Optional.ofNullable(response.getMetadata())
                .map(ChatResponseMetadata::getUsage)
                .filter(value -> !(value instanceof EmptyUsage));
        if (usage.isEmpty()) {
            return new ModelUsage(0, 0, 0, false);
        }
        Optional<Integer> promptTokens = Optional.ofNullable(usage.get().getPromptTokens());
        Optional<Integer> completionTokens = Optional.ofNullable(usage.get().getCompletionTokens());
        Optional<Integer> totalTokens = Optional.ofNullable(usage.get().getTotalTokens());
        if (promptTokens.isEmpty() || completionTokens.isEmpty() || totalTokens.isEmpty()) {
            return new ModelUsage(0, 0, 0, false);
        }
        return new ModelUsage(promptTokens.get(), completionTokens.get(), totalTokens.get(), true);
    }

    private static String resultCategory(ChatResponse response) {
        List<Generation> results = response.getResults();
        if (CollectionUtils.isEmpty(results)) {
            return "EMPTY";
        }
        return results.size() == 1 ? "SINGLE" : "MULTIPLE";
    }

    private static String finishReason(ChatResponse response) {
        List<Generation> results = response.getResults();
        if (CollectionUtils.isEmpty(results) || results.size() != 1) {
            return "UNAVAILABLE";
        }
        ChatGenerationMetadata metadata = results.getFirst().getMetadata();
        if (!StringUtils.hasText(metadata.getFinishReason())) {
            return "UNAVAILABLE";
        }
        return switch (metadata.getFinishReason().toUpperCase(Locale.ROOT)) {
            case "STOP", "MAX_TOKENS", "SAFETY", "RECITATION" -> metadata.getFinishReason().toUpperCase(Locale.ROOT);
            default -> "OTHER";
        };
    }

    private static int resultCount(ChatResponse response) {
        List<Generation> results = response.getResults();
        return CollectionUtils.isEmpty(results) ? 0 : results.size();
    }

    private static void logResponseShape(ChatResponse response) {
        List<Generation> results = response.getResults();
        Optional<AssistantMessage> output = CollectionUtils.isEmpty(results)
                ? Optional.empty()
                : Optional.ofNullable(results.getFirst().getOutput());
        int toolCallCount = output.map(message -> message.getToolCalls().size()).orElse(0);
        boolean textPresent = output.map(AssistantMessage::getText).filter(StringUtils::hasText).isPresent();
        LOGGER.info("google_model_response_shape resultCount={} outputPresent={} toolCallCount={} textPresent={}",
                resultCount(response), output.isPresent(), toolCallCount, textPresent);
    }

    private static ModelCallFailure classifyProviderFailure(RuntimeException exception) {
        if (hasContextTooLargeSignal(exception)) {
            return ModelCallFailure.contextTooLarge();
        }
        if (hasTransientSignal(exception)) {
            return ModelCallFailure.transientFailure();
        }
        return ModelCallFailure.terminal();
    }

    private static boolean hasContextTooLargeSignal(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) { // cs-allow traversal ends at null
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("context")
                    && (message.contains("window") || message.contains("too large") || message.contains("exceeded"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTransientSignal(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) { // cs-allow traversal ends at null
            if (current instanceof TransientAiException || current instanceof SocketTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("429") || message.contains("503") || message.contains("timeout")) {
                return true;
            }
        }
        return false;
    }
}
