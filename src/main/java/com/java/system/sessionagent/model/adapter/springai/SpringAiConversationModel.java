package com.java.system.sessionagent.model.adapter.springai;

import java.util.function.Consumer;
import com.java.system.sessionagent.conversation.domain.ModelCallObservation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.java.system.sessionagent.conversation.domain.IntentRequest;
import com.java.system.sessionagent.conversation.domain.IntentResult;
import com.java.system.sessionagent.conversation.domain.FollowUpIntent;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.ContextCompactionRequest;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.Map;
import java.util.function.Predicate;

public final class SpringAiConversationModel implements ConversationModel {

    private final ChatModel chatModel;
    private final SystemPrompt promptResource;
    private final SpringAiToolCallbackFactory callbackFactory;
    private final HistoryProjector historyProjector;
    private final ConversationTelemetry telemetry;
    private final ObjectMapper objectMapper;
    private final SpringAiContinuationHandler continuationHandler;
    private final ModelDescriptor descriptor;
    private final Predicate<Throwable> providerTransientFailure;

    public SpringAiConversationModel(
            ChatModel chatModel,
            SystemPrompt promptResource,
            ConversationTelemetry telemetry,
            ObjectMapper objectMapper,
            SpringAiContinuationHandler continuationHandler) {
        this(chatModel, promptResource, telemetry, objectMapper, continuationHandler,
                testDescriptor(continuationHandler), ignored -> false);
    }

    public SpringAiConversationModel(
            ChatModel chatModel,
            SystemPrompt promptResource,
            ConversationTelemetry telemetry,
            ObjectMapper objectMapper,
            SpringAiContinuationHandler continuationHandler,
            Predicate<Throwable> providerTransientFailure) {
        this(chatModel, promptResource, telemetry, objectMapper, continuationHandler,
                testDescriptor(continuationHandler), providerTransientFailure);
    }

    public SpringAiConversationModel(
            ChatModel chatModel,
            SystemPrompt promptResource,
            ConversationTelemetry telemetry,
            ObjectMapper objectMapper,
            SpringAiContinuationHandler continuationHandler,
            ModelDescriptor descriptor) {
        this(chatModel, promptResource, new SpringAiToolCallbackFactory(objectMapper), new HistoryProjector(objectMapper), telemetry,
                objectMapper, continuationHandler, descriptor, ignored -> false);
    }

    public SpringAiConversationModel(
            ChatModel chatModel,
            SystemPrompt promptResource,
            ConversationTelemetry telemetry,
            ObjectMapper objectMapper,
            SpringAiContinuationHandler continuationHandler,
            ModelDescriptor descriptor,
            Predicate<Throwable> providerTransientFailure) {
        this(chatModel, promptResource, new SpringAiToolCallbackFactory(objectMapper), new HistoryProjector(objectMapper), telemetry,
                objectMapper, continuationHandler, descriptor, providerTransientFailure);
    }

    SpringAiConversationModel(
            ChatModel chatModel,
            SystemPrompt promptResource,
            SpringAiToolCallbackFactory callbackFactory,
            HistoryProjector historyProjector,
            ConversationTelemetry telemetry,
            ObjectMapper objectMapper,
            SpringAiContinuationHandler continuationHandler) {
        this(chatModel, promptResource, callbackFactory, historyProjector, telemetry, objectMapper, continuationHandler,
                testDescriptor(continuationHandler));
    }

    SpringAiConversationModel(
            ChatModel chatModel,
            SystemPrompt promptResource,
            SpringAiToolCallbackFactory callbackFactory,
            HistoryProjector historyProjector,
            ConversationTelemetry telemetry,
            ObjectMapper objectMapper,
            SpringAiContinuationHandler continuationHandler,
            ModelDescriptor descriptor) {
        this(chatModel, promptResource, callbackFactory, historyProjector, telemetry, objectMapper, continuationHandler, descriptor,
                ignored -> false);
    }

