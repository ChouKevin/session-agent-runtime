package com.java.system.sessionagent.slack.adapter.postgres;

import com.java.system.sessionagent.slack.application.MessageHandler;
import com.java.system.sessionagent.slack.application.SlackDeliveryWorker;
import com.java.system.sessionagent.slack.domain.IncomingEvent;
import com.java.system.sessionagent.slack.domain.MessageSubmission;
import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryStatus;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;
import com.java.system.sessionagent.slack.domain.SlackEventOutcome;
import com.java.system.sessionagent.slack.domain.SlackIntakeClassification;
import com.java.system.sessionagent.slack.domain.SlackPostFailure;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.port.out.SlackWebApi;
import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessageSource;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresConversationStore;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackDeliveryPostgresIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway().clean();
        flyway().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void clean() {
        flyway().clean();
    }

    @Test
    void rediscovers_one_terminal_runtime_delivery_and_reclaims_it_after_its_lease_expires() {
        MessageReceipt receipt = completeSlackJobWithTerminalRuntime();
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore initialStore = new SlackPostgresDeliveryStore(dataSource());

        initialStore.discover();
        initialStore.discover();
        SlackDeliveryClaim expiredClaim = initialStore.claimNext("first-delivery-worker", Duration.ofSeconds(30), 5).orElseThrow();
        jdbcTemplate.update("update slack_delivery set locked_until = clock_timestamp() - interval '1 millisecond' where delivery_id = ?",
                expiredClaim.deliveryId());
        DatabaseInspectingSlackWebApi slack = new DatabaseInspectingSlackWebApi(jdbcTemplate);
        SlackDeliveryWorker recoveredWorker = new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), slack,
                new SlackDeliveryProperties(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(60), 5),
                "recovered-delivery-worker");

        assertThat(recoveredWorker.poll()).isTrue();

        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery", Integer.class)).isEqualTo(2);
        assertThat(slack.posts).containsExactly(new SlackPostRequest("C1", "1.000001", "Runtime terminal response."));
        assertThat(slack.claimWasCommittedBeforePost).isTrue();
        assertThat(jdbcTemplate.queryForObject("select status from slack_delivery where kind = 'ANSWER'", String.class)).isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject("select attempt_count from slack_delivery where kind = 'ANSWER'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select model_calls from message_job where message_job_id = ?", Integer.class,
                UUID.fromString(receipt.messageJobId().value()))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(4);
    }

    @Test
    void persists_an_exhausted_transient_delivery_as_queryable_failed_without_changing_its_message_job() {
        MessageReceipt receipt = completeSlackJobWithTerminalRuntime();
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        UUID deliveryId = answerDeliveryId();
        jdbcTemplate.update("update slack_delivery set attempt_count = 4 where delivery_id = ?", deliveryId);
        SlackWebApi failingSlack = request -> {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        };
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveryStore, failingSlack, new SlackDeliveryProperties(), "delivery-worker");

        assertThat(worker.poll()).isTrue();

        assertThat(deliveryStore.read(deliveryId)).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(delivery.attemptCount()).isEqualTo(5);
            assertThat(delivery.failureCategory()).contains(SlackDeliveryFailureCategory.TRANSIENT);
        });
        assertThat(jdbcTemplate.queryForObject("select model_calls from message_job where message_job_id = ?", Integer.class,
                UUID.fromString(receipt.messageJobId().value()))).isEqualTo(1);
    }

    @Test
    void terminalizes_an_expired_fifth_working_attempt_without_a_sixth_slack_call() {
        completeSlackJobWithTerminalRuntime();
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        UUID deliveryId = answerDeliveryId();
        jdbcTemplate.update("""
                update slack_delivery set status = 'WORKING', worker_id = 'stopped-worker',
                    locked_until = clock_timestamp() - interval '1 millisecond', claim_number = 1, attempt_count = 5
                where delivery_id = ?
                """, deliveryId);
        AtomicBoolean posted = new AtomicBoolean(false);
        SlackWebApi slack = request -> {
            posted.set(true);
            return "2.000001";
        };
        SlackDeliveryWorker recoveredWorker = new SlackDeliveryWorker(deliveryStore, slack, new SlackDeliveryProperties(),
                "recovered-delivery-worker");

        assertThat(recoveredWorker.poll()).isFalse();

        assertThat(posted.get()).isFalse();
        assertThat(deliveryStore.read(deliveryId)).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(delivery.attemptCount()).isEqualTo(5);
            assertThat(delivery.failureCategory()).contains(SlackDeliveryFailureCategory.TRANSIENT);
        });
    }

    @Test
    void does_not_claim_an_answer_while_its_queued_notice_remains_unfinished() {
        completeSlackJobWithTerminalRuntime();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        jdbcTemplate.update("""
                update slack_delivery set status = 'RETRY', next_attempt_at = clock_timestamp() + interval '1 hour'
                where kind = 'QUEUED'
                """);

        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).isEmpty();

        jdbcTemplate.update("""
                update slack_delivery set status = 'FAILED', failure_category = 'PERMANENT'
                where kind = 'QUEUED'
                """);
        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).hasValueSatisfying(claim ->
                assertThat(claim.kind().name()).isEqualTo("ANSWER"));
    }

    @Test
    void returns_safe_delivery_statuses_for_one_logical_source_in_creation_order() {
        MessageReceipt receipt = completeSlackJobWithTerminalRuntime();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();

        List<SlackDeliveryView> deliveries = deliveryStore.findBySource("T1", "C1", "1.000001");

        assertThat(deliveries).extracting(delivery -> delivery.kind().name()).containsExactly("QUEUED", "ANSWER");
        assertThat(deliveries).allSatisfy(delivery -> {
            assertThat(delivery.messageJobId()).contains(UUID.fromString(receipt.messageJobId().value()));
            assertThat(delivery.failureCategory()).isEmpty();
            assertThat(delivery.nextAttemptAt()).isNotNull();
        });
    }

    @Test
    void does_not_claim_a_later_answer_for_the_same_session_while_an_earlier_answer_retries() {
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
        MessageIntakePort messageIntake = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        PostgresMessageSubmission slackIntake = new PostgresMessageSubmission(dataSource(), messageIntake,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(slackIntake.receive(acceptedIntake("event-root", "1.000001", "1.000001")).outcome())
                .isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(slackIntake.receive(acceptedIntake("event-reply-one", "1.000001", "1.000002")).outcome())
                .isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(slackIntake.receive(acceptedIntake("event-reply-two", "1.000001", "1.000003")).outcome())
                .isEqualTo(SlackEventOutcome.ACCEPTED);
        completeNextWithTerminalRuntime(conversationStore);
        completeNextWithTerminalRuntime(conversationStore);
        completeNextWithTerminalRuntime(conversationStore);
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        jdbcTemplate.update("""
                update slack_delivery set status = 'SENT', slack_message_ts = '2.000000', sent_at = clock_timestamp()
                where kind = 'ANSWER' and message_ts = '1.000001'
                """);
        jdbcTemplate.update("""
                update slack_delivery set status = 'RETRY', next_attempt_at = clock_timestamp() + interval '1 hour'
                where kind = 'ANSWER' and message_ts = '1.000002'
                """);

        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).isEmpty();

        jdbcTemplate.update("""
                update slack_delivery set status = 'FAILED', failure_category = 'PERMANENT'
                where kind = 'ANSWER' and message_ts = '1.000002'
                """);
        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).hasValueSatisfying(claim ->
                assertThat(claim.postRequest().text()).isEqualTo("Runtime terminal response."));
    }

    @Test
    void claims_another_session_answer_while_a_previous_session_answer_retries() {
        completeSlackJobWithTerminalRuntime("1.000001");
        completeSlackJobWithTerminalRuntime("2.000001");
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        jdbcTemplate.update("""
                update slack_delivery set status = 'RETRY', next_attempt_at = clock_timestamp() + interval '1 hour'
                where kind = 'ANSWER' and message_ts = '1.000001'
                """);

        assertThat(deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5)).hasValueSatisfying(claim ->
                assertThat(claim.postRequest().rootThreadTs()).isEqualTo("2.000001"));
    }

    @Test
    void rolls_back_the_session_job_receipts_and_notice_when_notice_persistence_fails() {
        jdbcTemplate.execute("""
                create function reject_slack_delivery_insert() returns trigger language plpgsql as $$
                begin
                    raise exception 'delivery write rejected';
                end;
                $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_slack_delivery_insert before insert on slack_delivery
                for each row execute function reject_slack_delivery_insert()
                """);
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
        MessageIntakePort messageIntake = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        PostgresMessageSubmission slackIntake = new PostgresMessageSubmission(dataSource(), messageIntake,
                Clock.fixed(NOW, ZoneOffset.UTC));
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "slack:T1:C1:1.000001", "U1", "1.000001", "request");
        MessageSubmission intake = new MessageSubmission("event-rollback", "T1", "C1", "1.000001", "1.000001",
                SlackIntakeClassification.ACCEPTED, Optional.of(message));

        assertThatThrownBy(() -> slackIntake.receive(intake)).hasMessageContaining("delivery write rejected");

        assertThat(jdbcTemplate.queryForObject("select count(*) from conversation_session", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from message_job", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_thread_binding", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_message_receipt", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_event_receipt", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from slack_delivery", Integer.class)).isZero();
    }

    @Test
    void correlates_new_and_duplicate_slack_intake_through_model_processing_and_terminal_delivery() {
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
        MessageIntakePort messageIntake = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        MessageHandler adapter = new MessageHandler("UBOT",
                new PostgresMessageSubmission(dataSource(), messageIntake, Clock.fixed(NOW, ZoneOffset.UTC)));
        ListAppender<ILoggingEvent> intakeLogs = capture(MessageHandler.class);
        ListAppender<ILoggingEvent> modelLogs = capture(JobProcessor.class);
        ListAppender<ILoggingEvent> deliveryLogs = capture(SlackDeliveryWorker.class);
        IncomingEvent event = new IncomingEvent("event-lifecycle", "T1", "C1", "1.000001", "", "U1", "", "channel",
                "<@UBOT> hello", "", false, false);
        List<SlackPostRequest> posts = new java.util.ArrayList<>();
        try {
            assertThat(adapter.handle(event)).isEqualTo(SlackEventOutcome.ACCEPTED);
            assertThat(adapter.handle(event)).isEqualTo(SlackEventOutcome.ACCEPTED);
            MessageWorkClaim claim = conversationStore.claimNext("conversation-worker", Duration.ofSeconds(30)).orElseThrow();
            ConversationModel model = successfulModel();
            JobProcessor service = new JobProcessor(conversationStore, TestConfiguredModels.withModel(model), emptyToolCatalog(),
                    Clock.fixed(NOW, ZoneOffset.UTC), 3, new MessageJobRetryPolicy(3, Duration.ofSeconds(1)),
                    new NoOpConversationTelemetry());
            service.process(claim, () -> true);
            SlackDeliveryWorker deliveryWorker = new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), request -> {
                posts.add(request);
                return "2.000001";
            }, new SlackDeliveryProperties(), "delivery-worker");
            assertThat(deliveryWorker.poll()).isTrue();
            assertThat(deliveryWorker.poll()).isTrue();
        } finally {
            detach(MessageHandler.class, intakeLogs);
            detach(JobProcessor.class, modelLogs);
            detach(SlackDeliveryWorker.class, deliveryLogs);
        }

        List<Map<String, Object>> inbound = lifecycleEvents(intakeLogs, "slack_inbound");
        assertThat(inbound).hasSize(2);
        assertThat(inbound).extracting(values -> values.get("disposition"))
                .containsExactly("NEW_ACCEPTED", "DUPLICATE_ACCEPTED");
        Object sessionId = inbound.getFirst().get("sessionId");
        Object messageJobId = inbound.getFirst().get("messageJobId");
        assertThat(inbound.getLast()).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId);
        assertThat(lifecycleEvents(intakeLogs, "session_created")).hasSize(1).allSatisfy(values ->
                assertThat(values).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId));
        assertThat(lifecycleEvents(intakeLogs, "session_resolved")).hasSize(1).allSatisfy(values ->
                assertThat(values).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId));
        assertThat(lifecycleEvents(modelLogs, "model_request")).hasSize(1).allSatisfy(values ->
                assertThat(values).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId));
        assertThat(lifecycleEvents(modelLogs, "model_response")).hasSize(1).allSatisfy(values ->
                assertThat(values).containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId));
        assertThat(lifecycleEvents(deliveryLogs, "slack_delivery_attempt")).hasSize(2);
        assertThat(lifecycleEvents(deliveryLogs, "slack_delivery_attempt").getFirst())
                .containsEntry("kind", "QUEUED").containsEntry("messageJobId", messageJobId)
                .doesNotContainKey("sessionId");
        assertThat(lifecycleEvents(deliveryLogs, "slack_delivery_attempt").getLast())
                .containsEntry("kind", "ANSWER").containsEntry("sessionId", sessionId).containsEntry("messageJobId", messageJobId);
        assertThat(lifecycleEvents(deliveryLogs, "slack_delivery_sent")).hasSize(2);
        assertThat(posts).containsExactly(
                new SlackPostRequest("C1", "1.000001", "已收到，正在排隊處理。"),
                new SlackPostRequest("C1", "1.000001", "done"));
    }

    private static ConversationModel successfulModel() {
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("observability-test"), "fake", 1_000_000);
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
            public ModelCallResult respond(
                    ModelRequest request,
                    ModelCallReservation reservation,
                    Consumer<ModelUsage> usageObserver) {
                reservation.reserve();
                return new ModelCallResult(new ModelReply.Text("done"), Optional.empty());
            }
        };
    }

    private static ToolCatalog emptyToolCatalog() {
        return () -> new ToolSnapshot(List.of());
    }

    private static ListAppender<ILoggingEvent> capture(Class<?> source) {
        Logger logger = (Logger) LoggerFactory.getLogger(source);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> source, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(source);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<Map<String, Object>> lifecycleEvents(ListAppender<ILoggingEvent> appender, String name) {
        return appender.list.stream().map(SlackDeliveryPostgresIT::keyValues)
                .filter(values -> name.equals(values.get("event"))).toList();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private MessageReceipt completeSlackJobWithTerminalRuntime() {
        return completeSlackJobWithTerminalRuntime("1.000001");
    }

    private MessageReceipt completeSlackJobWithTerminalRuntime(String messageTs) {
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
        MessageIntakePort messageIntake = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        PostgresMessageSubmission slackIntake = new PostgresMessageSubmission(dataSource(), messageIntake, Clock.fixed(NOW, ZoneOffset.UTC));
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "slack:T1:C1:" + messageTs, "U1", messageTs,
                "request");
        MessageSubmission intake = new MessageSubmission("event-" + messageTs, "T1", "C1", messageTs, messageTs,
                SlackIntakeClassification.ACCEPTED, Optional.of(message));

        assertThat(slackIntake.receive(intake).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        MessageReceipt receipt = conversationStore.receive(message, TestConfiguredModels.withDefault("test").defaultSelection(),
                TestConfiguredModels.withDefault("test"));
        completeNextWithTerminalRuntime(conversationStore);
        return receipt;
    }

    private static MessageSubmission acceptedIntake(String eventId, String rootThreadTs, String messageTs) {
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "slack:T1:C1:" + rootThreadTs, "U1",
                "slack:T1:C1:" + messageTs, "request");
        return new MessageSubmission(eventId, "T1", "C1", rootThreadTs, messageTs, SlackIntakeClassification.ACCEPTED,
                Optional.of(message));
    }

    private static void completeNextWithTerminalRuntime(PostgresConversationStore conversationStore) {
        MessageWorkClaim claim = conversationStore.claimNext("conversation-worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(conversationStore.reserveModelCall(claim, 2, NOW)).hasValue(1);
        conversationStore.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.of("Intermediate tool-call text"), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "lookup", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "lookup", Map.of("result", "value")),
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "Runtime terminal response.")),
                ConversationStore.JobUpdate.COMPLETE), NOW);
    }

    private UUID answerDeliveryId() {
        return jdbcTemplate.queryForObject("select delivery_id from slack_delivery where kind = 'ANSWER'", UUID.class);
    }

    private void markQueuedNoticeSent() {
        jdbcTemplate.update("""
                update slack_delivery set status = 'SENT', slack_message_ts = '2.000000', sent_at = clock_timestamp()
                where kind = 'QUEUED'
                """);
    }

    private DriverManagerDataSource dataSource() {
        return dataSource;
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }

    private static final class DatabaseInspectingSlackWebApi implements SlackWebApi {

        private final JdbcTemplate jdbcTemplate;
        private final List<SlackPostRequest> posts = new java.util.ArrayList<>();
        private boolean claimWasCommittedBeforePost;

        private DatabaseInspectingSlackWebApi(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public String post(SlackPostRequest request) {
            claimWasCommittedBeforePost = "WORKING".equals(jdbcTemplate.queryForObject(
                    "select status from slack_delivery where kind = 'ANSWER'", String.class));
            posts.add(request);
            return "2.000001";
        }
    }
}
