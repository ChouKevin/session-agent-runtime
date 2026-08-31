package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class SpringAiConversationModel implements ConversationModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiConversationModel.class);

    private final ChatModel chatModel;
    private final PromptResource promptResource;
    private final SpringAiToolCallbackFactory callbackFactory;
    private final ConversationHistoryProjector historyProjector;
    private final ConversationTelemetry telemetry;

    public SpringAiConversationModel(ChatModel chatModel, PromptResource promptResource) {
        this(chatModel, promptResource, new SpringAiToolCallbackFactory(), new ConversationHistoryProjector(),
                new NoOpConversationTelemetry());
    }

    public SpringAiConversationModel(
            ChatModel chatModel,
            PromptResource promptResource,
            ConversationTelemetry telemetry) {
        this(chatModel, promptResource, new SpringAiToolCallbackFactory(), new ConversationHistoryProjector(), telemetry);
    }

    SpringAiConversationModel(
            ChatModel chatModel,
            PromptResource promptResource,
            SpringAiToolCallbackFactory callbackFactory,
            ConversationHistoryProjector historyProjector,
            ConversationTelemetry telemetry) {
        Assert.notNull(chatModel, "Chat model must not be null");
        Assert.notNull(promptResource, "Prompt resource must not be null");
        Assert.notNull(callbackFactory, "Tool callback factory must not be null");
        Assert.notNull(historyProjector, "Conversation history projector must not be null");
        Assert.notNull(telemetry, "Conversation telemetry must not be null");
        this.chatModel = chatModel;
        this.promptResource = promptResource;
        this.callbackFactory = callbackFactory;
        this.historyProjector = historyProjector;
        this.telemetry = telemetry;
    }

    @Override
    public ModelReply respond(
            ModelRequest request,
            ModelCallReservation reservation,
            Consumer<ModelUsage> usageObserver) {
        Assert.notNull(request, "Model request must not be null");
        Assert.notNull(reservation, "Model call reservation must not be null");
        Assert.notNull(usageObserver, "Model usage observer must not be null");

        Prompt prompt = promptFor(request);
        reservation.reserve();
        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException exception) {
            ModelCallFailure failure = classifyProviderFailure(exception);
            telemetry.model("FAILURE", Optional.of(failure.kind().name()), new ModelUsage(0, 0, 0, false));
            throw failure;
        }
        ModelReply reply = normalize(response);
        ModelUsage modelUsage = usage(response);
        usageObserver.accept(modelUsage);
        telemetry.model("SUCCESS", Optional.empty(), modelUsage);
        LOGGER.info("spring_ai_model_response resultCount={} usageAvailable={}", response.getResults().size(),
                modelUsage.available());
        return reply;
    }

    private Prompt promptFor(ModelRequest request) {
        List<ToolCallback> callbacks = callbackFactory.create(request.toolSnapshot());
        List<Message> messages = new ArrayList<>();
        messages.add(new org.springframework.ai.chat.messages.SystemMessage(promptResource.content()));
        messages.addAll(historyProjector.project(request.history()));
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
        return new Prompt(List.copyOf(messages), options);
    }

    private static ModelReply normalize(ChatResponse response) {
        AssistantMessage message = firstActionableMessage(response).orElseThrow(ModelCallFailure::correctable);
        if (message.hasToolCalls()) {
            return new ModelReply.UseTools(text(message), toolRequests(message));
        }
        return new ModelReply.Text(text(message).orElseThrow(ModelCallFailure::correctable));
    }

    private static Optional<AssistantMessage> firstActionableMessage(ChatResponse response) {
        return Optional.ofNullable(response)
                .map(ChatResponse::getResults)
                .stream()
                .flatMap(List::stream)
                .map(Generation::getOutput)
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

    private static List<ToolRequest> toolRequests(AssistantMessage message) {
        try {
            return message.getToolCalls().stream()
                    .map(toolCall -> new ToolRequest(new ToolName(toolCall.name()), toolCall.arguments()))
                    .toList();
        } catch (IllegalArgumentException exception) {
            throw ModelCallFailure.correctable();
        }
    }

    private static ModelUsage usage(ChatResponse response) {
        Optional<Usage> responseUsage = Optional.ofNullable(response.getMetadata())
                .map(ChatResponseMetadata::getUsage)
                .filter(value -> !(value instanceof EmptyUsage));
        if (responseUsage.isEmpty()) {
            return new ModelUsage(0, 0, 0, false);
        }
        Optional<Integer> promptTokens = Optional.ofNullable(responseUsage.get().getPromptTokens());
        Optional<Integer> completionTokens = Optional.ofNullable(responseUsage.get().getCompletionTokens());
        Optional<Integer> totalTokens = Optional.ofNullable(responseUsage.get().getTotalTokens());
        if (promptTokens.isEmpty() || completionTokens.isEmpty() || totalTokens.isEmpty()) {
            return new ModelUsage(0, 0, 0, false);
        }
        return new ModelUsage(promptTokens.get(), completionTokens.get(), totalTokens.get(), true);
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