    SpringAiConversationModel(
            ChatModel chatModel,
            SystemPrompt promptResource,
            SpringAiToolCallbackFactory callbackFactory,
            HistoryProjector historyProjector,
            ConversationTelemetry telemetry,
            ObjectMapper objectMapper,
            SpringAiContinuationHandler continuationHandler,
            ModelDescriptor descriptor,
            Predicate<Throwable> providerTransientFailure) {
        Assert.notNull(chatModel, "Chat model must not be null");
        Assert.notNull(promptResource, "Prompt resource must not be null");
        Assert.notNull(callbackFactory, "Tool callback factory must not be null");
        Assert.notNull(historyProjector, "Conversation history projector must not be null");
        Assert.notNull(telemetry, "Conversation telemetry must not be null");
        Assert.notNull(objectMapper, "Object mapper must not be null");
        Assert.notNull(continuationHandler, "Spring AI continuation handler must not be null");
        Assert.notNull(descriptor, "Model descriptor must not be null");
        Assert.notNull(providerTransientFailure, "Provider transient failure classifier must not be null");
        Assert.isTrue(descriptor.routeId().equals(continuationHandler.routeId()), "Model descriptor route must match continuation route");
        this.chatModel = chatModel;
        this.promptResource = promptResource;
        this.callbackFactory = callbackFactory;
        this.historyProjector = historyProjector;
        this.telemetry = telemetry;
        this.objectMapper = objectMapper;
        this.continuationHandler = continuationHandler;
        this.descriptor = descriptor;
        this.providerTransientFailure = providerTransientFailure;
    }

    @Override
    public ModelRouteId routeId() {
        return continuationHandler.routeId();
    }

    @Override
    public ModelDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public String systemPrompt() {
        return promptResource.content();
    }

