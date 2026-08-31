package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void appends_final_assistant_text_and_completes_the_claim() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1));
        ConversationModel model = (request, reservation, usage) -> {
            reservation.reserve();
            usage.accept(new ModelUsage(1, 1, 2, true));
            return new ModelReply.Text("answer");
        };

        service(store, model).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue().messages()).containsExactly(new ConversationStore.AssistantData("answer"));
        assertThat(batch.getValue().jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
    }

    @Test
    void schedules_a_transient_model_failure_without_appending_history() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 1)));
        when(store.scheduleRetry(eq(claim), eq(Duration.ofSeconds(1)))).thenReturn(true);

        service(store, (request, reservation, usage) -> {
            reservation.reserve();
            throw ModelCallFailure.transientFailure();
        }).process(claim, () -> true);

        verify(store).scheduleRetry(claim, Duration.ofSeconds(1));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).append(any(), any(), any());
    }

    private static MessageJobService service(ConversationStore store, ConversationModel model) {
        return new MessageJobService(store, model, new DirectToolRegistry(List.of()), Clock.fixed(NOW, ZoneOffset.UTC), 3,
                new MessageJobRetryPolicy(2, Duration.ofSeconds(10)), new com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry());
    }

    private static MessageWorkClaim claim() {
        return new MessageWorkClaim(new MessageJobId("job"), new SessionId("session"), "worker", 1L, NOW, NOW.plusSeconds(30));
    }

    private static UserMessage user() {
        return new UserMessage(new SessionId("session"), new SessionSequence(1L), Optional.of(new MessageJobId("job")), NOW,
                MessageRole.USER, "alice", "hello");
    }
}
