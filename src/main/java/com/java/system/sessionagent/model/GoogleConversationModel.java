package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.AssistantReply;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.json.JsonContractException;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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

    public GoogleConversationModel(ChatModel chatModel, PromptResource promptResource) {
        this(chatClientFor(chatModel), promptResource, new SpringAiToolCallbackFactory(),
                new ConversationHistoryProjector(), new StrictJsonCodec(), new NoOpConversationTelemetry(), Optional.empty());
    }

    public GoogleConversationModel(ChatModel chatModel, PromptResource promptResource, ConversationTelemetry telemetry) {
        this(chatClientFor(chatModel), promptResource, new SpringAiToolCallbackFactory(),
                new ConversationHistoryProjector(), new StrictJsonCodec(), telemetry, Optional.empty());
    }

    public GoogleConversationModel(ChatModel chatModel, PromptResource promptResource, ConversationTelemetry telemetry,
                                   String modelName) {
        this(chatClientFor(chatModel), promptResource, new SpringAiToolCallbackFactory(),
                new ConversationHistoryProjector(), new StrictJsonCodec(), telemetry, Optional.of(configureGoogleOptions(chatModel, modelName)));
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
        this(chatClient, promptResource, callbackFactory, historyProjector, jsonCodec, new NoOpConversationTelemetry(), Optional.empty());
    }

    GoogleConversationModel(
            ChatClient chatClient,
            PromptResource promptResource,
            SpringAiToolCallbackFactory callbackFactory,
            ConversationHistoryProjector historyProjector,
            StrictJsonCodec jsonCodec,
            ConversationTelemetry telemetry) {
        this(chatClient, promptResource, callbackFactory, historyProjector, jsonCodec, telemetry, Optional.empty());
    }

    private GoogleConversationModel(
            ChatClient chatClient,
            PromptResource promptResource,
            SpringAiToolCallbackFactory callbackFactory,
            ConversationHistoryProjector historyProjector,
            StrictJsonCodec jsonCodec,
            ConversationTelemetry telemetry,
            Optional<GoogleGenAiChatOptions> googleOptions) {
        Assert.notNull(chatClient, "Chat client must not be null");
        Assert.notNull(promptResource, "Prompt resource must not be null");
        Assert.notNull(callbackFactory, "Tool callback factory must not be null");
        Assert.notNull(historyProjector, "Conversation history projector must not be null");
        Assert.notNull(jsonCodec, "JSON codec must not be null");
        Assert.notNull(telemetry, "Conversation telemetry must not be null");
        this.chatClient = chatClient;
        this.promptResource = promptResource;
        this.callbackFactory = callbackFactory;
        this.historyProjector = historyProjector;
        this.jsonCodec = jsonCodec;
        this.telemetry = telemetry;
        this.googleOptions = googleOptions;
    }

    @Override
    public ModelDecision decide(ModelRequest request, Consumer<ModelUsage> usageObserver) {
        Assert.notNull(request, "Model request must not be null");
        Assert.notNull(usageObserver, "Model usage observer must not be null");
        List<ToolCallback> callbacks = request.replyOnly() ? List.of() : callbackFactory.create(request.toolSnapshot());
        List<Message> messages = messagesFor(request);
        LOGGER.info("google_model_request replyOnly={} messageCount={} callbackCount={}", request.replyOnly(), messages.size(), callbacks.size());
        try {
            ChatResponse response = call(messages, callbacks);
            logResponseShape(response);
            ModelUsage modelUsage = usage(response);
            ModelDecision modelDecision = decision(response);
            LOGGER.info("google_model_response replyOnly={} resultCategory={} resultCount={} usageAvailable={}", request.replyOnly(), resultCategory(response),
                    resultCount(response), modelUsage.available());
            usageObserver.accept(modelUsage);
            telemetry.model("SUCCESS", Optional.of(finishReason(response)), modelUsage);
            return modelDecision;
        } catch (ModelCallFailure failure) {
            LOGGER.info("google_model_failed replyOnly={} closedFailureKind={}", request.replyOnly(), failure.kind());
            telemetry.model("FAILURE", Optional.of(failure.kind().name()), new ModelUsage(0, 0, 0, false));
            throw failure;
        }
    }

    private List<Message> messagesFor(ModelRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new org.springframework.ai.chat.messages.SystemMessage(promptResource.content()));
        messages.addAll(historyProjector.project(request.history()));
        if (request.replyOnly()) {
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
                            + jsonCodec.canonicalize(resultIds)));
        }
        return List.copyOf(messages);
    }

    private ChatResponse call(List<Message> messages, List<ToolCallback> callbacks) {
        try {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                    .messages(messages);
            if (googleOptions.isPresent()) {
                return request.options(googleOptions.get().mutate().toolCallbacks(callbacks))
                        .call().chatResponse();
            }
            return request.toolCallbacks(callbacks).call().chatResponse();
        } catch (RuntimeException exception) {
            throw classifyProviderFailure(exception);
        }
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

    private ModelDecision decision(ChatResponse response) {
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
        return replyDecision(message.getText());
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

    private ModelDecision replyDecision(String content) {
        try {
            return new ModelDecision.Reply(jsonCodec.decode(content, AssistantReply.class));
        } catch (JsonContractException | IllegalArgumentException exception) {
            throw ModelCallFailure.correctable();
        }
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
