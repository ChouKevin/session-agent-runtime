package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = GoogleModelLiveTest.LiveApplication.class, properties = "spring.flyway.enabled=false")
@EnabledIfEnvironmentVariable(named = "GOOGLE_MODEL_LIVE", matches = "true")
class GoogleModelLiveTest {

    private final ChatModel provider;
    private final SpringAiRetryProperties retryProperties;

    @Autowired
    GoogleModelLiveTest(ChatModel provider, SpringAiRetryProperties retryProperties) {
        this.provider = provider;
        this.retryProperties = retryProperties;
    }

    @Test
    void makes_one_provider_neutral_real_model_call() {
        UserMessage question = new UserMessage(new SessionId("google-live"), new SessionSequence(1),
                Optional.of(new MessageJobId("google-live-job")), Instant.parse("2026-08-31T00:00:00Z"),
                MessageRole.USER, "tester", "Reply with a short greeting and do not call tools.");
        AtomicInteger reservations = new AtomicInteger();
        CountingChatModel countingModel = new CountingChatModel(provider);
        SpringAiConversationModel model = new SpringAiConversationModel(
                countingModel, new PromptResource(), new NoOpConversationTelemetry(), new ObjectMapper());

        ModelReply reply = model.respond(new ModelRequest(List.of(question), new ToolSnapshot(List.of())),
                reservations::incrementAndGet, usage -> { });

        assertThat(retryProperties.getMaxAttempts()).isEqualTo(1);
        assertThat(reply).isInstanceOf(ModelReply.Text.class);
        assertThat(countingModel.calls()).isEqualTo(1);
        assertThat(reservations.get()).isEqualTo(1);
        assertThat(((ModelReply.Text) reply).message()).isNotBlank();
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class LiveApplication {
    }

    private static final class CountingChatModel implements ChatModel {

        private final ChatModel delegate;
        private int callCount;

        private CountingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount++;
            return delegate.call(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }

        private int calls() {
            return callCount;
        }
    }
}
