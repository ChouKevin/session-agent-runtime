package com.java.system.sessionagent.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.application.ConversationMessageService;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessageSource;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.storage.PostgresConversationStore;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.request.RequestHeaders;
import com.slack.api.bolt.request.builtin.EventRequest;
import com.slack.api.bolt.response.Response;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackPostgresIntakePostgresIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        flyway().clean();
        flyway().migrate();
    }

    @AfterEach
    void clean() {
        flyway().clean();
    }

    @Test
    void atomically_persists_a_slack_binding_receipt_and_provider_neutral_message_job() {
        SlackPostgresRootIntake intake = intake();

        SlackEventOutcome outcome = intake.receive(rootIntake("T1", "C1", "1.000001"));
        SlackEventOutcome replayOutcome = intake.receive(rootIntake("T1", "C1", "1.000001"));

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        assertThat(outcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(replayOutcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(count(jdbcTemplate, "select count(*) from slack_thread_binding")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from session_message where role = 'USER'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(1);
    }

    @Test
    void ignores_an_unbound_thread_reply_without_creating_a_session_or_job() {
        SlackPostgresRootIntake intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

        intake.receive(replyIntake("event-unbound", "T1", "C1", "1.000001", "1.000002", "U2", "reply"));

        assertThat(count(jdbcTemplate, "select count(*) from conversation_session where source_type = 'slack'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isZero();
    }

    @Test
    void keeps_an_unbound_logical_reply_ignored_across_same_and_distinct_event_redelivery_after_binding() throws Exception {
        SlackPostgresRootIntake intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        SlackRootIntake unboundReply = replyIntake("event-unbound", "T1", "C1", "1.000001", "1.000002", "U2", "reply");

        SlackEventOutcome initial = intake.receive(unboundReply);
        intake.receive(rootIntake("T1", "C1", "1.000001"));
        SlackEventOutcome sameEventReplay = intake.receive(unboundReply);
        List<SlackEventOutcome> distinctEventReplays = concurrently(() -> intake.receive(replyIntake(
                "event-unbound-overlap-" + Thread.currentThread().threadId(), "T1", "C1", "1.000001", "1.000002", "U2", "reply")));

        assertThat(initial).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(sameEventReplay).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(distinctEventReplays).containsOnly(SlackEventOutcome.IGNORED);
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_message_receipt")).isEqualTo(2);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt")).isEqualTo(4);
    }

    @Test
    void acknowledges_and_persists_an_official_file_share_subtype_envelope_without_a_job() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", intake());
        SlackBoltSocketClient socketClient = new SlackBoltSocketClient(new SlackProperties("xapp-test", "xoxb-test", "UBOT",
                Duration.ofSeconds(1), Duration.ofSeconds(1)), adapter);
        App app = new App(AppConfig.builder().requestVerificationEnabled(false).build());
        socketClient.registerHandlers(app);
        EventRequest request = new EventRequest("""
                {
                  "type":"event_callback",
                  "event_id":"event-file-share",
                  "team_id":"T1",
                  "event":{"type":"message","subtype":"file_share","channel":"C1","channel_type":"channel",
                  "user":"U1","text":"private attachment caption","ts":"1.000001","files":[]}
                }
                """, new RequestHeaders(Map.of()));
        request.setSocketMode(true);

        Response response = app.run(request);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt where classification = 'UNSUPPORTED_CONTENT'"))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_message_receipt where classification = 'UNSUPPORTED_CONTENT'"))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isZero();
    }

    @Test
    void resolves_a_mixed_accepted_and_ignored_logical_race_to_one_committed_outcome() throws Exception {
        SlackPostgresRootIntake intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        SlackRootIntake accepted = rootIntake("T1", "C1", "1.000001");
        SlackRootIntake ignored = new SlackRootIntake("event-ignored-race", "T1", "C1", "1.000001", "1.000001",
                SlackIntakeClassification.UNSUPPORTED_CONTENT, java.util.Optional.empty());

        List<SlackEventOutcome> outcomes = concurrently(accepted, ignored, intake::receive);

        assertThat(outcomes).containsOnly(outcomes.getFirst());
        assertThat(count(jdbcTemplate, "select count(*) from slack_message_receipt")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt")).isEqualTo(2);
        if (outcomes.getFirst() == SlackEventOutcome.ACCEPTED) {
            assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isEqualTo(1);
            assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(1);
            assertThat(count(jdbcTemplate, """
                    select count(*) from slack_event_receipt
                    where classification = 'ACCEPTED' and correlation_id is not null
                    """)).isEqualTo(2);
        } else {
            assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isZero();
            assertThat(count(jdbcTemplate, "select count(*) from message_job")).isZero();
            assertThat(count(jdbcTemplate, """
                    select count(*) from slack_event_receipt
                    where classification = 'UNSUPPORTED_CONTENT' and correlation_id is null
                    """)).isEqualTo(2);
        }
    }

    @Test
    void acknowledges_and_persists_an_unregistered_candidate_human_subtype_envelope_without_a_job() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        SlackBoltSocketClient socketClient = socketClient();
        App app = appWithoutAuthorization(socketClient);
        EventRequest request = new EventRequest("""
                {
                  "type":"event_callback",
                  "event_id":"event-assistant-thread",
                  "team_id":"T1",
                  "event":{"type":"message","subtype":"assistant_app_thread","channel":"C1","channel_type":"channel",
                  "user":"U1","text":"private assistant thread text","ts":"1.000001"}
                }
                """, new RequestHeaders(Map.of()));
        request.setSocketMode(true);

        Response response = app.run(request);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt where classification = 'UNSUPPORTED_CONTENT'"))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_message_receipt where classification = 'UNSUPPORTED_CONTENT'"))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isZero();
    }

    @Test
    void acknowledges_a_canonical_deleted_envelope_without_previous_message_or_a_job() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        App app = appWithoutAuthorization(socketClient());
        EventRequest request = new EventRequest("""
                {
                  "type":"event_callback",
                  "event_id":"event-deleted",
                  "team_id":"T1",
                  "event":{"type":"message","subtype":"message_deleted","channel":"C1","channel_type":"channel",
                  "deleted_ts":"1.000001","event_ts":"1.000002","ts":"1.000002"}
                }
                """, new RequestHeaders(Map.of()));
        request.setSocketMode(true);

        Response response = app.run(request);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isZero();
    }

    @Test
    void shares_a_bound_thread_session_and_deduplicates_event_and_logical_message_identities() {
        SlackPostgresRootIntake intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

        intake.receive(rootIntake("T1", "C1", "1.000001"));
        SlackEventOutcome firstReply = intake.receive(replyIntake("event-reply-1", "T1", "C1", "1.000001", "1.000002", "U2", "one"));
        SlackEventOutcome replay = intake.receive(replyIntake("event-reply-1", "T1", "C1", "1.000001", "1.000002", "U2", "one"));
        SlackEventOutcome overlappingSubscription = intake.receive(replyIntake("event-reply-2", "T1", "C1", "1.000001", "1.000002", "U2", "one"));

        assertThat(firstReply).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(replay).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(overlappingSubscription).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(count(jdbcTemplate, "select count(*) from conversation_session where source_type = 'slack'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isEqualTo(2);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(2);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt")).isEqualTo(3);
        assertThat(count(jdbcTemplate, "select count(*) from slack_message_receipt")).isEqualTo(2);
    }

    @Test
    void orders_concurrent_bound_replies_by_session_sequence_and_claims_one_at_a_time() throws Exception {
        SlackPostgresRootIntake intake = intake();
        ConversationStore store = store();
        intake.receive(rootIntake("T1", "C1", "1.000001"));
        MessageWorkClaim rootClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(rootClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), Instant.parse("2026-09-06T00:00:01Z"));

        List<SlackEventOutcome> outcomes = concurrently(() -> {
            String suffix = Long.toString(Thread.currentThread().threadId());
            return intake.receive(replyIntake("event-" + suffix, "T1", "C1", "1.000001", "2.00000" + suffix,
                    "U" + suffix, "reply " + suffix));
        });

        assertThat(outcomes).containsOnly(SlackEventOutcome.ACCEPTED);
        MessageWorkClaim firstReply = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.claimNext("other-worker", Duration.ofSeconds(30))).isEmpty();
        store.append(firstReply, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), Instant.parse("2026-09-06T00:00:02Z"));
        ConversationStore reconstructedWorkerStore = store();
        assertThat(reconstructedWorkerStore.claimNext("other-worker", Duration.ofSeconds(30))).isPresent();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                select user_message_sequence from source_message
                where source_type = 'slack'
                order by user_message_sequence
                """, Long.class)).containsExactly(1L, 3L, 4L);
    }

    @Test
    void stores_ignored_receipts_without_text_or_raw_payload_columns() {
        SlackPostgresRootIntake intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

        SlackEventOutcome outcome = intake.receive(new SlackRootIntake("event-ignored", "T1", "C1", "1.000001", "1.000001",
                SlackIntakeClassification.BLANK, java.util.Optional.empty()));

        assertThat(outcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt where classification = 'BLANK'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, """
                select count(*) from slack_message_receipt
                where classification = 'BLANK' and session_id is null and message_job_id is null
                """)).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from session_message")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isZero();
        assertThat(count(jdbcTemplate, """
                select count(*) from information_schema.columns
                where table_name in ('slack_event_receipt', 'slack_message_receipt')
                and column_name in ('text', 'message', 'payload', 'raw_payload')
                """)).isZero();
    }

    @Test
    void resolves_concurrent_event_replays_as_one_job_and_preserves_content_conflicts() throws Exception {
        SlackPostgresRootIntake intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        intake.receive(rootIntake("T1", "C1", "1.000001"));

        List<SlackEventOutcome> outcomes = concurrently(() -> intake.receive(replyIntake("event-race", "T1", "C1",
                "1.000001", "1.000002", "U2", "one")));

        assertThat(outcomes).containsOnly(SlackEventOutcome.ACCEPTED);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(2);
        assertThatThrownBy(() -> intake.receive(replyIntake("event-other", "T1", "C1", "1.000001", "1.000002", "U2", "changed")))
                .isInstanceOf(MessageConflictException.class);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(2);
    }

    @Test
    void calls_the_model_once_for_repeated_event_and_logical_message_replays() {
        SlackPostgresRootIntake intake = intake();
        ConversationStore store = store();
        intake.receive(rootIntake("T1", "C1", "1.000001"));
        MessageWorkClaim rootClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(rootClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), Instant.parse("2026-09-06T00:00:01Z"));

        SlackRootIntake reply = replyIntake("event-reply", "T1", "C1", "1.000001", "1.000002", "U2", "one");
        intake.receive(reply);
        intake.receive(reply);
        intake.receive(replyIntake("event-overlap", "T1", "C1", "1.000001", "1.000002", "U2", "one"));

        AtomicInteger modelCalls = new AtomicInteger();
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("slack-fake"), "fake", 1_000_000);
        ConversationModel model = new ConversationModel() {
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
                modelCalls.incrementAndGet();
                reservation.reserve();
                return new ModelCallResult(new ModelReply.Text("done"), java.util.Optional.empty());
            }
        };
        ToolCatalog toolCatalog = () -> new ToolSnapshot(List.of());
        MessageJobService messageJobService = new MessageJobService(store, model, toolCatalog,
                Clock.fixed(Instant.parse("2026-09-06T00:00:02Z"), ZoneOffset.UTC));

        messageJobService.process(store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow(), () -> true);

        assertThat(modelCalls).hasValue(1);
        assertThat(store.claimNext("worker", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void rolls_back_binding_source_message_session_event_and_job_when_later_receipt_write_fails() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        jdbcTemplate.execute("""
                create function reject_slack_receipt() returns trigger language plpgsql as $$
                begin
                    raise exception 'receipt rejected';
                end;
                $$;
                create trigger reject_slack_receipt before insert on slack_event_receipt
                    for each row execute function reject_slack_receipt();
                """);

        assertThatThrownBy(() -> intake().receive(rootIntake("T2", "C2", "2.000001")))
                .isInstanceOf(RuntimeException.class);

        assertThat(count(jdbcTemplate, "select count(*) from slack_thread_binding")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from conversation_session where source_type = 'slack'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from session_message where role = 'USER'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isZero();
    }

    private SlackPostgresRootIntake intake() {
        DriverManagerDataSource dataSource = dataSource();
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        ConversationStore conversationStore = new PostgresConversationStore(dataSource, clock, new ObjectMapper());
        MessageIntakePort messageIntakePort = new ConversationMessageService(conversationStore);
        return new SlackPostgresRootIntake(dataSource, messageIntakePort, clock);
    }

    private SlackBoltSocketClient socketClient() {
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", intake());
        return new SlackBoltSocketClient(new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofSeconds(1),
                Duration.ofSeconds(1)), adapter);
    }

    private App appWithoutAuthorization(SlackBoltSocketClient socketClient) {
        App app = new App(socketClient.buildApp().config().toBuilder().singleTeamBotToken(null).build()); // cs-allow Bolt uses null to disable test-only authorization.
        socketClient.registerHandlers(app);
        return app;
    }

    private ConversationStore store() {
        return new PostgresConversationStore(dataSource(), Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC),
                new ObjectMapper());
    }

    private <T> List<T> concurrently(Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<T> synchronizedAction = () -> {
                ready.countDown();
                start.await();
                return action.call();
            };
            Future<T> first = executor.submit(synchronizedAction);
            Future<T> second = executor.submit(synchronizedAction);
            ready.await();
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private List<SlackEventOutcome> concurrently(
            SlackRootIntake firstIntake,
            SlackRootIntake secondIntake,
            java.util.function.Function<SlackRootIntake, SlackEventOutcome> receiver) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<SlackEventOutcome> first = () -> {
                ready.countDown();
                start.await();
                return receiver.apply(firstIntake);
            };
            Callable<SlackEventOutcome> second = () -> {
                ready.countDown();
                start.await();
                return receiver.apply(secondIntake);
            };
            Future<SlackEventOutcome> firstResult = executor.submit(first);
            Future<SlackEventOutcome> secondResult = executor.submit(second);
            ready.await();
            start.countDown();
            return List.of(firstResult.get(), secondResult.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static SlackRootIntake rootIntake(String teamId, String channelId, String messageTs) {
        return replyIntake("event-" + messageTs, teamId, channelId, messageTs, messageTs, "U1", "hello");
    }

    private static SlackRootIntake replyIntake(
            String eventId,
            String teamId,
            String channelId,
            String rootThreadTs,
            String messageTs,
            String participantId,
            String messageText) {
        IncomingMessage message = new IncomingMessage(
                IncomingMessageSource.SLACK,
                "slack/" + teamId + "/" + channelId + "/" + rootThreadTs,
                participantId,
                "slack/" + teamId + "/" + channelId + "/" + messageTs,
                messageText);
        return new SlackRootIntake(eventId, teamId, channelId, rootThreadTs, messageTs,
                SlackIntakeClassification.ACCEPTED, java.util.Optional.of(message));
    }

    private static int count(JdbcTemplate jdbcTemplate, String query) {
        Integer count = jdbcTemplate.queryForObject(query, Integer.class);
        return Objects.requireNonNull(count, "Count query must return a value").intValue();
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }
}
