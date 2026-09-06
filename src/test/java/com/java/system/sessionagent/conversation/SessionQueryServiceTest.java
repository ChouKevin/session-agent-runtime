package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.ConversationQueryService;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.SessionDetailView;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionQueryServiceTest {

    private static final String SESSION_ID = "a1d4cefe-d5b5-4f40-b9f6-beb41a6831af";
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T10:00:01Z");

    @Test
    void projects_active_context_usage_without_promoting_session_content_or_provider_metadata() {
        ConversationStore store = mock(ConversationStore.class);
        SessionId sessionId = new SessionId(SESSION_ID);
        when(store.readSession(sessionId)).thenReturn(Optional.of(new ConversationStore.SessionProjection(sessionId, CREATED_AT,
                Optional.of(new ConversationStore.MessageJobProjection(new MessageJobId("f47bdb7e-75c3-4dbf-bdd4-1f8681705b62"), sessionId,
                        com.java.system.sessionagent.conversation.domain.JobStatus.DONE, 1, 2)))));
        when(store.loadHistory(sessionId)).thenReturn(List.of(new UserMessage(sessionId, new SessionSequence(1),
                Optional.of(new MessageJobId("f47bdb7e-75c3-4dbf-bdd4-1f8681705b62")), CREATED_AT, MessageRole.USER, "U01", "private text")));
        when(store.loadCompaction(sessionId)).thenReturn(Optional.empty());
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("google-genai"), "gemini-3.1-flash-lite", 1_048_576);
        ConversationModel model = model(descriptor);
        ToolCatalog tools = () -> new ToolSnapshot(List.of());
        ConversationQueryService service = new ConversationQueryService(store, model, tools, new ContextUsageEstimator());

        SessionDetailView detail = service.session(SESSION_ID).orElseThrow();

        assertThat(detail.sessionId()).isEqualTo(SESSION_ID);
        assertThat(detail.currentJob()).isPresent();
        assertThat(detail.latestCompaction()).isEmpty();
        assertThat(detail.context().modelId()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(detail.context().capacityTokens()).isEqualTo(1_048_576);
        assertThat(detail.context().estimatedUsedTokens()).isPositive();
        assertThat(detail.context().basis().name()).isEqualTo("FULL_ESTIMATE");
    }

    private static ConversationModel model(ModelDescriptor descriptor) {
        return new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return descriptor.routeId();
            }

            @Override
            public ModelDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public String systemPrompt() {
                return "Runtime system prompt";
            }

            @Override
            public com.java.system.sessionagent.conversation.domain.ModelCallResult respond(
                    com.java.system.sessionagent.conversation.domain.ModelRequest request,
                    com.java.system.sessionagent.conversation.port.out.ModelCallReservation reservation,
                    java.util.function.Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver) {
                throw new UnsupportedOperationException("Session query does not call the model");
            }
        };
    }
}
