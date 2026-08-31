package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = GoogleNoToolLiveTest.LiveApplication.class, properties = "spring.flyway.enabled=false")
@EnabledIfEnvironmentVariable(named = "GOOGLE_NO_TOOL_LIVE", matches = "true")
class GoogleNoToolLiveTest {

    private final ChatModel provider;

    @Autowired
    GoogleNoToolLiveTest(ChatModel provider) {
        this.provider = provider;
    }

    @Test
    void calls_the_real_google_provider_through_the_final_respond_contract() {
        UserMessage question = new UserMessage(new SessionId("google-live"), new SessionSequence(1),
                Optional.of(new MessageJobId("google-live-job")), Instant.parse("2026-08-31T00:00:00Z"),
                MessageRole.USER, "tester", "Reply with a short greeting and do not call tools.");
        AtomicInteger reservations = new AtomicInteger();
        SpringAiConversationModel model = new SpringAiConversationModel(provider, new PromptResource());

        ModelReply reply = model.respond(new ModelRequest(List.of(question), new DirectToolRegistry(List.of()).snapshot()),
                reservations::incrementAndGet, usage -> { });

        assertThat(reply).isInstanceOf(ModelReply.Text.class);
        assertThat(((ModelReply.Text) reply).message()).isNotBlank();
        assertThat(reservations).hasValue(1);
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class LiveApplication {
    }
}
