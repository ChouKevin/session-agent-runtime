package com.java.system.sessionagent.model;

import com.java.system.sessionagent.bootstrap.MicrometerConversationTelemetry;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.application.ToolSnapshot;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolName;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiConversationModelTest {

    @Test
    void preserves_text_only_generation_without_interpreting_its_content() {
        String message = "## Answer\n\n{\"source\":\"opaque\"}";
        RecordingChatModel chatModel = new RecordingChatModel(response(new AssistantMessage(message)));
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new PromptResource());
        AtomicReference<ModelUsage> observedUsage = new AtomicReference<>();

        ModelReply reply = model.respond(request(), () -> 1, observedUsage::set);

        assertThat(reply).isEqualTo(new ModelReply.Text(message));
        assertThat(observedUsage.get()).isEqualTo(new ModelUsage(7, 3, 10, true));
    }

    @Test
    void keeps_same_generation_text_and_ordered_tool_requests_together() {
        AssistantMessage message = AssistantMessage.builder()
                .content("I will inspect both sources.")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("provider-1", "function", "first", "{}"),
                        new AssistantMessage.ToolCall("provider-2", "function", "second", "{\"id\":2}")))
                .build();
        RecordingChatModel chatModel = new RecordingChatModel(response(message));
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new PromptResource());

        ModelReply reply = model.respond(request(), () -> 1, usage -> { });

        assertThat(reply).isEqualTo(new ModelReply.UseTools(
                Optional.of("I will inspect both sources."),
                List.of(
                        new ToolRequest(new ToolName("first"), "{}"),
                        new ToolRequest(new ToolName("second"), "{\"id\":2}"))));
        assertThat(chatModel.prompt.getInstructions().getFirst())
                .isInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class);
        assertThat(((ToolCallingChatOptions) chatModel.prompt.getOptions()).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("first");
    }

    @Test
    void selects_the_first_actionable_generation_and_ignores_later_candidates() {
        SpringAiConversationModel model = new SpringAiConversationModel(new RecordingChatModel(response(
                new AssistantMessage(""),
                new AssistantMessage("first answer"),
                toolMessage("later", "{}"))), new PromptResource());

        ModelReply reply = model.respond(request(), () -> 1, usage -> { });

        assertThat(reply).isEqualTo(new ModelReply.Text("first answer"));
    }

    @Test
    void does_not_merge_tool_requests_from_separate_generations() {
        SpringAiConversationModel model = new SpringAiConversationModel(new RecordingChatModel(response(
                toolMessage("first", "{}"),
                toolMessage("second", "{\"id\":2}"))), new PromptResource());

        ModelReply reply = model.respond(request(), () -> 1, usage -> { });

        assertThat(reply).isEqualTo(new ModelReply.UseTools(Optional.empty(),
                List.of(new ToolRequest(new ToolName("first"), "{}"))));
    }

    @Test
    void rejects_generations_without_nonblank_text_or_tool_requests() {
        SpringAiConversationModel model = new SpringAiConversationModel(
                new RecordingChatModel(response(new AssistantMessage("   "))), new PromptResource());

        assertThatThrownBy(() -> model.respond(request(), () -> 1, usage -> { }))
                .isInstanceOf(ModelCallFailure.class)
                .extracting(exception -> ((ModelCallFailure) exception).kind())
                .isEqualTo(ModelCallFailure.Kind.CORRECTABLE);
    }

    @Test
    void records_one_failure_counter_and_duration_for_an_unusable_provider_response() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingChatModel chatModel = new RecordingChatModel(response(new AssistantMessage("   ")));
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new PromptResource(),
                new MicrometerConversationTelemetry(registry));

        assertThatThrownBy(() -> model.respond(request(), () -> 1, usage -> { }))
                .isInstanceOf(ModelCallFailure.class)
                .extracting(exception -> ((ModelCallFailure) exception).kind())
                .isEqualTo(ModelCallFailure.Kind.CORRECTABLE);

        Counter failureCounter = registry.find("session_agent.model").tags(
                "outcome", "FAILURE", "category", "OUTPUT_INVALID", "usage_available", "false").counter();
        Timer failureDuration = registry.find("session_agent.model.duration").tags(
                "outcome", "FAILURE", "category", "OUTPUT_INVALID", "usage_available", "false").timer();
        assertThat(chatModel.callCount).isEqualTo(1);
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
        assertThat(failureDuration).isNotNull();
        assertThat(failureDuration.count()).isEqualTo(1);
    }

    @Test
    void reserves_before_the_single_provider_call_and_consumes_reservation_when_provider_fails() {
        List<String> events = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("provider rejected request");
        RecordingChatModel chatModel = new RecordingChatModel(failure, events);
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new PromptResource());

        assertThatThrownBy(() -> model.respond(request(), () -> {
            events.add("reserve");
            return 1;
        }, usage -> { })).isInstanceOf(ModelCallFailure.class);

        assertThat(events).containsExactly("reserve", "call");
        assertThat(chatModel.callCount).isEqualTo(1);
    }

    @Test
    void does_not_call_the_provider_when_reservation_is_denied() {
        RecordingChatModel chatModel = new RecordingChatModel(response(new AssistantMessage("unused")));
        SpringAiConversationModel model = new SpringAiConversationModel(chatModel, new PromptResource());

        assertThatThrownBy(() -> model.respond(request(), () -> {
            throw new IllegalStateException("budget exhausted");
        }, usage -> { })).isInstanceOf(IllegalStateException.class)
                .hasMessage("budget exhausted");

        assertThat(chatModel.callCount).isZero();
    }

    private static ModelRequest request() {
        return new ModelRequest(List.of(), snapshot());
    }

    private static ToolSnapshot snapshot() {
        ToolDefinition definition = new ToolDefinition(new ToolName("first"), "First tool", "{\"type\":\"object\"}");
        ToolRegistration<String> registration = new ToolRegistration<>(definition, String.class,
                ignored -> "{}");
        return new DirectToolRegistry(List.of(registration)).snapshot();
    }

    private static AssistantMessage toolMessage(String name, String arguments) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("provider-call", "function", name, arguments)))
                .build();
    }

    private static ChatResponse response(AssistantMessage... messages) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(7, 3, 10)).build();
        return new ChatResponse(List.of(messages).stream().map(Generation::new).toList(), metadata);
    }

    private static final class RecordingChatModel implements ChatModel {

        private final Optional<ChatResponse> response;
        private final Optional<RuntimeException> failure;
        private final List<String> events;
        private Prompt prompt;
        private int callCount;

        private RecordingChatModel(ChatResponse response) {
            this.response = Optional.of(response);
            this.failure = Optional.empty();
            this.events = new ArrayList<>();
        }

        private RecordingChatModel(RuntimeException failure, List<String> events) {
            this.response = Optional.empty();
            this.failure = Optional.of(failure);
            this.events = events;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            callCount++;
            events.add("call");
            if (failure.isPresent()) {
                throw failure.orElseThrow();
            }
            return response.orElseThrow();
        }

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
