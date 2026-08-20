package com.java.system.sessionagent.acceptance;

import com.java.system.sessionagent.conversation.application.ConversationMessageService;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.AssistantReply;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.RevisionLookup;
import com.java.system.sessionagent.semantic.domain.RepositoryId;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAcceptanceTest {

    @Test
    void answers_payment_methods_from_a_citeable_payment_service_result() {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime()) {
            MessageReceipt receipt = runtime.receive("payment-session", "alice", "payment-1", "Which payment methods are supported?");
            runtime.process(receipt);

            AssistantMessage reply = runtime.reply(receipt);
            assertThat(reply.citations()).hasSize(1);
            assertThat(runtime.store.readResult(reply.citations().getFirst())).get().extracting(ConversationStore.ResultProjection::citeable).isEqualTo(true);
            assertThat(runtime.store.toolMessages(receipt.messageJobId())).anySatisfy(message -> {
                assertThat(message.repositoryId()).contains("payment-service");
                assertThat(message.resultJson()).contains("credit card", "bank transfer", "wallet");
            });
            assertThat(runtime.semantic.calls()).anySatisfy(call -> assertThat(call.path()).isEqualTo("/v1/repositories/payment-service/entry-points"));
        }
    }

    @Test
    void states_that_the_runtime_fee_value_is_unavailable_while_citing_json_settings_evidence() {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime()) {
            MessageReceipt receipt = runtime.receive("fee-session", "alice", "fee-1", "What is the runtime fee?");
            runtime.process(receipt);

            AssistantMessage reply = runtime.reply(receipt);
            ToolMessage cited = runtime.citedTool(reply);
            assertThat(reply.message()).contains("current runtime value is unavailable");
            assertThat(cited.resultJson()).contains("formula is loaded from JSON settings");
            assertThat(cited.repositoryId()).contains("payment-service");
        }
    }

    @Test
    void reports_absent_bnpl_only_after_citeable_typed_repository_queries() {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime()) {
            MessageReceipt receipt = runtime.receive("bnpl-session", "alice", "bnpl-1", "Is BNPL supported?");
            runtime.process(receipt);

            AssistantMessage reply = runtime.reply(receipt);
            List<ToolMessage> sourceMessages = runtime.store.toolMessages(receipt.messageJobId()).stream().filter(ToolMessage::citeable).toList();
            assertThat(reply.message()).contains("No BNPL behavior was found");
            assertThat(sourceMessages).hasSizeGreaterThanOrEqualTo(2);
            assertThat(runtime.semantic.calls()).anySatisfy(call -> assertThat(call.path()).isEqualTo("/v1/api-routes/lookup"));
            assertThat(runtime.semantic.calls()).filteredOn(call -> call.path().equals("/v1/api-routes/lookup"))
                    .singleElement().satisfies(call -> assertThat(call.body()).contains("\"repoId\":\"payment-service\"", "\"expectedRevision\":\"payment-revision-1\""));
            assertThat(runtime.citedTool(reply).resultJson()).contains("NOT_FOUND");
        }
    }

    @Test
    void answers_cancellation_and_refund_after_cross_repository_citeable_queries() {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime()) {
            MessageReceipt receipt = runtime.receive("refund-session", "alice", "refund-1", "How do cancellation and refund work?");
            runtime.process(receipt);

            List<ToolMessage> sources = runtime.store.toolMessages(receipt.messageJobId()).stream().filter(ToolMessage::citeable).toList();
            assertThat(sources).extracting(message -> message.repositoryId().orElseThrow()).containsExactlyInAnyOrder("order-service", "payment-service");
            assertThat(sources).allSatisfy(message -> assertThat(message.arguments()).contains("\"repositoryId\":\"" + message.repositoryId().orElseThrow() + "\""));
            assertThat(runtime.semantic.calls()).filteredOn(call -> call.path().endsWith("/entry-points"))
                    .extracting(FakeSemanticService.Call::path)
                    .contains("/v1/repositories/order-service/entry-points", "/v1/repositories/payment-service/entry-points");
            AssistantMessage reply = runtime.reply(receipt);
            assertThat(reply.citations()).containsExactlyInAnyOrderElementsOf(sources.stream().map(ToolMessage::resultId).toList());
            assertThat(reply.citations()).allSatisfy(citation -> assertThat(runtime.store.readResult(citation)).get()
                    .extracting(ConversationStore.ResultProjection::citeable).isEqualTo(true));
        }
    }

    @Test
    void creates_a_fresh_persisted_result_for_repeated_identical_successful_source_queries() {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime()) {
            MessageReceipt first = runtime.receive("repeat-session", "alice", "repeat-1", "Which payment methods are supported?");
            runtime.process(first);
            MessageReceipt second = runtime.receive("repeat-session", "alice", "repeat-2", "Which payment methods are supported?");
            runtime.process(second);

            ResultId firstResult = runtime.citedTool(runtime.reply(first)).resultId();
            ResultId secondResult = runtime.citedTool(runtime.reply(second)).resultId();
            assertThat(secondResult).isNotEqualTo(firstResult);
            assertThat(runtime.semantic.calls()).filteredOn(call -> call.path().equals("/v1/repositories/payment-service/entry-points")).hasSize(2);
        }
    }

    @Test
    void refreshes_the_catalog_and_retries_the_exact_returned_repository_id_after_invalid_repository_feedback() {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime()) {
            MessageReceipt receipt = runtime.receive("retry-session", "alice", "retry-1", "Use an invalid repository to find payment methods.");
            runtime.process(receipt);

            assertThat(runtime.store.feedbackMessages(receipt.messageJobId())).extracting(FeedbackMessage::code).contains("UNKNOWN_REPOSITORY");
            assertThat(runtime.store.toolMessages(receipt.messageJobId())).filteredOn(message -> message.toolName().equals("list_repositories")).hasSize(2);
            assertThat(runtime.semantic.calls()).filteredOn(call -> call.path().equals("/v1/repositories")).hasSize(2);
            assertThat(runtime.semantic.calls()).anySatisfy(call -> assertThat(call.path()).isEqualTo("/v1/repositories/payment-service/entry-points"));
            assertThat(runtime.citedTool(runtime.reply(receipt)).repositoryId()).contains("payment-service");
        }
    }

    static final class AcceptanceRuntime implements AutoCloseable {
        private final FakeSemanticService semantic = new FakeSemanticService();
        private final InMemoryConversationStore store = new InMemoryConversationStore();
        private final FakeConversationModel model = new FakeConversationModel();
        private final ConversationMessageService intake = new ConversationMessageService(store);
        private final MessageJobService jobs;

        AcceptanceRuntime() {
            RestClient restClient = RestClient.builder().baseUrl(semantic.baseUrl()).build();
            SemanticRepositoryClient repositories = new SemanticRepositoryClient(restClient);
            DirectToolRegistry registry = new DirectToolRegistry(new SemanticToolProvider(repositories,
                    new SemanticSourceClient(restClient, repositories)).registrations());
            jobs = new MessageJobService(store, model, registry,
                    repositoryId -> new RevisionLookup.CurrentRevision(repositories.currentRevision(new RepositoryId(repositoryId)).value()),
                    Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));
        }

        MessageReceipt receive(String sessionKey, String participant, String sourceMessageId, String question) {
            return intake.receive(new IncomingMessage(sessionKey, participant, sourceMessageId, question));
        }

        void process(MessageReceipt receipt) {
            jobs.process(store.claim(receipt), () -> true);
        }

        AssistantMessage reply(MessageReceipt receipt) {
            return store.messages(receipt.sessionId()).stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                    .filter(message -> message.messageJobId().filter(receipt.messageJobId()::equals).isPresent()).findFirst().orElseThrow();
        }

        ToolMessage citedTool(AssistantMessage reply) {
            ResultId citation = reply.citations().getFirst();
            return store.messages(reply.sessionId()).stream().filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                    .filter(message -> message.resultId().equals(citation)).findFirst().orElseThrow();
        }

        List<ModelRequest> modelRequests() {
            return model.requests();
        }

        List<SessionMessage> history(SessionId sessionId) {
            return store.messages(sessionId);
        }

        @Override
        public void close() {
            semantic.close();
        }
    }

    static final class InMemoryConversationStore implements ConversationStore {
        private final Map<String, SessionId> sessions = new LinkedHashMap<>();
        private final Map<SessionId, List<SessionMessage>> messages = new LinkedHashMap<>();
        private final Map<MessageJobId, Job> jobs = new LinkedHashMap<>();
        private final Map<ResultId, ResultProjection> results = new LinkedHashMap<>();
        private long nextJob = 1;

        @Override
        public MessageReceipt receive(IncomingMessage incoming) {
            SessionId sessionId = sessions.computeIfAbsent(incoming.sessionKey(), SessionId::new);
            MessageJobId jobId = new MessageJobId("job-" + nextJob++);
            List<SessionMessage> history = messages.computeIfAbsent(sessionId, ignored -> new ArrayList<>());
            history.add(new UserMessage(sessionId, sequence(history), Optional.of(jobId), Instant.parse("2026-08-16T00:00:00Z"),
                    MessageRole.USER, incoming.participantId(), incoming.message()));
            jobs.put(jobId, new Job(sessionId, JobStatus.PENDING, 0, 0, Optional.empty()));
            return new MessageReceipt(sessionId, jobId);
        }

        MessageWorkClaim claim(MessageReceipt receipt) {
            Job job = jobs.get(receipt.messageJobId());
            jobs.put(receipt.messageJobId(), job.withStatus(JobStatus.WORKING));
            Instant claimTime = Instant.parse("2026-08-16T00:00:00Z");
            return new MessageWorkClaim(receipt.messageJobId(), receipt.sessionId(), "acceptance-worker", 1, claimTime, claimTime.plusSeconds(60));
        }

        List<SessionMessage> messages(SessionId sessionId) {
            return List.copyOf(messages.getOrDefault(sessionId, List.of()));
        }

        List<ToolMessage> toolMessages(MessageJobId jobId) {
            return messages.values().stream().flatMap(List::stream).filter(ToolMessage.class::isInstance).map(ToolMessage.class::cast)
                    .filter(message -> message.messageJobId().filter(jobId::equals).isPresent()).toList();
        }

        List<FeedbackMessage> feedbackMessages(MessageJobId jobId) {
            return messages.values().stream().flatMap(List::stream).filter(FeedbackMessage.class::isInstance).map(FeedbackMessage.class::cast)
                    .filter(message -> message.messageJobId().filter(jobId::equals).isPresent()).toList();
        }

        @Override
        public Optional<MessageWorkClaim> claimNext(String workerId, java.time.Duration leaseDuration) {
            return Optional.empty();
        }

        @Override
        public boolean extendClaim(MessageWorkClaim claim, java.time.Duration leaseDuration) {
            return true;
        }

        @Override
        public List<SessionMessage> loadHistory(SessionId sessionId) {
            return messages(sessionId);
        }

        @Override
        public List<SessionMessage> loadHistory(SessionId sessionId, MessageJobId messageJobId) {
            List<MessageJobId> visibleJobIds = new ArrayList<>();
            for (Map.Entry<MessageJobId, Job> entry : jobs.entrySet()) {
                if (entry.getKey().equals(messageJobId) || entry.getValue().status() == JobStatus.DONE) {
                    visibleJobIds.add(entry.getKey());
                }
                if (entry.getKey().equals(messageJobId)) {
                    break;
                }
            }
            return messages(sessionId).stream()
                    .filter(message -> message.messageJobId().filter(visibleJobIds::contains).isPresent())
                    .toList();
        }

        @Override
        public OptionalInt reserveModelCall(MessageWorkClaim claim, Instant now) {
            Job job = jobs.get(claim.messageJobId());
            Job reserved = job.withCalls(job.modelCallCount() + 1);
            jobs.put(claim.messageJobId(), reserved);
            return OptionalInt.of(reserved.modelCallCount());
        }

        @Override
        public ToolMessage appendTool(
                MessageWorkClaim claim,
                ResultId resultId,
                String modelCallId,
                String modelContext,
                ToolData data,
                Instant createdAt) {
            List<SessionMessage> history = messages.get(claim.sessionId());
            ToolMessage message = new ToolMessage(claim.sessionId(), sequence(history), Optional.of(claim.messageJobId()), createdAt,
                    MessageRole.TOOL, resultId, modelCallId, modelContext, data.toolName(), data.toolVersion(), data.canonicalArguments(), data.repositoryId(),
                    data.revision(), data.resultJson(), data.citeable());
            history.add(message);
            results.put(resultId, new ResultProjection(resultId, claim.sessionId(), data.toolName(), data.toolVersion(), data.canonicalArguments(),
                    data.repositoryId(), data.revision(), data.resultJson(), data.citeable()));
            return message;
        }

        @Override
        public FeedbackMessage appendFeedback(MessageWorkClaim claim, String code, String message, boolean terminal, Optional<String> modelCallId,
                                               Optional<String> toolName, Optional<String> rejectedArguments,
                                               Optional<String> modelContext, Instant createdAt) {
            List<SessionMessage> history = messages.get(claim.sessionId());
            FeedbackMessage feedback = new FeedbackMessage(claim.sessionId(), sequence(history), Optional.of(claim.messageJobId()), createdAt,
                    MessageRole.FEEDBACK, code, message, terminal, modelCallId, toolName, rejectedArguments, modelContext);
            history.add(feedback);
            return feedback;
        }

        @Override
        public AssistantMessage appendAssistant(MessageWorkClaim claim, AssistantReply reply, Instant createdAt) {
            List<SessionMessage> history = messages.get(claim.sessionId());
            AssistantMessage assistant = new AssistantMessage(claim.sessionId(), sequence(history), Optional.of(claim.messageJobId()), createdAt,
                    MessageRole.ASSISTANT, reply.message(), reply.citations());
            history.add(assistant);
            Job job = jobs.get(claim.messageJobId());
            jobs.put(claim.messageJobId(), new Job(job.sessionId(), JobStatus.DONE, job.retryCount(), job.modelCallCount(), Optional.of(assistant.sequence())));
            return assistant;
        }

        @Override
        public boolean scheduleRetry(MessageWorkClaim claim, java.time.Duration retryDelay) {
            Job job = jobs.get(claim.messageJobId());
            jobs.put(claim.messageJobId(), new Job(job.sessionId(), JobStatus.RETRY, job.retryCount() + 1, job.modelCallCount(), job.replySequence()));
            return true;
        }

        @Override
        public Optional<MessageJobProjection> readJob(MessageJobId messageJobId) {
            Job job = jobs.get(messageJobId);
            return Optional.ofNullable(job).map(value -> new MessageJobProjection(messageJobId, value.sessionId(), value.status(),
                    value.retryCount(), value.modelCallCount(), value.replySequence()));
        }

        @Override
        public Optional<ResultProjection> readResult(ResultId resultId) {
            return Optional.ofNullable(results.get(resultId));
        }

        private static SessionSequence sequence(List<SessionMessage> history) {
            return new SessionSequence(history.size() + 1L);
        }

        private record Job(SessionId sessionId, JobStatus status, int retryCount, int modelCallCount,
                           Optional<SessionSequence> replySequence) {
            private Job withStatus(JobStatus nextStatus) {
                return new Job(sessionId, nextStatus, retryCount, modelCallCount, replySequence);
            }

            private Job withCalls(int calls) {
                return new Job(sessionId, status, retryCount, calls, replySequence);
            }
        }
    }
}