    @Override
    public ModelCallResult respond(
            ModelRequest request,
            ModelCallReservation reservation, Consumer<ModelCallObservation> usageObserver) {
        Assert.notNull(request, "Model request must not be null");
        Assert.notNull(reservation, "Model call reservation must not be null");
        Assert.notNull(usageObserver, "Model call observation observer must not be null");

        Prompt prompt;
        try {
            prompt = promptFor(request);
        } catch (InvalidConversationHistoryException exception) {
            throw ModelCallFailure.invalidHistory();
        }
        int totalOrdinal = reservation.reserve();
        ChatResponse response;
        long requestStartedAt = System.nanoTime();
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException exception) {
            ModelCallFailure failure = classifyProviderFailure(exception);
            recordFailure("UNAVAILABLE", requestStartedAt);
            throw failure;
        }
        usageObserver.accept(new ModelCallObservation(totalOrdinal, CacheTokenUsageExtractor.extractResponse(response)));
        ModelCallResult result;
        try {
            result = normalize(response, objectMapper, continuationHandler);
        } catch (ModelCallFailure failure) {
            recordFailure("OUTPUT_INVALID", requestStartedAt);
            throw failure;
        }
        ModelUsage modelUsage = usage(response);
        telemetry.model("SUCCESS", Optional.of("RESPONSE"), modelUsage, elapsedSince(requestStartedAt));
        return new ModelCallResult(result.reply(), result.continuation(), modelUsage);
    }

    @Override
    public String summarize(ContextCompactionRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observationObserver) {
        Assert.notNull(request, "Context compaction request must not be null");
        Assert.notNull(reservation, "Model call reservation must not be null");
        Assert.notNull(observationObserver, "Model call observation observer must not be null");
        List<Message> messages = new ArrayList<>();
        messages.add(new org.springframework.ai.chat.messages.SystemMessage(compactionPrompt()));
        try {
            messages.addAll(historyProjector.project(request.history(), Map.of(), null, request.previousSummary()));
        } catch (InvalidConversationHistoryException exception) {
            throw ModelCallFailure.invalidHistory();
        }
        ChatOptions chatOptions = chatModel.getOptions();
        Assert.isInstanceOf(ToolCallingChatOptions.class, chatOptions,
                "Chat model options must support tool calling");
        ToolCallingChatOptions noToolOptions = ((ToolCallingChatOptions) chatOptions)
                .mutate()
                .toolCallbacks(List.of())
                .build();
        Prompt prompt = new Prompt(List.copyOf(messages), noToolOptions);
        int totalOrdinal = reservation.reserve();
        long requestStartedAt = System.nanoTime();
        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException exception) {
            ModelCallFailure failure = classifyProviderFailure(exception);
            recordFailure("UNAVAILABLE", requestStartedAt);
            throw failure;
        }
        observationObserver.accept(new ModelCallObservation(totalOrdinal, CacheTokenUsageExtractor.extractResponse(response)));
        AssistantMessage message = firstActionableMessage(response).orElseThrow(ModelCallFailure::correctable);
        if (message.hasToolCalls() || !StringUtils.hasText(message.getText())) {
            recordFailure("OUTPUT_INVALID", requestStartedAt);
            throw ModelCallFailure.correctable();
        }
        telemetry.model("SUCCESS", Optional.of("COMPACTION"), usage(response), elapsedSince(requestStartedAt));
        return message.getText();
    }

    @Override
    public IntentResult classifyIntent(IntentRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observationObserver) {
        Assert.notNull(observationObserver, "Model call observation observer must not be null");
        List<Message> messages = new ArrayList<>();
        messages.add(new org.springframework.ai.chat.messages.SystemMessage(request.directive()));
        try {
            messages.addAll(historyProjector.project(request.history(), Map.of(), null, request.summary()));
        } catch (InvalidConversationHistoryException exception) {
            throw ModelCallFailure.invalidHistory();
        }
        messages.add(new org.springframework.ai.chat.messages.UserMessage(
                "Current user message (sequence " + request.initiatingMessage().sequence().value() + "):\n"
                        + request.initiatingMessage().message()));
        Prompt prompt = new Prompt(List.copyOf(messages), options(List.of()));
        int totalOrdinal = reservation.reserve();
        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException exception) {
            throw classifyProviderFailure(exception);
        }
        observationObserver.accept(new ModelCallObservation(totalOrdinal, CacheTokenUsageExtractor.extractResponse(response)));
        AssistantMessage message = firstActionableMessage(response).orElseThrow(ModelCallFailure::correctable);
        if (message.hasToolCalls() || !StringUtils.hasText(message.getText())) {
            throw ModelCallFailure.correctable();
        }
        try {
            JsonNode value = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(message.getText());
            if (!value.isObject() || value.size() < 1 || value.size() > 2
                    || !value.path("intent").isTextual()) {
                throw ModelCallFailure.correctable();
            }
            java.util.Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!"intent".equals(name) && !"clarification".equals(name)) {
                    throw ModelCallFailure.correctable();
                }
            }
            FollowUpIntent intent = FollowUpIntent.valueOf(value.path("intent").textValue());
            Optional<String> clarification = Optional.empty();
            if (value.has("clarification")) {
                if (!value.path("clarification").isTextual()) {
                    throw ModelCallFailure.correctable();
                }
                clarification = Optional.of(value.path("clarification").textValue());
            }
            return new IntentResult(intent, clarification);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw ModelCallFailure.correctable();
        }
    }

    private ToolCallingChatOptions options(List<ToolCallback> callbacks) {
        ChatOptions chatOptions = chatModel.getOptions();
        Assert.isInstanceOf(ToolCallingChatOptions.class, chatOptions,
                "Chat model options must support tool calling");
        return ((ToolCallingChatOptions) chatOptions).mutate().toolCallbacks(callbacks).build();
    }

    private static ModelDescriptor testDescriptor(SpringAiContinuationHandler continuationHandler) {
        return new ModelDescriptor(continuationHandler.routeId(), "unspecified", 1);
    }

    private Prompt promptFor(ModelRequest request) {
        List<ToolCallback> callbacks = callbackFactory.create(request.toolSnapshot());
        List<Message> messages = new ArrayList<>();
        String directive = request.transientDirective();
        String systemPrompt = StringUtils.hasText(directive)
                ? promptResource.content() + "\n\n" + directive : promptResource.content();
        messages.add(new org.springframework.ai.chat.messages.SystemMessage(systemPrompt));
        messages.addAll(historyProjector.project(request.history(), request.continuations(), continuationHandler, request.contextSummary()));
        if (request.purpose() == ModelRequest.Purpose.WRAP_UP) {
            Assert.isTrue(callbacks.isEmpty(), "Wrap-up must not expose tools");
        }
        return new Prompt(List.copyOf(messages), options(callbacks));
    }

    private static ModelCallResult normalize(
            ChatResponse response,
            ObjectMapper objectMapper,
            SpringAiContinuationHandler continuationHandler) {
        AssistantMessage message = firstActionableMessage(response).orElseThrow(ModelCallFailure::correctable);
        if (message.hasToolCalls()) {
            return new ModelCallResult(new ModelReply.UseTools(text(message), toolRequests(message, objectMapper)),
                    continuationHandler.capture(message));
        }
        return new ModelCallResult(new ModelReply.Text(text(message).orElseThrow(ModelCallFailure::correctable)), Optional.empty());
    }

    private static Optional<AssistantMessage> firstActionableMessage(ChatResponse response) {
        return Optional.ofNullable(response)
                .map(value -> value.getResults())
                .stream()
                .flatMap(results -> results.stream())
                .map(generation -> generation.getOutput())
                .filter(Objects::nonNull)
                .filter(SpringAiConversationModel::isActionable)
                .findFirst();
    }

    private static boolean isActionable(AssistantMessage message) {
        return message.hasToolCalls() || StringUtils.hasText(message.getText());
    }

    private static Optional<String> text(AssistantMessage message) {
        return Optional.ofNullable(message.getText()).filter(StringUtils::hasText);
    }

    private static List<ToolRequest> toolRequests(AssistantMessage message, ObjectMapper objectMapper) {
        try {
            List<ToolRequest> requests = new ArrayList<>();
            HashSet<String> providerIds = new HashSet<>();
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                String providerId = toolCall.id();
                if (StringUtils.hasText(providerId) && !providerIds.add(providerId)) {
                    throw ModelCallFailure.correctable();
                }
                String toolCallId = StringUtils.hasText(providerId) ? providerId : UUID.randomUUID().toString();
                Map<String, Object> arguments = objectMapper.readValue(toolCall.arguments(), new TypeReference<LinkedHashMap<String, Object>>() { });
                Assert.notNull(arguments, "Tool call arguments must be a JSON object");
                requests.add(new ToolRequest(new com.java.system.sessionagent.conversation.domain.ToolCallId(toolCallId),
                        new ToolName(toolCall.name()), arguments));
            }
            return List.copyOf(requests);
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw ModelCallFailure.correctable();
        }
    }

    private static ModelUsage usage(ChatResponse response) {
        Optional<Usage> responseUsage = Optional.ofNullable(response.getMetadata())
                .map(metadata -> metadata.getUsage())
                .filter(value -> !(value instanceof EmptyUsage));
        if (responseUsage.isEmpty()) {
            return new ModelUsage(0, 0, 0, false);
        }
        OptionalLong cachedInputTokens = cachedInputTokens(responseUsage.get());
        Optional<Integer> promptTokens = Optional.ofNullable(responseUsage.get().getPromptTokens());
        Optional<Integer> completionTokens = Optional.ofNullable(responseUsage.get().getCompletionTokens());
        Optional<Integer> totalTokens = Optional.ofNullable(responseUsage.get().getTotalTokens());
        if (promptTokens.isEmpty() || completionTokens.isEmpty() || totalTokens.isEmpty()) {
            return new ModelUsage(0, 0, 0, false, cachedInputTokens);
        }
        int prompt = promptTokens.get();
        int completion = completionTokens.get();
        int total = totalTokens.get();
        if (prompt < 0 || completion < 0 || total < 0) {
            return new ModelUsage(0, 0, 0, false, cachedInputTokens);
        }
        return new ModelUsage(prompt, completion, total, true, cachedInputTokens);
    }

    private static OptionalLong cachedInputTokens(Usage usage) {
        Optional<Long> cachedInputTokens = Optional.ofNullable(usage.getCacheReadInputTokens())
                .filter(value -> value >= 0);
        return cachedInputTokens.isPresent()
                ? OptionalLong.of(cachedInputTokens.get())
                : OptionalLong.empty();
    }

    private ModelCallFailure classifyProviderFailure(RuntimeException exception) {
        if (hasContextTooLargeSignal(exception)) {
            return ModelCallFailure.contextTooLarge();
        }
        if (hasTransientSignal(exception)) {
            return ModelCallFailure.transientFailure();
        }
        return ModelCallFailure.terminal();
    }

    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private void recordFailure(String category, long requestStartedAt) {
        telemetry.model("FAILURE", Optional.of(category), new ModelUsage(0, 0, 0, false),
                elapsedSince(requestStartedAt));
    }

    private static boolean hasContextTooLargeSignal(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) { // cs-allow traversal ends at null
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (hasProviderInputTokenLimitSignal(message)
                    || (message.contains("context")
                    && (message.contains("window") || message.contains("too large") || message.contains("exceeded")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProviderInputTokenLimitSignal(String message) {
        return (message.contains("input token count") && message.contains("maximum number of tokens allowed"))
                || (message.contains("maximum context length") && message.contains("requested"));
    }

    private boolean hasTransientSignal(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) { // cs-allow traversal ends at null
            if (current instanceof TransientAiException || current instanceof SocketTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof OpenAIRetryableException
                    || current instanceof OpenAIIoException) {
                return true;
            }
            if (current instanceof OpenAIServiceException providerFailure
                    && isTransientHttpStatus(providerFailure.statusCode())) {
                return true;
            }
            if (providerTransientFailure.test(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransientHttpStatus(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }
}
