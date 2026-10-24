package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.ConversationQueryService;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.domain.ContextHistoryView;
import com.java.system.sessionagent.conversation.domain.ContextUsageProjection;
import com.java.system.sessionagent.conversation.domain.ContextUsageCheckpoint;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ContextSummary;
import com.java.system.sessionagent.conversation.domain.ContextEstimate;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.SkillCommand;
import com.java.system.sessionagent.conversation.domain.SkillCommandState;
import com.java.system.sessionagent.conversation.domain.SkillMessage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.skill.domain.SkillResource;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobPhase;
import com.java.system.sessionagent.conversation.domain.JobProgress;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;
import com.java.system.sessionagent.conversation.port.in.SessionDetailView;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.function.Consumer;

import static com.java.system.sessionagent.conversation.MessageJobTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionQueryServiceTest {

    private static final String SESSION_ID = "a1d4cefe-d5b5-4f40-b9f6-beb41a6831af";
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T10:00:01Z");

    @Test
    void query_usage_matches_actual_worker_context_after_delayed_admission_and_ignores_stale_checkpoint() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        SessionId session = claim.sessionId();
        MessageJobId command = new MessageJobId("accepted");
        MessageJobId pending = new MessageJobId("pending");
        MessageJobId rejected = new MessageJobId("rejected");
        List<SessionMessage> audit = List.of(
                new UserMessage(session, new SessionSequence(1), Optional.empty(), CREATED_AT, MessageRole.USER, "alice", "old"),
                new UserMessage(session, new SessionSequence(2), Optional.of(command), CREATED_AT, MessageRole.USER, "alice", "/skill:review actual question"),
                new AssistantMessage(session, new SessionSequence(3), Optional.of(new MessageJobId("old")), CREATED_AT, MessageRole.ASSISTANT, "old answer"),
                new SkillMessage(session, new SessionSequence(4), Optional.of(command), CREATED_AT, MessageRole.SKILL,
                        new SkillResource("skill://review/SKILL.md", "a".repeat(64), "review instructions")),
                new UserMessage(session, new SessionSequence(5), Optional.of(pending), CREATED_AT, MessageRole.USER, "alice", "/skill:review " + "secret".repeat(1000)),
                new UserMessage(session, new SessionSequence(6), Optional.of(rejected), CREATED_AT, MessageRole.USER, "alice", "/skill:missing secret"),
                new RuntimeMessage(session, new SessionSequence(7), Optional.of(rejected), CREATED_AT, MessageRole.RUNTIME, "SKILL_UNAVAILABLE", "safe rejection"));
        SkillCommand parsed = SkillCommand.parse("/skill:review actual question").orElseThrow();
        ConversationHistory history = new ConversationHistory(audit, Map.of(
                command, new SkillCommandState(command, parsed, SkillCommandState.Disposition.ACCEPTED, Optional.of(new SessionSequence(4))),
                pending, new SkillCommandState(pending, parsed, SkillCommandState.Disposition.PENDING, Optional.empty()),
                rejected, new SkillCommandState(rejected, parsed, SkillCommandState.Disposition.REJECTED, Optional.of(new SessionSequence(7)))));
        ModelDescriptor descriptor = new ModelDescriptor(TEST_ROUTE_ID, "capacity", 100_000);
        ContextSummary summary = new ContextSummary("previous summary");
        ContextCompaction compaction = new ContextCompaction(1, new MessageJobId("old"), ContextCompaction.Reason.THRESHOLD,
                summary, new SessionSequence(3), descriptor, "old-shape", 100, 10, CREATED_AT);
        when(store.loadContextHistory(session)).thenReturn(history);
        when(store.loadHistory(session)).thenReturn(audit);
        when(store.readSession(session)).thenReturn(Optional.of(new ConversationStore.SessionProjection(session, CREATED_AT, Optional.empty(), "test")));
        when(store.loadCompaction(session)).thenReturn(Optional.of(compaction));
        when(store.reserveModelCall(any(), anyInt(), any())).thenReturn(OptionalInt.of(1));
        when(store.loadUsageCheckpoint(any(), any(), any(), any(), anyLong())).thenReturn(Optional.of(
                new ContextUsageCheckpoint(descriptor, 1, new SessionSequence(3), 900_000, 99_999, 999_999, "stale-eligibility", 1, CREATED_AT)));
        AtomicReference<ModelRequest> actual = new AtomicReference<>();
        ConversationModel model = new ConversationModel() {
            @Override public ModelRouteId routeId() { return TEST_ROUTE_ID; }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "system"; }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> observer) {
                actual.set(request);
                reservation.reserve();
                return result(new ModelReply.Text("done"));
            }
        };
        ToolCatalog tools = () -> new ToolSnapshot(List.of());

        service(store, model, tools).process(claim, () -> true);
        ConversationQueryService query = new ConversationQueryService(store, TestConfiguredModels.withModel(model), tools, new ContextUsageEstimator());
        SessionDetailView detail = query.session(session.value()).orElseThrow();

        assertThat(actual.get()).isNotNull();
        assertThat(actual.get().history()).containsExactly(audit.get(1), audit.get(3));
        ContextUsageEstimator estimator = new ContextUsageEstimator();
        ContextUsageProjection expected = new ContextUsageProjection(descriptor, "system", List.of(), actual.get().history(), 1,
                Optional.of(summary), new ContextHistoryView(history).eligibilityFingerprint());
        assertThat(detail.context().estimatedUsedTokens()).isEqualTo(estimator.estimate(expected, Optional.empty()).tokens());
        assertThat(detail.context().basis()).isEqualTo(ContextEstimate.Basis.FULL_ESTIMATE);
        assertThat(query.messages(session.value())).contains(audit);
        verify(store, times(2)).loadUsageCheckpoint(session, claim.modelSelection(), descriptor,
                estimator.requestShapeFingerprint(expected), 1);
    }

    @Test
    void loads_a_paged_history_window_without_loading_complete_history_and_keeps_exhausted_and_missing_distinct() {
        ConversationStore store = mock(ConversationStore.class);
        SessionId sessionId = new SessionId(SESSION_ID);
        UserMessage pageMessage = new UserMessage(sessionId, new SessionSequence(4), Optional.empty(), CREATED_AT,
                MessageRole.USER, "U01", "page content");
        when(store.loadHistoryPage(sessionId, 3, 2)).thenReturn(Optional.of(List.of(pageMessage)));
        when(store.loadHistoryPage(sessionId, 4, 2)).thenReturn(Optional.of(List.of()));
        when(store.loadHistoryPage(new SessionId("a1d4cefe-d5b5-4f40-b9f6-beb41a6831a0"), 0, 2)).thenReturn(Optional.empty());
        when(store.loadHistory(any())).thenThrow(new AssertionError("Paged history must not load complete history"));
        ConversationQueryService service = new ConversationQueryService(store,
                TestConfiguredModels.withModel(model(new ModelDescriptor(new ModelRouteId("test"), "test-model", 1_000))),
                () -> new ToolSnapshot(List.of()), new ContextUsageEstimator());

        assertThat(service.messages(SESSION_ID, 3, 2)).contains(List.of(pageMessage));
        assertThat(service.messages(SESSION_ID, 4, 2)).contains(List.of());
        assertThat(service.messages("a1d4cefe-d5b5-4f40-b9f6-beb41a6831a0", 0, 2)).isEmpty();

        verify(store).loadHistoryPage(sessionId, 3, 2);
        verify(store).loadHistoryPage(sessionId, 4, 2);
        verify(store).loadHistoryPage(new SessionId("a1d4cefe-d5b5-4f40-b9f6-beb41a6831a0"), 0, 2);
        verify(store, never()).loadHistory(any());
    }

    @Test
    void projects_active_context_usage_without_promoting_session_content_or_provider_metadata() {
        ConversationStore store = mock(ConversationStore.class);
        SessionId sessionId = new SessionId(SESSION_ID);
        Instant cancellationRequestedAt = Instant.parse("2026-10-13T15:45:01Z");
        JobProgress activeProgress = new JobProgress(JobPhase.MODEL_CALL, cancellationRequestedAt, Optional.empty());
        when(store.readSession(sessionId)).thenReturn(Optional.of(new ConversationStore.SessionProjection(sessionId, CREATED_AT,
                Optional.of(new ConversationStore.MessageJobProjection(new MessageJobId("f47bdb7e-75c3-4dbf-bdd4-1f8681705b62"), sessionId,
                        JobStatus.WORKING, 1, 2, Optional.of(activeProgress), Optional.empty(), Optional.of(cancellationRequestedAt))), "test")));
        when(store.loadContextHistory(sessionId)).thenReturn(new ConversationHistory(List.of(new UserMessage(sessionId, new SessionSequence(1),
                Optional.of(new MessageJobId("f47bdb7e-75c3-4dbf-bdd4-1f8681705b62")), CREATED_AT, MessageRole.USER, "U01", "private text")), Map.of()));
        when(store.loadCompaction(sessionId)).thenReturn(Optional.empty());
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("google-genai"), "gemini-3.1-flash-lite", 1_048_576);
        ConversationModel model = model(descriptor);
        ToolCatalog tools = () -> new ToolSnapshot(List.of());
        ConversationQueryService service = new ConversationQueryService(store, TestConfiguredModels.withModel(model), tools,
                new ContextUsageEstimator());

        SessionDetailView detail = service.session(SESSION_ID).orElseThrow();

        assertThat(detail.sessionId()).isEqualTo(SESSION_ID);
        assertThat(detail.currentJob()).isPresent();
        assertThat(detail.currentJob().orElseThrow().cancellationRequestedAt()).contains(cancellationRequestedAt);
        assertThat(detail.currentJob().orElseThrow().progress()).contains(activeProgress);
        assertThat(detail.currentJob().orElseThrow().failure()).isEmpty();
        assertThat(detail.latestCompaction()).isEmpty();
        assertThat(detail.context().modelId()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(detail.context().capacityTokens()).isEqualTo(1_048_576);
        assertThat(detail.context().estimatedUsedTokens()).isPositive();
        assertThat(detail.context().basis().name()).isEqualTo("FULL_ESTIMATE");
    }

    @Test
    void projects_safe_job_failure_without_live_progress_for_polling() {
        ConversationStore store = mock(ConversationStore.class);
        MessageJobId jobId = new MessageJobId("f47bdb7e-75c3-4dbf-bdd4-1f8681705b62");
        SessionId sessionId = new SessionId(SESSION_ID);
        JobFailure failure = JobFailure.MODEL_UNAVAILABLE;
        when(store.readJob(jobId)).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                jobId, sessionId, JobStatus.RETRY, 1, 1, Optional.empty(), Optional.of(failure), Optional.empty())));
        ConversationQueryService service = new ConversationQueryService(store,
                TestConfiguredModels.withModel(model(new ModelDescriptor(new ModelRouteId("test"), "test-model", 1_000))),
                () -> new ToolSnapshot(List.of()), new ContextUsageEstimator());

        MessageJobView view = service.findJob(jobId.value()).orElseThrow();

        assertThat(view.status()).isEqualTo(JobStatus.RETRY);
        assertThat(view.progress()).isEmpty();
        assertThat(view.failure()).contains(failure);
        assertThat(view.cancellationRequestedAt()).isEmpty();
    }

    @Test
    void projects_cancellation_request_timestamp_for_job_polling() {
        ConversationStore store = mock(ConversationStore.class);
        MessageJobId jobId = new MessageJobId("f47bdb7e-75c3-4dbf-bdd4-1f8681705b62");
        SessionId sessionId = new SessionId(SESSION_ID);
        Instant cancellationRequestedAt = Instant.parse("2026-10-13T15:45:01Z");
        when(store.readJob(jobId)).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                jobId, sessionId, JobStatus.WORKING, 0, 0, Optional.empty(), Optional.empty(),
                Optional.of(cancellationRequestedAt))));
        ConversationQueryService service = new ConversationQueryService(store,
                TestConfiguredModels.withModel(model(new ModelDescriptor(new ModelRouteId("test"), "test-model", 1_000))),
                () -> new ToolSnapshot(List.of()), new ContextUsageEstimator());

        MessageJobView view = service.findJob(jobId.value()).orElseThrow();

        assertThat(view.cancellationRequestedAt()).contains(cancellationRequestedAt);

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
