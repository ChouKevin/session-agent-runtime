package com.java.system.sessionagent.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.application.ConversationMessageService;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAcceptanceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void persists_a_direct_text_answer_without_a_tool_observation() {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime(List.of(text("Hello.")))) {
            MessageReceipt receipt = runtime.receive("direct-session", "alice", "direct-1", "Say hello.");
            runtime.process(receipt);

            assertThat(runtime.history(receipt.sessionId())).extracting(SessionMessage::role)
                    .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
            assertThat(runtime.reply(receipt).message()).isEqualTo("Hello.");
            assertThat(runtime.modelRequests()).singleElement().satisfies(request ->
                    assertThat(request.history()).extracting(SessionMessage::role).containsExactly(MessageRole.USER));
        }
    }

    @Test
    void persists_repository_catalog_then_source_observation_before_the_final_answer() throws Exception {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime(List.of(
                tools(tool("list_repositories", "{}")),
                tools(tool("codebase_list_entry_points", paymentEntryPointsInput())),
                text("Credit card, bank transfer, and wallet are supported.")))) {
            MessageReceipt receipt = runtime.receive("payment-session", "alice", "payment-1", "Which payment methods are supported?");
            runtime.process(receipt);

            List<SessionMessage> history = runtime.history(receipt.sessionId());
            assertThat(history).extracting(SessionMessage::role)
                    .containsExactly(MessageRole.USER, MessageRole.TOOL, MessageRole.TOOL, MessageRole.ASSISTANT);
            List<ToolObservation> observations = runtime.toolObservations(receipt);
            assertObservation(observations.get(0), 2, "list_repositories", "{}", repositoryCatalogOutput());
            assertObservation(observations.get(1), 3, "codebase_list_entry_points", paymentEntryPointsInput(), paymentEntryPointsOutput());
            assertThat(runtime.reply(receipt).message()).contains("Credit card");
            assertThat(runtime.modelRequests()).extracting(request -> request.history().size()).containsExactly(1, 2, 3);
            List<SessionMessage> catalogRequest = runtime.modelRequests().get(1).history();
            assertUser(catalogRequest.get(0), 1, "alice", "Which payment methods are supported?");
            assertObservation((ToolObservation) catalogRequest.get(1), 2, "list_repositories", "{}", repositoryCatalogOutput());
            List<SessionMessage> laterRequest = runtime.modelRequests().getLast().history();
            assertUser(laterRequest.get(0), 1, "alice", "Which payment methods are supported?");
            assertObservation((ToolObservation) laterRequest.get(1), 2, "list_repositories", "{}", repositoryCatalogOutput());
            assertObservation((ToolObservation) laterRequest.get(2), 3, "codebase_list_entry_points", paymentEntryPointsInput(), paymentEntryPointsOutput());
        }
    }

    @Test
    void commits_an_ordered_two_tool_batch_before_the_next_model_request() throws Exception {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime(List.of(
                new ModelReply.UseTools(Optional.of("I will inspect both services."), List.of(
                        tool("codebase_list_entry_points", paymentEntryPointsInput()),
                        tool("codebase_list_entry_points", orderEntryPointsInput()))),
                text("Cancellation is implemented before refund handling.")))) {
            MessageReceipt receipt = runtime.receive("batch-session", "alice", "batch-1", "Compare cancellation and payment handling.");
            runtime.process(receipt);

            List<SessionMessage> history = runtime.history(receipt.sessionId());
            assertThat(history).extracting(SessionMessage::role).containsExactly(
                    MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.TOOL, MessageRole.ASSISTANT);
            List<ToolObservation> observations = runtime.toolObservations(receipt);
            assertObservation(observations.get(0), 3, "codebase_list_entry_points", paymentEntryPointsInput(), paymentEntryPointsOutput());
            assertObservation(observations.get(1), 4, "codebase_list_entry_points", orderEntryPointsInput(), orderEntryPointsOutput());
            List<SessionMessage> laterRequest = runtime.modelRequests().get(1).history();
            assertUser(laterRequest.get(0), 1, "alice", "Compare cancellation and payment handling.");
            assertAssistant(laterRequest.get(1), 2, "I will inspect both services.");
            assertObservation((ToolObservation) laterRequest.get(2), 3, "codebase_list_entry_points", paymentEntryPointsInput(), paymentEntryPointsOutput());
            assertObservation((ToolObservation) laterRequest.get(3), 4, "codebase_list_entry_points", orderEntryPointsInput(), orderEntryPointsOutput());
        }
    }

    @Test
    void reports_runtime_only_fee_data_as_unavailable_without_inventing_a_value() throws Exception {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime(List.of(
                tools(tool("codebase_list_entry_points", paymentEntryPointsInput())),
                text("The source defines a JSON-configured formula, but the current database/API fee value is unavailable.")))) {
            MessageReceipt receipt = runtime.receive("fee-session", "alice", "fee-1", "What is the runtime fee?");
            runtime.process(receipt);

            assertThat(runtime.reply(receipt).message()).contains("database/API fee value is unavailable");
            assertThat(runtime.reply(receipt).message()).doesNotContain("10%");
            List<ToolObservation> observations = runtime.toolObservations(receipt);
            assertObservation(observations.getFirst(), 2, "codebase_list_entry_points", paymentEntryPointsInput(), paymentEntryPointsOutput());
            List<SessionMessage> laterRequest = runtime.modelRequests().getLast().history();
            assertUser(laterRequest.get(0), 1, "alice", "What is the runtime fee?");
            assertObservation((ToolObservation) laterRequest.get(1), 2, "codebase_list_entry_points", paymentEntryPointsInput(), paymentEntryPointsOutput());
        }
    }

    @Test
    void reports_absent_bnpl_narrowly_as_not_found_in_inspected_code() throws Exception {
        try (AcceptanceRuntime runtime = new AcceptanceRuntime(List.of(
                tools(tool("codebase_search_code_facts", "{\"repositoryId\":\"payment-service\",\"revision\":\"payment-revision-1\",\"query\":\"BNPL\"}")),
                text("BNPL behavior was not found in the inspected code.")))) {
            MessageReceipt receipt = runtime.receive("bnpl-session", "alice", "bnpl-1", "Is BNPL supported?");
            runtime.process(receipt);

            assertThat(runtime.reply(receipt).message()).isEqualTo("BNPL behavior was not found in the inspected code.");
            List<ToolObservation> observations = runtime.toolObservations(receipt);
            assertObservation(observations.getFirst(), 2, "codebase_search_code_facts", bnplSearchInput(), bnplSearchOutput());
            assertThat(isCompleteNegativeCodeSearch(readJson(observations.getFirst().output()))).isTrue();
            List<SessionMessage> laterRequest = runtime.modelRequests().getLast().history();
            assertUser(laterRequest.get(0), 1, "alice", "Is BNPL supported?");
            assertObservation((ToolObservation) laterRequest.get(1), 2, "codebase_search_code_facts", bnplSearchInput(), bnplSearchOutput());
        }
    }

    @Test
    void does_not_certify_code_limited_absence_when_search_coverage_has_issues() throws Exception {
        JsonNode incompleteSearch = readJson("""
                {"data":{"totalCount":0,"hasMore":false,"coverage":{"issues":["partial-index"]},"facts":[]},
                 "repositoryId":"payment-service","revision":"payment-revision-1"}
                """);

        assertThat(isCompleteNegativeCodeSearch(incompleteSearch)).isFalse();
    }

    private static void assertUser(SessionMessage message, long sequence, String participant, String text) {
        assertThat(message).isInstanceOf(UserMessage.class);
        UserMessage user = (UserMessage) message;
        assertThat(user.sequence().value()).isEqualTo(sequence);
        assertThat(user.participantId()).isEqualTo(participant);
        assertThat(user.message()).isEqualTo(text);
    }

    private static void assertAssistant(SessionMessage message, long sequence, String text) {
        assertThat(message).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistant = (AssistantMessage) message;
        assertThat(assistant.sequence().value()).isEqualTo(sequence);
        assertThat(assistant.message()).isEqualTo(text);
    }

    private static void assertObservation(
            ToolObservation observation, long sequence, String toolName, String input, String output) throws Exception {
        assertThat(observation.sequence().value()).isEqualTo(sequence);
        assertThat(observation.toolName()).isEqualTo(toolName);
        assertThat(observation.input()).isEqualTo(input);
        assertThat(readJson(observation.output())).isEqualTo(readJson(output));
    }

    private static boolean isCompleteNegativeCodeSearch(JsonNode output) {
        JsonNode data = output.required("data");
        JsonNode totalCount = data.required("totalCount");
        JsonNode hasMore = data.required("hasMore");
        JsonNode issues = data.required("coverage").required("issues");
        return totalCount.isIntegralNumber() && totalCount.intValue() == 0
                && hasMore.isBoolean() && !hasMore.booleanValue()
                && issues.isArray() && issues.isEmpty();
    }

    private static JsonNode readJson(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    private static String repositoryCatalogOutput() {
        return """
                {"repositories":[
                  {"repositoryId":"payment-service","revision":"payment-revision-1"},
                  {"repositoryId":"order-service","revision":"order-revision-1"}
                ]}
                """;
    }

    private static String paymentEntryPointsOutput() {
        return entryPointsOutput("payment-service", "payment-revision-1",
                "Payment methods include credit card, bank transfer, and wallet; fee formula is loaded from JSON settings.");
    }

    private static String orderEntryPointsOutput() {
        return entryPointsOutput("order-service", "order-revision-1",
                "Order cancellation is implemented before payment refund handling.");
    }

    private static String entryPointsOutput(String repositoryId, String revision, String description) {
        return """
                {"data":{"entryPoints":[{
                  "sourceType":{"javaType":{"packageName":"com.example","className":"ConversationFixture"},
                  "sourceFile":"src/main/java/com/example/ConversationFixture.java"},
                  "description":"%s","basePaths":[],"methods":[]
                }]},"repositoryId":"%s","revision":"%s"}
                """.formatted(description, repositoryId, revision);
    }

    private static String bnplSearchInput() {
        return "{\"repositoryId\":\"payment-service\",\"revision\":\"payment-revision-1\",\"query\":\"BNPL\"}";
    }

    private static String bnplSearchOutput() {
        return """
                {"data":{"totalCount":0,"hasMore":false,"coverage":{"issues":[]},"facts":[]},
                 "repositoryId":"payment-service","revision":"payment-revision-1"}
                """;
    }

    static ModelReply.Text text(String message) {
        return new ModelReply.Text(message);
    }

    static ModelReply.UseTools tools(ToolRequest... requests) {
        return new ModelReply.UseTools(Optional.empty(), List.of(requests));
    }

    static ToolRequest tool(String name, String input) {
        return new ToolRequest(new ToolName(name), input);
    }

    static String paymentEntryPointsInput() {
        return "{\"repositoryId\":\"payment-service\",\"revision\":\"payment-revision-1\"}";
    }

    static String orderEntryPointsInput() {
        return "{\"repositoryId\":\"order-service\",\"revision\":\"order-revision-1\"}";
    }

    static final class AcceptanceRuntime implements AutoCloseable {
        private final FakeSemanticService semantic = new FakeSemanticService();
        private final InMemoryConversationStore store = new InMemoryConversationStore();
        private final FakeConversationModel model;
        private final ConversationMessageService intake = new ConversationMessageService(store);
        private final MessageJobService jobs;

        AcceptanceRuntime(List<ModelReply> replies) {
            model = new FakeConversationModel(replies);
            RestClient restClient = RestClient.builder().baseUrl(semantic.baseUrl()).build();
            SemanticRepositoryClient repositories = new SemanticRepositoryClient(restClient);
            DirectToolRegistry registry = new DirectToolRegistry(new SemanticToolProvider(repositories,
                    new SemanticSourceClient(restClient)).registrations());
            jobs = new MessageJobService(store, model, registry,
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
                    .filter(message -> message.messageJobId().filter(receipt.messageJobId()::equals).isPresent()).reduce((first, second) -> second).orElseThrow();
        }

        List<ModelRequest> modelRequests() {
            return model.requests();
        }

        List<ToolObservation> toolObservations(MessageReceipt receipt) {
            return store.toolObservations(receipt.messageJobId());
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
        private long nextJob = 1;

        @Override
        public MessageReceipt receive(IncomingMessage incoming) {
            SessionId sessionId = sessions.computeIfAbsent(incoming.sessionKey(), SessionId::new);
            MessageJobId jobId = new MessageJobId("job-" + nextJob++);
            List<SessionMessage> history = messages.computeIfAbsent(sessionId, ignored -> new ArrayList<>());
            history.add(new UserMessage(sessionId, sequence(history), Optional.of(jobId), Instant.parse("2026-08-16T00:00:00Z"),
                    MessageRole.USER, incoming.participantId(), incoming.message()));
            jobs.put(jobId, new Job(sessionId, JobStatus.PENDING, 0, 0));
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

        List<ToolObservation> toolObservations(MessageJobId jobId) {
            return messages.values().stream().flatMap(List::stream).filter(ToolObservation.class::isInstance).map(ToolObservation.class::cast)
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
        public OptionalInt reserveModelCall(MessageWorkClaim claim, int maxModelCalls, Instant now) {
            Job job = jobs.get(claim.messageJobId());
            if (job.modelCallCount() >= maxModelCalls) {
                return OptionalInt.empty();
            }
            Job reserved = job.withCalls(job.modelCallCount() + 1);
            jobs.put(claim.messageJobId(), reserved);
            return OptionalInt.of(reserved.modelCallCount());
        }

        @Override
        public void append(MessageWorkClaim claim, MessageBatch batch, Instant createdAt) {
            List<SessionMessage> history = messages.get(claim.sessionId());
            for (MessageData data : batch.messages()) {
                if (data instanceof ToolObservationData observation) {
                    history.add(new ToolObservation(claim.sessionId(), sequence(history), Optional.of(claim.messageJobId()), createdAt,
                            MessageRole.TOOL, observation.observationId(), observation.toolName(), observation.input(), observation.output()));
                } else if (data instanceof AssistantData assistant) {
                    history.add(new AssistantMessage(claim.sessionId(), sequence(history), Optional.of(claim.messageJobId()), createdAt,
                            MessageRole.ASSISTANT, assistant.message()));
                } else if (data instanceof RuntimeData runtime) {
                    history.add(new RuntimeMessage(claim.sessionId(), sequence(history), Optional.of(claim.messageJobId()), createdAt,
                            MessageRole.RUNTIME, runtime.code(), runtime.message()));
                }
            }
            if (batch.jobUpdate() == JobUpdate.COMPLETE) {
                Job job = jobs.get(claim.messageJobId());
                jobs.put(claim.messageJobId(), new Job(job.sessionId(), JobStatus.DONE, job.retryCount(), job.modelCallCount()));
            }
        }

        @Override
        public boolean scheduleRetry(MessageWorkClaim claim, java.time.Duration retryDelay) {
            Job job = jobs.get(claim.messageJobId());
            jobs.put(claim.messageJobId(), new Job(job.sessionId(), JobStatus.RETRY, job.retryCount() + 1, job.modelCallCount()));
            return true;
        }

        @Override
        public Optional<MessageJobProjection> readJob(MessageJobId messageJobId) {
            Job job = jobs.get(messageJobId);
            return Optional.ofNullable(job).map(value -> new MessageJobProjection(messageJobId, value.sessionId(), value.status(),
                    value.retryCount(), value.modelCallCount()));
        }

        private static SessionSequence sequence(List<SessionMessage> history) {
            return new SessionSequence(history.size() + 1L);
        }

        private record Job(SessionId sessionId, JobStatus status, int retryCount, int modelCallCount) {
            private Job withStatus(JobStatus nextStatus) {
                return new Job(sessionId, nextStatus, retryCount, modelCallCount);
            }

            private Job withCalls(int calls) {
                return new Job(sessionId, status, retryCount, calls);
            }
        }
    }
}
