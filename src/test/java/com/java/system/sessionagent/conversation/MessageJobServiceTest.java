package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.domain.ToolResult;
import com.java.system.sessionagent.tool.application.ToolExecutionFailure;
import com.java.system.sessionagent.model.ConversationHistoryProjector;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessageJobServiceTest {

    private static final String MODEL_CONTEXT = "dGVzdA==";

    @Test
    void issues_catalog_then_full_snapshot_and_commits_the_valid_cited_reply() {
        RecordingStore store = new RecordingStore();
        ScriptedModel model = new ScriptedModel(store,
                new ModelDecision.UseTool("catalog-call", new ToolName("list_repositories"), "{}", MODEL_CONTEXT),
                new ModelDecision.UseTool("source-call", new ToolName("source"),
                        "{\"repositoryId\":\"payment-service\"}", MODEL_CONTEXT));
        DirectToolRegistry registry = new DirectToolRegistry(List.of(
                registration("list_repositories", ToolKind.CATALOG, Optional.empty(), Optional.empty(), "{\"repositories\":[]}"),
                registration("source", ToolKind.SOURCE, Optional.of("payment-service"), Optional.of("rev-a"), "{\"members\":[]}")));
        MessageJobService service = new MessageJobService(store, model, registry,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(model.snapshots).containsExactly(List.of("list_repositories", "source"), List.of("list_repositories", "source"), List.of("list_repositories", "source"));
        assertThat(store.toolMessages).hasSize(2);
        assertThat(store.toolMessages.get(1).arguments()).isEqualTo("{\"repositoryId\":\"payment-service\"}");
        assertThat(store.storedReplyText).isEqualTo("Answer");
    }

    @Test
    void treats_a_not_issued_tool_as_safe_correctable_feedback_and_continues() {
        RecordingStore store = new RecordingStore();
        store.seedCatalog();
        ScriptedModel model = new ScriptedModel(store,
                new ModelDecision.UseTool("unknown-call", new ToolName("not-issued"), "{}", MODEL_CONTEXT),
                new ModelDecision.UseTool("source-call", new ToolName("source"),
                        "{\"repositoryId\":\"payment-service\"}", MODEL_CONTEXT));
        DirectToolRegistry registry = new DirectToolRegistry(List.of(
                registration("list_repositories", ToolKind.CATALOG, Optional.empty(), Optional.empty(), "{\"repositories\":[]}"),
                registration("source", ToolKind.SOURCE, Optional.of("payment-service"), Optional.of("rev-a"), "{\"members\":[]}")));
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        MessageJobService service = new MessageJobService(store, model, registry,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC),
                new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(3, java.time.Duration.ofSeconds(60)), telemetry);

        service.process(store.claim, () -> true);

        assertThat(store.feedbackCodes).containsExactly("INVALID_TOOL_INPUT");
        assertThat(store.toolMessages).hasSize(2);
        assertThat(store.storedReplyText).isEqualTo("Answer");
        verify(telemetry).tool("not-issued", "INVALID_INPUT", Optional.empty(), Optional.empty());
        verify(telemetry).feedback("INVALID_TOOL_INPUT");
    }

    @Test
    void persists_an_opaque_final_reply_without_citation_validation() {
        RecordingStore store = new RecordingStore();
        store.seedSource();
        List<Integer> planOrdinals = new java.util.ArrayList<>();
        List<Integer> replyOrdinals = new java.util.ArrayList<>();
        ConversationModel model = model(
                (request, usageObserver) -> {
                    planOrdinals.add(request.callContext().ordinal());
                    return new ModelDecision.AnswerReady();
                },
                (request, usageObserver) -> {
                    replyOrdinals.add(request.callContext().ordinal());
                    return "bad";
                });
        DirectToolRegistry registry = new DirectToolRegistry(List.of(
                registration("list_repositories", ToolKind.CATALOG, Optional.empty(), Optional.empty(), "{\"repositories\":[]}")));
        MessageJobService service = new MessageJobService(store, model, registry,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackCodes).isEmpty();
        assertThat(planOrdinals).containsExactly(1);
        assertThat(replyOrdinals).containsExactly(2);
        assertThat(store.calls).isEqualTo(2);
        assertThat(store.storedReplyText).isEqualTo("bad");
    }

    @Test
    void schedules_the_existing_model_retry_after_an_early_direct_reply_transient_failure() {
        RecordingStore store = new RecordingStore();
        store.job = Optional.of(new ConversationStore.MessageJobProjection(store.claim.messageJobId(), store.claim.sessionId(),
                com.java.system.sessionagent.conversation.domain.JobStatus.WORKING, 0, 1, Optional.empty()));
        List<Integer> planOrdinals = new java.util.ArrayList<>();
        List<Integer> replyOrdinals = new java.util.ArrayList<>();
        ConversationModel model = model(
                (request, usageObserver) -> {
                    planOrdinals.add(request.callContext().ordinal());
                    return new ModelDecision.AnswerReady();
                },
                (request, usageObserver) -> {
                    replyOrdinals.add(request.callContext().ordinal());
                    throw ModelCallFailure.transientFailure();
                });
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));
        ListAppender<ILoggingEvent> appender = attachAppender(MessageJobService.class);

        try {
            service.process(store.claim, () -> true);

            assertThat(planOrdinals).containsExactly(1);
            assertThat(replyOrdinals).containsExactly(2);
            assertThat(store.calls).isEqualTo(2);
            assertThat(store.scheduledAt).contains(store.claim.claimedAt().plusSeconds(1));
            assertThat(store.feedbackMessages).isEmpty();
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .contains("model_call_failed sessionId=session-1 messageJobId=job-1 ordinal=2 "
                            + "phase=FINAL_REPLY closedFailureKind=TRANSIENT");
        } finally {
            detachAppender(MessageJobService.class, appender);
        }
    }

    @Test
    void persists_an_early_final_reply_without_requesting_citation_correction() {
        RecordingStore store = new RecordingStore();
        store.seedSource();
        List<Integer> planOrdinals = new java.util.ArrayList<>();
        List<Integer> replyOrdinals = new java.util.ArrayList<>();
        ConversationModel model = model(
                (request, usageObserver) -> {
                    planOrdinals.add(request.callContext().ordinal());
                    return new ModelDecision.AnswerReady();
                },
                (request, usageObserver) -> {
                    replyOrdinals.add(request.callContext().ordinal());
                    return "bad";
                });
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));
        ListAppender<ILoggingEvent> appender = attachAppender(MessageJobService.class);

        try {
            service.process(store.claim, () -> true);

            assertThat(planOrdinals).containsExactly(1);
            assertThat(replyOrdinals).containsExactly(2);
            assertThat(store.calls).isEqualTo(2);
            assertThat(store.feedbackMessages).isEmpty();
            assertThat(store.storedReplyText).isEqualTo("bad");
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .contains("model_call_decision sessionId=session-1 messageJobId=job-1 ordinal=2 "
                                    + "phase=FINAL_REPLY decisionCategory=ASSISTANT_TEXT");
        } finally {
            detachAppender(MessageJobService.class, appender);
        }
    }

    @Test
    void turns_a_twelfth_call_model_failure_into_one_terminal_call_limit_feedback() {
        RecordingStore store = new RecordingStore();
        store.calls = 11;
        ConversationModel model = model(
                (request, usageObserver) -> { throw new AssertionError("plan must not be called"); },
                (request, usageObserver) -> { throw ModelCallFailure.correctable(); });
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackCodes).containsExactly("CALL_LIMIT_REACHED");
        assertThat(store.calls).isEqualTo(12);
    }

    @Test
    void preserves_context_too_large_feedback_on_the_twelfth_model_call() {
        RecordingStore store = new RecordingStore();
        store.calls = 11;
        ConversationModel model = model(
                (request, usageObserver) -> { throw new AssertionError("plan must not be called"); },
                (request, usageObserver) -> { throw ModelCallFailure.contextTooLarge(); });
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackCodes).containsExactly("CONTEXT_TOO_LARGE");
        assertThat(store.feedbackMessages).singleElement().extracting(FeedbackMessage::terminal).isEqualTo(true);
    }

    @Test
    void terminates_a_transient_twelfth_model_failure_without_scheduling_a_thirteenth_call() {
        RecordingStore store = new RecordingStore();
        store.calls = 11;
        store.job = Optional.of(new ConversationStore.MessageJobProjection(store.claim.messageJobId(), store.claim.sessionId(),
                com.java.system.sessionagent.conversation.domain.JobStatus.WORKING, 0, 11, Optional.empty()));
        ConversationModel model = model(
                (request, usageObserver) -> { throw new AssertionError("plan must not be called"); },
                (request, usageObserver) -> { throw ModelCallFailure.transientFailure(); });
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackCodes).containsExactly("DEPENDENCY_UNAVAILABLE");
        assertThat(store.feedbackMessages).singleElement().extracting(FeedbackMessage::terminal).isEqualTo(true);
        assertThat(store.scheduledAt).isEmpty();
        assertThat(store.calls).isEqualTo(12);
    }

    @Test
    void preserves_the_original_rejected_tool_request_for_history_reconstruction() {
        RecordingStore store = new RecordingStore();
        store.seedSource();
        AtomicInteger sourceExecutions = new AtomicInteger();
        ModelDecision.UseTool rejected = new ModelDecision.UseTool(
                "source-before-catalog", new ToolName("source"), "{not-json}", MODEL_CONTEXT);
        ScriptedModel model = new ScriptedModel(store,
                "done", rejected, new ModelDecision.AnswerReady());
        DirectToolRegistry registry = new DirectToolRegistry(List.of(
                registration("list_repositories", ToolKind.CATALOG, Optional.empty(), Optional.empty(), "{\"repositories\":[]}"),
                countedRegistration("source", ToolKind.SOURCE, sourceExecutions)));
        MessageJobService service = new MessageJobService(store, model, registry,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackMessages).singleElement().satisfies(feedback -> {
            assertThat(feedback.code()).isEqualTo("INVALID_TOOL_INPUT");
            assertThat(feedback.modelCallId()).contains("source-before-catalog");
            assertThat(feedback.toolName()).contains("source");
            assertThat(feedback.rejectedArguments()).contains("{not-json}");
            assertThat(feedback.message()).doesNotContain("{not-json}");
        });
        List<org.springframework.ai.chat.messages.Message> history = new ConversationHistoryProjector().project(store.loadHistory(store.claim.sessionId()));
        org.springframework.ai.chat.messages.AssistantMessage request = (org.springframework.ai.chat.messages.AssistantMessage) history.get(2);
        org.springframework.ai.chat.messages.ToolResponseMessage response = (org.springframework.ai.chat.messages.ToolResponseMessage) history.get(3);
        assertThat(request.getToolCalls().getFirst().arguments()).isEqualTo("{not-json}");
        assertThat(response.getResponses().getFirst().responseData()).contains("INVALID_TOOL_INPUT");
        assertThat(sourceExecutions).hasValue(0);
    }

    @Test
    void persists_a_twelfth_opaque_final_reply() {
        RecordingStore store = new RecordingStore();
        store.calls = 11;
        store.seedSource();
        ConversationModel model = model(
                (request, usageObserver) -> { throw new AssertionError("plan must not be called"); },
                (request, usageObserver) -> "bad");
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));
        ListAppender<ILoggingEvent> appender = attachAppender(MessageJobService.class);

        try {
            service.process(store.claim, () -> true);

            assertThat(store.feedbackCodes).isEmpty();
            assertThat(store.feedbackMessages).isEmpty();
            assertThat(store.storedReplyText).isEqualTo("bad");
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .contains("model_call_decision sessionId=session-1 messageJobId=job-1 ordinal=12 "
                            + "phase=FINAL_REPLY decisionCategory=ASSISTANT_TEXT");
        } finally {
            detachAppender(MessageJobService.class, appender);
        }
    }

    @Test
    void turns_a_twelfth_transient_citation_validation_into_terminal_dependency_feedback_without_retrying() {
        RecordingStore store = new RecordingStore();
        store.calls = 11;
        store.seedSource();
        store.job = Optional.of(new ConversationStore.MessageJobProjection(store.claim.messageJobId(), store.claim.sessionId(),
                com.java.system.sessionagent.conversation.domain.JobStatus.WORKING, 0, 11, Optional.empty()));
        ConversationModel model = model(
                (request, usageObserver) -> { throw new AssertionError("plan must not be called"); },
                (request, usageObserver) -> "answer");
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackCodes).isEmpty();
        assertThat(store.storedReplyText).isEqualTo("answer");
        assertThat(store.scheduledAt).isEmpty();
        assertThat(store.calls).isEqualTo(12);
    }

    @Test
    void reserves_a_separate_reply_call_after_answer_ready() {
        RecordingStore store = new RecordingStore();
        store.seedSource();
        List<Integer> planOrdinals = new java.util.ArrayList<>();
        List<Integer> replyOrdinals = new java.util.ArrayList<>();
        ConversationModel model = model(
                (request, usageObserver) -> {
                    planOrdinals.add(request.callContext().ordinal());
                    return new ModelDecision.AnswerReady();
                },
                (request, usageObserver) -> {
                    replyOrdinals.add(request.callContext().ordinal());
                    return "answer";
                });
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(planOrdinals).containsExactly(1);
        assertThat(replyOrdinals).containsExactly(2);
        assertThat(store.calls).isEqualTo(2);
    }

    @Test
    void uses_the_twelfth_call_as_a_direct_final_reply_fallback() {
        RecordingStore store = new RecordingStore();
        store.calls = 11;
        store.seedSource();
        List<Integer> replyOrdinals = new java.util.ArrayList<>();
        ConversationModel model = model(
                (request, usageObserver) -> { throw new AssertionError("plan must not be called"); },
                (request, usageObserver) -> {
                    replyOrdinals.add(request.callContext().ordinal());
                    return "answer";
                });
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(replyOrdinals).containsExactly(12);
        assertThat(store.calls).isEqualTo(12);
    }

    @ParameterizedTest
    @MethodSource("rejectedCatalogArguments")
    void persists_original_malformed_or_oversize_tool_arguments_without_executing(String arguments, String expectedCode) {
        RecordingStore store = new RecordingStore();
        store.seedCatalog();
        AtomicInteger executions = new AtomicInteger();
        ConversationModel model = model((request, usageObserver) -> new ModelDecision.UseTool(
                "catalog-call", new ToolName("list_repositories"), arguments, MODEL_CONTEXT));
        DirectToolRegistry registry = new DirectToolRegistry(List.of(countedRegistration("list_repositories", ToolKind.CATALOG, executions)));
        MessageJobService service = new MessageJobService(store, model, registry,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> store.feedbackMessages.isEmpty());

        assertThat(store.feedbackMessages).singleElement().satisfies(feedback -> {
            assertThat(feedback.code()).isEqualTo(expectedCode);
            assertThat(feedback.modelCallId()).contains("catalog-call");
            assertThat(feedback.toolName()).contains("list_repositories");
            assertThat(feedback.rejectedArguments()).contains(arguments);
            assertThat(feedback.message()).doesNotContain(arguments);
        });
        assertThat(executions).hasValue(0);
    }

    @Test
    void clamps_tool_retry_after_without_appending_history() {
        RecordingStore store = new RecordingStore();
        store.job = Optional.of(new ConversationStore.MessageJobProjection(store.claim.messageJobId(), store.claim.sessionId(),
                com.java.system.sessionagent.conversation.domain.JobStatus.WORKING, 0, 0, Optional.empty()));
        ModelDecision.UseTool request = new ModelDecision.UseTool(
                "catalog-call", new ToolName("list_repositories"), "{}", MODEL_CONTEXT);
        ScriptedModel model = new ScriptedModel(store, request);
        ToolDefinition definition = new ToolDefinition(new ToolName("list_repositories"), "v1", "catalog", "{\"type\":\"object\"}", ToolKind.CATALOG);
        DirectToolRegistry registry = new DirectToolRegistry(List.of(new ToolRegistration<>(definition, Object.class,
                ignored -> { throw ToolExecutionFailure.semanticIndexUnavailable(Optional.of(java.time.Duration.ofSeconds(61))); })));
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        MessageJobService service = new MessageJobService(store, model, registry,
                Clock.fixed(now, ZoneOffset.UTC),
                new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(3, java.time.Duration.ofSeconds(60)), telemetry);

        service.process(store.claim, () -> true);

        assertThat(store.scheduledAt).contains(now.plusSeconds(60));
        assertThat(store.feedbackMessages).isEmpty();
        assertThat(store.toolMessages).isEmpty();
        verify(telemetry).tool("list_repositories", "SEMANTIC_INDEX_UNAVAILABLE", Optional.empty(), Optional.empty());
        verify(telemetry).retry("TOOL", java.time.Duration.ofSeconds(60));
    }

    @Test
    void capsExponentialRetryDelayForAHighDurableRetryCount() {
        RecordingStore store = new RecordingStore();
        store.job = Optional.of(new ConversationStore.MessageJobProjection(store.claim.messageJobId(), store.claim.sessionId(),
                com.java.system.sessionagent.conversation.domain.JobStatus.WORKING, 63, 0, Optional.empty()));
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        MessageJobService service = new MessageJobService(store,
                model((request, usageObserver) -> { throw ModelCallFailure.transientFailure(); }), new DirectToolRegistry(List.of()),
                Clock.fixed(now, ZoneOffset.UTC),
                new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(64, java.time.Duration.ofSeconds(60)),
                mock(ConversationTelemetry.class));

        assertThatCode(() -> service.process(store.claim, () -> true)).doesNotThrowAnyException();

        assertThat(store.scheduledAt).contains(now.plusSeconds(60));
    }

    @Test
    void preserves_tool_details_when_transient_tool_retries_are_exhausted() {
        RecordingStore store = new RecordingStore();
        store.job = Optional.of(new ConversationStore.MessageJobProjection(store.claim.messageJobId(), store.claim.sessionId(),
                com.java.system.sessionagent.conversation.domain.JobStatus.WORKING, 3, 0, Optional.empty()));
        ModelDecision.UseTool toolRequest = new ModelDecision.UseTool(
                "tool-call", new ToolName("list_repositories"), "{\"repositoryId\":\"payment-service\"}", MODEL_CONTEXT);
        ScriptedModel model = new ScriptedModel(store, toolRequest);
        ToolDefinition definition = new ToolDefinition(new ToolName("list_repositories"), "v1", "catalog", "{\"type\":\"object\"}", ToolKind.CATALOG);
        DirectToolRegistry registry = new DirectToolRegistry(List.of(new ToolRegistration<>(definition, Object.class,
                ignored -> { throw ToolExecutionFailure.semanticIndexUnavailable(Optional.empty()); })));
        MessageJobService service = new MessageJobService(store, model, registry,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackMessages).singleElement().satisfies(feedback -> {
            assertThat(feedback.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
            assertThat(feedback.terminal()).isTrue();
            assertThat(feedback.modelCallId()).contains("tool-call");
            assertThat(feedback.toolName()).contains("list_repositories");
            assertThat(feedback.rejectedArguments()).contains("{\"repositoryId\":\"payment-service\"}");
        });
        assertThat(store.scheduledAt).isEmpty();
    }

    @Test
    void records_context_too_large_as_terminal_feedback() {
        RecordingStore store = new RecordingStore();
        ConversationModel model = model((request, usageObserver) -> { throw ModelCallFailure.contextTooLarge(); });
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackMessages).singleElement().satisfies(feedback -> {
            assertThat(feedback.code()).isEqualTo("CONTEXT_TOO_LARGE");
            assertThat(feedback.terminal()).isTrue();
        });
    }

    @Test
    void records_a_database_contract_error_when_history_loading_breaks_but_feedback_is_available() {
        RecordingStore store = new RecordingStore();
        store.loadFailure = Optional.of(ConversationStoreFailure.contract(new IllegalStateException("bad persisted row")));
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        MessageJobService service = new MessageJobService(store,
                model((request, usageObserver) -> { throw new AssertionError("model must not be called"); }), new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC),
                new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(3, java.time.Duration.ofSeconds(60)), telemetry);

        service.process(store.claim, () -> true);

        assertThat(store.feedbackMessages).singleElement().satisfies(feedback -> {
            assertThat(feedback.code()).isEqualTo("DATABASE_CONTRACT_ERROR");
            assertThat(feedback.terminal()).isTrue();
        });
        verify(telemetry).feedback("DATABASE_CONTRACT_ERROR");
    }

    @Test
    void retries_a_transient_store_failure_without_writing_conversation_history() {
        RecordingStore store = new RecordingStore();
        store.job = Optional.of(new ConversationStore.MessageJobProjection(store.claim.messageJobId(), store.claim.sessionId(),
                com.java.system.sessionagent.conversation.domain.JobStatus.WORKING, 0, 0, Optional.empty()));
        store.loadFailure = Optional.of(ConversationStoreFailure.transientFailure(new IllegalStateException("connection lost")));
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        MessageJobService service = new MessageJobService(store,
                model((request, usageObserver) -> { throw new AssertionError("model must not be called"); }), new DirectToolRegistry(List.of()),
                Clock.fixed(now, ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.scheduledAt).contains(now.plusSeconds(1));
        assertThat(store.toolMessages).isEmpty();
        assertThat(store.feedbackMessages).isEmpty();
    }

    @Test
    void turns_a_transient_store_failure_after_the_durable_call_limit_into_terminal_dependency_feedback() {
        RecordingStore store = new RecordingStore();
        store.job = Optional.of(new ConversationStore.MessageJobProjection(store.claim.messageJobId(), store.claim.sessionId(),
                com.java.system.sessionagent.conversation.domain.JobStatus.WORKING, 0, 12, Optional.empty()));
        store.loadFailure = Optional.of(ConversationStoreFailure.transientFailure(new IllegalStateException("connection lost")));
        MessageJobService service = new MessageJobService(store,
                model((request, usageObserver) -> { throw new AssertionError("model must not be called"); }), new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackCodes).containsExactly("DEPENDENCY_UNAVAILABLE");
        assertThat(store.feedbackMessages).singleElement().extracting(FeedbackMessage::terminal).isEqualTo(true);
        assertThat(store.scheduledAt).isEmpty();
    }

    @Test
    void returns_safely_when_storage_contract_recovery_feedback_is_stale() {
        RecordingStore store = new RecordingStore();
        store.loadFailure = Optional.of(ConversationStoreFailure.contract(new IllegalStateException("bad persisted row")));
        store.feedbackFailure = Optional.of(new StaleWorkClaimException());
        MessageJobService service = new MessageJobService(store,
                model((request, usageObserver) -> { throw new AssertionError("model must not be called"); }), new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        assertThatCode(() -> service.process(store.claim, () -> true)).doesNotThrowAnyException();

        assertThat(store.feedbackMessages).isEmpty();
    }

    @Test
    void sanitizes_an_unexpected_tool_executor_failure_into_terminal_feedback() {
        RecordingStore store = new RecordingStore();
        store.seedCatalog();
        ScriptedModel model = new ScriptedModel(store,
                new ModelDecision.UseTool("catalog-call", new ToolName("list_repositories"), "{}", MODEL_CONTEXT));
        ToolDefinition definition = new ToolDefinition(new ToolName("list_repositories"), "v1", "catalog", "{\"type\":\"object\"}", ToolKind.CATALOG);
        DirectToolRegistry registry = new DirectToolRegistry(List.of(new ToolRegistration<>(definition, Object.class,
                ignored -> { throw new IllegalStateException("provider secret"); })));
        MessageJobService service = new MessageJobService(store, model, registry,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

        service.process(store.claim, () -> true);

        assertThat(store.feedbackMessages).singleElement().satisfies(feedback -> {
            assertThat(feedback.code()).isEqualTo("DEPENDENCY_INVALID_RESPONSE");
            assertThat(feedback.terminal()).isTrue();
            assertThat(feedback.message()).doesNotContain("provider secret");
        });
        assertThat(store.terminalFeedbackWritten).isTrue();
    }

    @Test
    void emitsAnInvalidResponseToolOutcomeWhenEnvelopeValidationRejectsToolData() {
        RecordingStore store = new RecordingStore();
        ScriptedModel model = new ScriptedModel(store,
                new ModelDecision.UseTool("catalog-call", new ToolName("list_repositories"), "{}", MODEL_CONTEXT));
        DirectToolRegistry registry = new DirectToolRegistry(List.of(
                registration("list_repositories", ToolKind.CATALOG, Optional.empty(), Optional.empty(), "not-json")));
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        MessageJobService service = new MessageJobService(store, model, registry,
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC),
                new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(3, java.time.Duration.ofSeconds(60)), telemetry);

        service.process(store.claim, () -> true);

        assertThat(store.feedbackCodes).containsExactly("DEPENDENCY_INVALID_RESPONSE");
        verify(telemetry).tool("list_repositories", "INVALID_RESPONSE", Optional.empty(), Optional.empty());
        verify(telemetry).feedback("DEPENDENCY_INVALID_RESPONSE");
    }

    @Test
    void emits_correlated_model_events_without_sensitive_tool_arguments() {
        RecordingStore store = new RecordingStore();
        ConversationModel model = model((request, usageObserver) -> {
            usageObserver.accept(new com.java.system.sessionagent.conversation.domain.ModelUsage(5, 4, 9, true));
            return new ModelDecision.UseTool(
                    "tool-call", new ToolName("list_repositories"), "{\"apiKey\":\"runtime-secret\"}", MODEL_CONTEXT);
        });
        MessageJobService service = new MessageJobService(store, model, new DirectToolRegistry(List.of()),
                Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));
        ListAppender<ILoggingEvent> appender = attachAppender(MessageJobService.class);

        try {
            service.process(store.claim, () -> store.calls < 2);

            assertThat(appender.list).extracting(ILoggingEvent::getMessage)
                    .contains("model_call_started sessionId={} messageJobId={} ordinal={} phase={} historyCount={} visibleToolCount={}",
                            "model_call_usage sessionId={} messageJobId={} ordinal={} usageAvailable={} promptTokens={} completionTokens={} totalTokens={}",
                            "model_call_decision sessionId={} messageJobId={} ordinal={} phase={} decisionCategory={}");
            assertThat(logTemplatesAndArguments(appender)).doesNotContain("runtime-secret", "apiKey", "{\"apiKey\"");
        } finally {
            detachAppender(MessageJobService.class, appender);
        }
    }

    private static ToolRegistration<Object> registration(
            String name, ToolKind kind, Optional<String> repositoryId, Optional<String> revision, String data) {
        ToolDefinition definition = new ToolDefinition(new ToolName(name), "v1", name, "{\"type\":\"object\"}", kind);
        return new ToolRegistration<>(definition, Object.class,
                ignored -> new ToolResult(repositoryId, revision, data));
    }

    private static ToolRegistration<Object> countedRegistration(String name, ToolKind kind, AtomicInteger executions) {
        ToolDefinition definition = new ToolDefinition(new ToolName(name), "v1", name, "{\"type\":\"object\"}", kind);
        return new ToolRegistration<>(definition, Object.class, ignored -> {
            executions.incrementAndGet();
            return new ToolResult(Optional.of("payment-service"), Optional.of("rev-a"), "{\"members\":[]}");
        });
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> rejectedCatalogArguments() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("{not-json}", "INVALID_TOOL_INPUT"),
                org.junit.jupiter.params.provider.Arguments.of("{\"repositoryId\":\"" + "界".repeat(22_000) + "\"}", "TOOL_INPUT_TOO_LARGE"));
    }

    private static ListAppender<ILoggingEvent> attachAppender(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(Class<?> type, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static String logTemplatesAndArguments(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().flatMap(event -> Stream.concat(Stream.of(event.getMessage()),
                        Arrays.stream(event.getArgumentArray()).map(String::valueOf)))
                .collect(Collectors.joining("\n"));
    }

    private static ConversationModel model(PlanAction planAction) {
        return model(planAction, (request, usageObserver) -> {
            throw new AssertionError("reply must not be called");
        });
    }

    private static ConversationModel model(PlanAction planAction, ReplyAction replyAction) {
        return new ConversationModel() {
            @Override
            public ModelDecision plan(com.java.system.sessionagent.conversation.domain.ModelRequest request,
                                      java.util.function.Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver) {
                return planAction.plan(request, usageObserver);
            }

            @Override
            public String reply(com.java.system.sessionagent.conversation.domain.ReplyRequest request,
                                        java.util.function.Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver) {
                return replyAction.reply(request, usageObserver);
            }
        };
    }

    @FunctionalInterface
    private interface PlanAction {
        ModelDecision plan(com.java.system.sessionagent.conversation.domain.ModelRequest request,
                           java.util.function.Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver);
    }

    @FunctionalInterface
    private interface ReplyAction {
        String reply(com.java.system.sessionagent.conversation.domain.ReplyRequest request,
                             java.util.function.Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver);
    }

    private static final class ScriptedModel implements ConversationModel {
        private final List<ModelDecision> decisions;
        private final RecordingStore store;
        private final Optional<String> scriptedReply;
        private final List<List<String>> snapshots = new java.util.ArrayList<>();
        private int index;

        private ScriptedModel(RecordingStore store, ModelDecision... decisions) {
            this.store = store;
            this.scriptedReply = Optional.empty();
            this.decisions = List.of(decisions);
        }

        private ScriptedModel(RecordingStore store, String reply, ModelDecision... decisions) {
            this.store = store;
            this.scriptedReply = Optional.of(reply);
            this.decisions = List.of(decisions);
        }

        @Override
        public ModelDecision plan(com.java.system.sessionagent.conversation.domain.ModelRequest request,
                                  java.util.function.Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver) {
            snapshots.add(request.toolSnapshot().definitions().stream().map(definition -> definition.name().value()).toList());
            if (index == decisions.size()) {
                index++;
                return new ModelDecision.AnswerReady();
            }
            return decisions.get(index++);
        }

        @Override
        public String reply(com.java.system.sessionagent.conversation.domain.ReplyRequest request,
                                    java.util.function.Consumer<com.java.system.sessionagent.conversation.domain.ModelUsage> usageObserver) {
            return scriptedReply.orElse("Answer");
        }
    }

    private static final class RecordingStore implements ConversationStore {
        private final MessageWorkClaim claim = new MessageWorkClaim(new com.java.system.sessionagent.conversation.domain.MessageJobId("job-1"), new SessionId("session-1"), "worker", 1,
                Instant.parse("2026-08-16T00:00:00Z"), Instant.parse("2026-08-16T00:01:00Z"));
        private final List<com.java.system.sessionagent.conversation.domain.ToolMessage> toolMessages = new java.util.ArrayList<>();
        private final List<String> feedbackCodes = new java.util.ArrayList<>();
        private final List<FeedbackMessage> feedbackMessages = new java.util.ArrayList<>();
        private String storedReplyText;
        private int calls;
        private Optional<MessageJobProjection> job = Optional.empty();
        private Optional<Instant> scheduledAt = Optional.empty();
        private Optional<ConversationStoreFailure> loadFailure = Optional.empty();
        private Optional<RuntimeException> feedbackFailure = Optional.empty();
        private boolean terminalFeedbackWritten;

        @Override public List<com.java.system.sessionagent.conversation.domain.SessionMessage> loadHistory(SessionId sessionId) { if (loadFailure.isPresent()) { throw loadFailure.get(); } java.util.List<com.java.system.sessionagent.conversation.domain.SessionMessage> history = new java.util.ArrayList<>(toolMessages); history.addAll(feedbackMessages); history.sort(java.util.Comparator.comparingLong(message -> message.sequence().value())); return history; }
        @Override public List<com.java.system.sessionagent.conversation.domain.SessionMessage> loadHistory(SessionId sessionId, com.java.system.sessionagent.conversation.domain.MessageJobId messageJobId) { return loadHistory(sessionId).stream().filter(message -> message.messageJobId().map(messageJobId::equals).orElse(true)).toList(); }
        @Override public java.util.OptionalInt reserveModelCall(MessageWorkClaim ignored, Instant now) { return java.util.OptionalInt.of(++calls); }
        @Override public com.java.system.sessionagent.conversation.domain.ToolMessage appendTool(MessageWorkClaim ignored, ResultId resultId, String modelCallId, String modelContext, ToolData data, Instant now) {
            com.java.system.sessionagent.conversation.domain.ToolMessage message = new com.java.system.sessionagent.conversation.domain.ToolMessage(claim.sessionId(), new SessionSequence(toolMessages.size() + 1), Optional.of(claim.messageJobId()), now, MessageRole.TOOL, resultId, modelCallId, modelContext, data.toolName(), data.toolVersion(), data.canonicalArguments(), data.repositoryId(), data.revision(), data.resultJson());
            toolMessages.add(message); return message;
        }
        private void seedCatalog() {
            toolMessages.add(new com.java.system.sessionagent.conversation.domain.ToolMessage(claim.sessionId(), new SessionSequence(1), Optional.of(claim.messageJobId()), claim.claimedAt(), MessageRole.TOOL,
                    new ResultId("catalog-result"), "catalog-call", MODEL_CONTEXT, "list_repositories", "v1", "{}", Optional.empty(), Optional.empty(), "{\"resultId\":\"catalog-result\",\"toolName\":\"list_repositories\",\"data\":{}}"));
        }
        private void seedSource() {
            toolMessages.add(new com.java.system.sessionagent.conversation.domain.ToolMessage(claim.sessionId(), new SessionSequence(1), Optional.of(claim.messageJobId()), claim.claimedAt(), MessageRole.TOOL,
                    new ResultId("source-result"), "source-call", MODEL_CONTEXT, "source", "v1", "{\"repositoryId\":\"payment-service\"}", Optional.of("payment-service"), Optional.of("rev-a"), "{\"resultId\":\"source-result\",\"toolName\":\"source\",\"repositoryId\":\"payment-service\",\"revision\":\"rev-a\",\"data\":{}}"));
        }
        @Override public com.java.system.sessionagent.conversation.domain.AssistantMessage appendAssistant(MessageWorkClaim ignored, String reply, Instant now) { storedReplyText = reply; return new com.java.system.sessionagent.conversation.domain.AssistantMessage(claim.sessionId(), new SessionSequence(3), Optional.of(claim.messageJobId()), now, MessageRole.ASSISTANT, reply); }
        @Override public Optional<ResultProjection> readResult(ResultId resultId) { return toolMessages.stream().filter(message -> message.resultId().equals(resultId)).findFirst().map(message -> new ResultProjection(message.resultId(), message.sessionId(), message.toolName(), message.toolVersion(), message.arguments(), message.repositoryId(), message.revision(), message.resultJson())); }
        @Override public com.java.system.sessionagent.conversation.domain.MessageReceipt receive(com.java.system.sessionagent.conversation.domain.IncomingMessage message) { throw new UnsupportedOperationException(); }
        @Override public Optional<MessageWorkClaim> claimNext(String workerId, java.time.Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public boolean extendClaim(MessageWorkClaim claim, java.time.Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.FeedbackMessage appendFeedback(MessageWorkClaim claim, String code, String message, boolean terminal, Optional<String> modelCallId, Optional<String> toolName, Optional<String> rejectedArguments, Optional<String> modelContext, Instant createdAt) { if (feedbackFailure.isPresent()) { throw feedbackFailure.get(); } feedbackCodes.add(code); terminalFeedbackWritten = terminal; FeedbackMessage feedback = new FeedbackMessage(claim.sessionId(), new SessionSequence(toolMessages.size() + feedbackCodes.size() + 1), Optional.of(claim.messageJobId()), createdAt, MessageRole.FEEDBACK, code, message, terminal, modelCallId, toolName, rejectedArguments, modelContext); feedbackMessages.add(feedback); return feedback; }
        @Override public boolean scheduleRetry(MessageWorkClaim claim, java.time.Duration retryDelay) { scheduledAt = Optional.of(claim.claimedAt().plus(retryDelay)); return true; }
        @Override public Optional<MessageJobProjection> readJob(com.java.system.sessionagent.conversation.domain.MessageJobId messageJobId) { return job; }
    }
}
