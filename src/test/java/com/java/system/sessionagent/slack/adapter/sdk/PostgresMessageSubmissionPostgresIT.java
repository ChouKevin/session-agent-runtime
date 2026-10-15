package com.java.system.sessionagent.slack.adapter.sdk;

import com.java.system.sessionagent.slack.adapter.postgres.PostgresMessageSubmission;
import com.java.system.sessionagent.slack.adapter.postgres.SlackPostgresDeliveryStore;
import com.java.system.sessionagent.slack.adapter.postgres.SlackPostgresSessionLookup;
import com.java.system.sessionagent.slack.application.MessageHandler;
import com.java.system.sessionagent.slack.domain.IncomingEvent;
import com.java.system.sessionagent.slack.domain.MessageSubmission;
import com.java.system.sessionagent.slack.domain.SlackEventOutcome;
import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryKind;
import com.java.system.sessionagent.slack.domain.SlackDeliveryStatus;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;
import com.java.system.sessionagent.slack.domain.SlackIntakeClassification;
import com.java.system.sessionagent.slack.domain.SlackIntakeDisposition;
import com.java.system.sessionagent.slack.domain.SlackIntakeResult;
import com.java.system.sessionagent.slack.domain.SlackPermalinkParser;
import com.java.system.sessionagent.slack.domain.SlackSessionResolution;
import com.java.system.sessionagent.slack.configuration.SlackProperties;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessageSource;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.in.QueueFullException;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresConversationStore;
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
import org.slf4j.LoggerFactory;
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
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMessageSubmissionPostgresIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private DriverManagerDataSource dataSource;

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway().clean();
        flyway().migrate();
    }

    @AfterEach
    void clean() {
        flyway().clean();
    }

    @Test
    void atomically_persists_a_slack_binding_receipt_and_provider_neutral_message_job() {
        PostgresMessageSubmission intake = intake();

        SlackIntakeResult result = intake.receive(rootIntake("T1", "C1", "1.000001"));
        SlackIntakeResult replayResult = intake.receive(rootIntake("T1", "C1", "1.000001"));

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        assertThat(result.outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(result.disposition()).isEqualTo(SlackIntakeDisposition.NEW_ACCEPTED);
        assertThat(result.sessionResolution()).contains(SlackSessionResolution.CREATED);
        assertThat(result.sessionId()).isPresent();
        assertThat(result.messageJobId()).isPresent();
        assertThat(replayResult.outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(replayResult.disposition()).isEqualTo(SlackIntakeDisposition.DUPLICATE_ACCEPTED);
        assertThat(replayResult.sessionResolution()).contains(SlackSessionResolution.RESOLVED);
        assertThat(replayResult.sessionId()).isEqualTo(result.sessionId());
        assertThat(replayResult.messageJobId()).isEqualTo(result.messageJobId());
        assertThat(count(jdbcTemplate, "select count(*) from slack_thread_binding")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from session_message where role = 'USER'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(1);
    }

    @Test
    void preserves_a_busy_logical_source_and_its_notice_when_capacity_is_full() {
        PostgresMessageSubmission intake = intake(1);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

        assertThat(intake.receive(rootIntake("T1", "C1", "1.000001")).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(intake.receive(rootIntake("T1", "C1", "2.000001")).outcome()).isEqualTo(SlackEventOutcome.BUSY);
        assertThat(intake.receive(replyIntake("new-envelope", "T1", "C1", "2.000001", "2.000001", "U1", "hello")).outcome())
                .isEqualTo(SlackEventOutcome.BUSY);

        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from conversation_session where session_key like '%2.000001'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from slack_delivery where kind = 'BUSY'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_delivery where kind = 'QUEUED'")).isEqualTo(1);
    }

    @Test
    void preserves_accepted_replays_and_content_conflicts_before_rejecting_new_sources_at_capacity() {
        PostgresMessageSubmission intake = intake(1);
        MessageSubmission accepted = rootIntake("T1", "C1", "1.000001");

        assertThat(intake.receive(accepted).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(intake.receive(accepted).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThatThrownBy(() -> intake.receive(replyIntake("event-content-conflict", "T1", "C1", "1.000001", "1.000001",
                "U1", "changed"))).isInstanceOf(MessageConflictException.class);
        assertThat(intake.receive(rootIntake("T1", "C1", "2.000001")).outcome()).isEqualTo(SlackEventOutcome.BUSY);
    }

    @Test
    void retains_a_busy_outcome_after_capacity_releases_without_promoting_it_to_accepted() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        ConversationStore conversationStore = new PostgresConversationStore(dataSource(), clock, new ObjectMapper(), 1);
        PostgresMessageSubmission intake = intake(conversationStore, clock, 1);
        MessageSubmission busySource = rootIntake("T1", "C1", "2.000001");

        assertThat(intake.receive(rootIntake("T1", "C1", "1.000001")).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(intake.receive(busySource).outcome()).isEqualTo(SlackEventOutcome.BUSY);
        MessageWorkClaim claim = conversationStore.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        conversationStore.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), Instant.parse("2026-09-06T00:00:01Z"));

        assertThat(intake.receive(busySource).outcome()).isEqualTo(SlackEventOutcome.BUSY);
        assertThat(intake.receive(rootIntake("T1", "C1", "3.000001")).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(2);
        assertThat(count(jdbcTemplate, "select count(*) from slack_delivery where message_ts = '2.000001' and kind = 'BUSY'"))
                .isEqualTo(1);
    }

    @Test
    void exposes_a_permanently_failed_no_job_busy_notice_by_source() {
        PostgresMessageSubmission intake = intake(1);
        assertThat(intake.receive(rootIntake("T1", "C1", "1.000001")).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(intake.receive(rootIntake("T1", "C1", "2.000001")).outcome()).isEqualTo(SlackEventOutcome.BUSY);
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());

        SlackDeliveryClaim queuedClaim = deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5).orElseThrow();
        assertThat(queuedClaim.kind()).isEqualTo(SlackDeliveryKind.QUEUED);
        assertThat(deliveryStore.markPartSent(queuedClaim,
                deliveryStore.nextPart(queuedClaim, () -> List.of(queuedClaim.postRequest().text()), Duration.ofSeconds(30)).orElseThrow(),
                "10.000001")).isTrue();
        SlackDeliveryClaim busyClaim = deliveryStore.claimNext("delivery-worker", Duration.ofSeconds(30), 5).orElseThrow();
        assertThat(busyClaim.kind()).isEqualTo(SlackDeliveryKind.BUSY);
        assertThat(deliveryStore.markPartFailed(busyClaim,
                deliveryStore.nextPart(busyClaim, () -> List.of(busyClaim.postRequest().text()), Duration.ofSeconds(30)).orElseThrow(),
                SlackDeliveryFailureCategory.PERMANENT, Optional.empty())).isTrue();

        List<SlackDeliveryView> deliveries = deliveryStore.findBySource("T1", "C1", "2.000001");
        assertThat(deliveries).singleElement().satisfies(delivery -> {
            assertThat(delivery.kind()).isEqualTo(SlackDeliveryKind.BUSY);
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(delivery.failureCategory()).contains(SlackDeliveryFailureCategory.PERMANENT);
            assertThat(delivery.messageJobId()).isEmpty();
        });
    }

    @Test
    void permits_exactly_one_of_mixed_http_and_slack_intake_at_the_final_capacity_slot() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        ConversationStore httpStore = new PostgresConversationStore(dataSource(), clock, new ObjectMapper(), 100);
        for (int index = 0; index < 99; index++) {
            receive(httpStore, new IncomingMessage("http-" + index, "alice", "http-source-" + index, "message"));
        }
        PostgresMessageSubmission slackIntake = intake(100);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> httpAccepted = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    receive(httpStore, new IncomingMessage("http-final", "alice", "http-final-source", "message"));
                    return true;
                } catch (QueueFullException exception) {
                    return false;
                }
            });
            Future<Boolean> slackAccepted = executor.submit(() -> {
                ready.countDown();
                start.await();
                return slackIntake.receive(rootIntake("T1", "C1", "1.000001")).outcome() == SlackEventOutcome.ACCEPTED;
            });
            ready.await();
            start.countDown();

            assertThat(List.of(httpAccepted.get(), slackAccepted.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(100);
        assertThat(count(jdbcTemplate, "select count(*) from slack_delivery where kind in ('QUEUED', 'BUSY')")).isEqualTo(1);
    }

    @Test
    void counts_pending_working_and_retry_jobs_until_terminal_completion_releases_capacity() {
        ConversationStore conversationStore = new PostgresConversationStore(dataSource(),
                Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC), new ObjectMapper(), 1);
        receive(conversationStore, incoming("first"));

        assertThatThrownBy(() -> receive(conversationStore, incoming("pending"))).isInstanceOf(QueueFullException.class);
        MessageWorkClaim workingClaim = conversationStore.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThatThrownBy(() -> receive(conversationStore, incoming("working"))).isInstanceOf(QueueFullException.class);
        assertThat(conversationStore.scheduleRetry(workingClaim, Duration.ZERO, JobFailure.MODEL_UNAVAILABLE)).isTrue();
        assertThatThrownBy(() -> receive(conversationStore, incoming("retry"))).isInstanceOf(QueueFullException.class);
        MessageWorkClaim retryClaim = conversationStore.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        conversationStore.append(retryClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), Instant.parse("2026-09-06T00:00:01Z"));

        assertThat(receive(conversationStore, incoming("after-terminal")).messageJobId().value()).isNotBlank();
    }

    @Test
    void retains_existing_unfinished_jobs_when_a_reconstructed_store_has_a_lower_limit() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        ConversationStore roomierStore = new PostgresConversationStore(dataSource(), clock, new ObjectMapper(), 2);
        MessageReceipt first = receive(roomierStore, incoming("first"));
        receive(roomierStore, incoming("second"));
        ConversationStore tightenedStore = new PostgresConversationStore(dataSource(), clock, new ObjectMapper(), 1);

        assertThat(receive(tightenedStore, incoming("first")).messageJobId()).isEqualTo(first.messageJobId());
        assertThatThrownBy(() -> receive(tightenedStore, incoming("third"))).isInstanceOf(QueueFullException.class);
        assertThat(count(new JdbcTemplate(dataSource()), "select count(*) from message_job")).isEqualTo(2);
    }

    @Test
    void keeps_an_unbound_reply_ignored_when_capacity_is_full() {
        PostgresMessageSubmission intake = intake(1);
        assertThat(intake.receive(rootIntake("T1", "C1", "1.000001")).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);

        SlackIntakeResult result = intake.receive(replyIntake("event-unbound-full", "T1", "C1", "2.000001", "2.000002", "U2", "reply"));

        assertThat(result.outcome()).isEqualTo(SlackEventOutcome.IGNORED);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        assertThat(count(jdbcTemplate, "select count(*) from slack_delivery where kind = 'BUSY' ")).isZero();
    }

    @Test
    void promotes_a_previously_ignored_top_level_source_to_one_busy_outcome_when_capacity_is_full() {
        PostgresMessageSubmission intake = intake(1);
        MessageSubmission ignored = new MessageSubmission("event-ignored-top-level", "T1", "C1", "2.000001", "2.000001",
                SlackIntakeClassification.BLANK, Optional.empty());
        assertThat(intake.receive(ignored).outcome()).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(intake.receive(rootIntake("T1", "C1", "1.000001")).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);

        SlackIntakeResult promoted = intake.receive(rootIntake("T1", "C1", "2.000001"));

        assertThat(promoted.outcome()).isEqualTo(SlackEventOutcome.BUSY);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_message_receipt where message_ts = '2.000001' and classification = 'BUSY'"))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_delivery where message_ts = '2.000001' and kind = 'BUSY'"))
                .isEqualTo(1);
    }

    @Test
    void logs_one_new_session_for_concurrent_same_event_and_overlapping_event_redelivery() throws Exception {
        MessageHandler adapter = new MessageHandler("UBOT", intake());
        Logger logger = (Logger) LoggerFactory.getLogger(MessageHandler.class);
        ConcurrentLogAppender appender = new ConcurrentLogAppender();
        appender.start();
        logger.addAppender(appender);
        try {
            IncomingEvent sameEvent = rootEvent("event-same", "1.000001");
            List<SlackEventOutcome> sameEventOutcomes = concurrently(() -> adapter.handle(sameEvent));
            List<SlackEventOutcome> overlappingEventOutcomes = concurrently(() -> adapter.handle(rootEvent(
                    "event-overlap-" + Thread.currentThread().threadId(), "2.000001")));

            assertThat(sameEventOutcomes).containsOnly(SlackEventOutcome.ACCEPTED);
            assertThat(overlappingEventOutcomes).containsOnly(SlackEventOutcome.ACCEPTED);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<Map<String, Object>> inboundLogs = appender.events().stream()
                .map(PostgresMessageSubmissionPostgresIT::keyValues)
                .filter(values -> "slack_inbound".equals(values.get("event")))
                .toList();
        assertThat(inboundLogs).hasSize(4);
        assertThat(dispositionsFor(inboundLogs, "1.000001"))
                .containsExactlyInAnyOrder("NEW_ACCEPTED", "DUPLICATE_ACCEPTED");
        assertThat(dispositionsFor(inboundLogs, "2.000001"))
                .containsExactlyInAnyOrder("NEW_ACCEPTED", "DUPLICATE_ACCEPTED");
        assertThat(correlationsFor(inboundLogs, "1.000001", "sessionId")).doesNotContainNull().hasSize(1);
        assertThat(correlationsFor(inboundLogs, "1.000001", "messageJobId")).doesNotContainNull().hasSize(1);
        assertThat(correlationsFor(inboundLogs, "2.000001", "sessionId")).doesNotContainNull().hasSize(1);
        assertThat(correlationsFor(inboundLogs, "2.000001", "messageJobId")).doesNotContainNull().hasSize(1);
        assertThat(appender.events().stream().map(PostgresMessageSubmissionPostgresIT::keyValues)
                .filter(values -> "session_created".equals(values.get("event")))).hasSize(2);
        assertThat(appender.events().stream().map(PostgresMessageSubmissionPostgresIT::keyValues)
                .filter(values -> "session_resolved".equals(values.get("event")))).hasSize(2);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        assertThat(count(jdbcTemplate, "select count(*) from conversation_session where source_type = 'slack'")).isEqualTo(2);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(2);
    }

    @Test
    void resolves_a_migrated_slack_thread_binding_from_root_and_reply_permalinks() {
        PostgresMessageSubmission intake = intake();
        intake.receive(rootIntake("T1", "C01ABCDEF", "1712345678.123456"));

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        UUID persistedSessionId = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                select session_id from slack_thread_binding
                where team_id = ? and channel_id = ? and root_thread_ts = ?
                """, UUID.class, "T1", "C01ABCDEF", "1712345678.123456"));
        SlackPostgresSessionLookup lookup = new SlackPostgresSessionLookup(dataSource(), new SlackPermalinkParser());
        SessionId expectedSessionId = new SessionId(persistedSessionId.toString());

        assertThat(lookup.findSessionId("https://acme.slack.com/archives/C01ABCDEF/p1712345678123456"))
                .contains(expectedSessionId);
        assertThat(lookup.findSessionId("https://acme.slack.com/archives/C01ABCDEF/p1712345687123456"
                + "?thread_ts=1712345678.123456&cid=C01ABCDEF")).contains(expectedSessionId);
        assertThat(lookup.findSessionId("https://acme.slack.com/archives/C01MISSING/p1712345678123456")).isEmpty();
    }

    @Test
    void ignores_an_unbound_thread_reply_without_creating_a_session_or_job() {
        PostgresMessageSubmission intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

        intake.receive(replyIntake("event-unbound", "T1", "C1", "1.000001", "1.000002", "U2", "reply"));

        assertThat(count(jdbcTemplate, "select count(*) from conversation_session where source_type = 'slack'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isZero();
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isZero();
    }

    @Test
    void keeps_an_unbound_logical_reply_ignored_across_same_and_distinct_event_redelivery_after_binding() throws Exception {
        PostgresMessageSubmission intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        MessageSubmission unboundReply = replyIntake("event-unbound", "T1", "C1", "1.000001", "1.000002", "U2", "reply");

        SlackEventOutcome initial = intake.receive(unboundReply).outcome();
        intake.receive(rootIntake("T1", "C1", "1.000001"));
        SlackEventOutcome sameEventReplay = intake.receive(unboundReply).outcome();
        List<SlackEventOutcome> distinctEventReplays = concurrently(() -> intake.receive(replyIntake(
                "event-unbound-overlap-" + Thread.currentThread().threadId(), "T1", "C1", "1.000001", "1.000002", "U2", "reply")).outcome());

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
        MessageHandler adapter = new MessageHandler("UBOT", intake());
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
    void accepts_an_eligible_top_level_root_after_a_committed_ignored_top_level_representation() {
        PostgresMessageSubmission intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        MessageSubmission ignored = new MessageSubmission("event-ignored-first", "T1", "C1", "1.000001", "1.000001",
                SlackIntakeClassification.UNSUPPORTED_CONTENT, Optional.empty());
        MessageSubmission accepted = rootIntake("T1", "C1", "1.000001");

        SlackEventOutcome ignoredOutcome = intake.receive(ignored).outcome();
        SlackEventOutcome acceptedOutcome = intake.receive(accepted).outcome();
        SlackEventOutcome acceptedReplayOutcome = intake.receive(accepted).outcome();

        assertThat(ignoredOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(acceptedOutcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(acceptedReplayOutcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(count(jdbcTemplate, "select count(*) from slack_message_receipt where classification = 'ACCEPTED'"))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt where classification = 'UNSUPPORTED_CONTENT'"))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt where classification = 'ACCEPTED'"))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from conversation_session where source_type = 'slack'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(1);
    }

    @Test
    void commits_a_staged_accepted_root_before_an_overlapping_ignored_representation() throws Exception {
        DriverManagerDataSource raceDataSource = dataSource();
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        MessageIntakePort delegate = new MessageService(new PostgresConversationStore(raceDataSource, clock, new ObjectMapper()),
                TestConfiguredModels.withDefault("test"), new NoOpConversationTelemetry());
        CountDownLatch acceptedMessageStaged = new CountDownLatch(1);
        CountDownLatch allowAcceptedReceipt = new CountDownLatch(1);
        MessageIntakePort stagedAcceptedIntake = message -> {
            MessageReceipt receipt = delegate.receive(message);
            acceptedMessageStaged.countDown();
            try {
                allowAcceptedReceipt.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while staging the accepted Slack intake", exception);
            }
            return receipt;
        };
        PostgresMessageSubmission intake = new PostgresMessageSubmission(raceDataSource, stagedAcceptedIntake, clock);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        MessageSubmission accepted = rootIntake("T1", "C1", "1.000001");
        MessageSubmission ignored = new MessageSubmission("event-ignored-race", "T1", "C1", "1.000001", "1.000001",
                SlackIntakeClassification.UNSUPPORTED_CONTENT, Optional.empty());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SlackEventOutcome> acceptedOutcome = executor.submit(() -> intake.receive(accepted).outcome());
            assertThat(acceptedMessageStaged.await(5, TimeUnit.SECONDS)).isTrue();
            Future<SlackEventOutcome> ignoredOutcome = executor.submit(() -> intake.receive(ignored).outcome());
            allowAcceptedReceipt.countDown();

            assertThat(acceptedOutcome.get()).isEqualTo(SlackEventOutcome.ACCEPTED);
            assertThat(ignoredOutcome.get()).isEqualTo(SlackEventOutcome.ACCEPTED);
        } finally {
            allowAcceptedReceipt.countDown();
            executor.shutdownNow();
        }
        assertThat(intake.receive(ignored).outcome()).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(count(jdbcTemplate, "select count(*) from slack_message_receipt")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt")).isEqualTo(2);
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from conversation_session where source_type = 'slack'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(1);
        assertThat(count(jdbcTemplate, """
                select count(*) from slack_event_receipt
                where classification = 'ACCEPTED' and correlation_id is not null
                """)).isEqualTo(2);
    }

    @Test
    void acknowledges_an_unregistered_candidate_human_subtype_with_the_installed_workspace_identity() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        SlackBoltSocketClient socketClient = socketClient();
        App app = appWithoutAuthorization(socketClient);
        EventRequest request = new EventRequest("""
                {
                  "type":"event_callback",
                  "event_id":"event-assistant-thread",
                  "team_id":"T-origin",
                  "authorizations":[{"team_id":"T-installed"}],
                  "event":{"type":"message","subtype":"assistant_app_thread","channel":"C1","channel_type":"channel",
                  "user":"U1","text":"private assistant thread text","ts":"1.000001"}
                }
                """, new RequestHeaders(Map.of()));
        request.setSocketMode(true);

        Response response = app.run(request);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(count(jdbcTemplate, """
                select count(*) from slack_event_receipt
                where classification = 'UNSUPPORTED_CONTENT' and team_id = 'T-installed'
                """))
                .isEqualTo(1);
        assertThat(count(jdbcTemplate, """
                select count(*) from slack_message_receipt
                where classification = 'UNSUPPORTED_CONTENT' and team_id = 'T-installed'
                """))
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
        PostgresMessageSubmission intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

        intake.receive(rootIntake("T1", "C1", "1.000001"));
        SlackEventOutcome firstReply = intake.receive(replyIntake("event-reply-1", "T1", "C1", "1.000001", "1.000002", "U2", "one")).outcome();
        SlackEventOutcome replay = intake.receive(replyIntake("event-reply-1", "T1", "C1", "1.000001", "1.000002", "U2", "one")).outcome();
        SlackEventOutcome overlappingSubscription = intake.receive(replyIntake("event-reply-2", "T1", "C1", "1.000001", "1.000002", "U2", "one")).outcome();

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
        PostgresMessageSubmission intake = intake();
        ConversationStore store = store();
        intake.receive(rootIntake("T1", "C1", "1.000001"));
        MessageWorkClaim rootClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(rootClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), Instant.parse("2026-09-06T00:00:01Z"));

        List<SlackEventOutcome> outcomes = concurrently(() -> {
            String suffix = Long.toString(Thread.currentThread().threadId());
            return intake.receive(replyIntake("event-" + suffix, "T1", "C1", "1.000001", "2.00000" + suffix,
                    "U" + suffix, "reply " + suffix)).outcome();
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
        PostgresMessageSubmission intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

        SlackEventOutcome outcome = intake.receive(new MessageSubmission("event-ignored", "T1", "C1", "1.000001", "1.000001",
                SlackIntakeClassification.BLANK, Optional.empty())).outcome();

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
        PostgresMessageSubmission intake = intake();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        intake.receive(rootIntake("T1", "C1", "1.000001"));

        List<SlackEventOutcome> outcomes = concurrently(() -> intake.receive(replyIntake("event-race", "T1", "C1",
                "1.000001", "1.000002", "U2", "one")).outcome());

        assertThat(outcomes).containsOnly(SlackEventOutcome.ACCEPTED);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(2);
        assertThatThrownBy(() -> intake.receive(replyIntake("event-other", "T1", "C1", "1.000001", "1.000002", "U2", "changed")))
                .isInstanceOf(MessageConflictException.class);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(2);
    }

    @Test
    void calls_the_model_once_for_repeated_event_and_logical_message_replays() {
        PostgresMessageSubmission intake = intake();
        ConversationStore store = store();
        intake.receive(rootIntake("T1", "C1", "1.000001"));
        MessageWorkClaim rootClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(rootClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), Instant.parse("2026-09-06T00:00:01Z"));

        MessageSubmission reply = replyIntake("event-reply", "T1", "C1", "1.000001", "1.000002", "U2", "one");
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
                return new ModelCallResult(new ModelReply.Text("done"), Optional.empty());
            }
        };
        ToolCatalog toolCatalog = () -> new ToolSnapshot(List.of());
        JobProcessor messageJobService = new JobProcessor(store, TestConfiguredModels.withModel(model), toolCatalog,
                Clock.fixed(Instant.parse("2026-09-06T00:00:02Z"), ZoneOffset.UTC), 3,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(1)), new NoOpConversationTelemetry());

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

    private PostgresMessageSubmission intake() {
        return intake(100);
    }

    private PostgresMessageSubmission intake(int maximumUnfinishedJobs) {
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        ConversationStore conversationStore = new PostgresConversationStore(dataSource(), clock, new ObjectMapper(), maximumUnfinishedJobs);
        return intake(conversationStore, clock, maximumUnfinishedJobs);
    }

    private PostgresMessageSubmission intake(ConversationStore conversationStore, Clock clock, int maximumUnfinishedJobs) {
        MessageIntakePort messageIntakePort = new MessageService(conversationStore, TestConfiguredModels.withDefault("test"),
                new NoOpConversationTelemetry());
        return new PostgresMessageSubmission(dataSource(), messageIntakePort, clock, maximumUnfinishedJobs);
    }

    private SlackBoltSocketClient socketClient() {
        MessageHandler adapter = new MessageHandler("UBOT", intake());
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

    private static MessageSubmission rootIntake(String teamId, String channelId, String messageTs) {
        return replyIntake("event-" + messageTs, teamId, channelId, messageTs, messageTs, "U1", "hello");
    }

    private static IncomingEvent rootEvent(String eventId, String messageTs) {
        return new IncomingEvent(eventId, "T1", "C1", messageTs, "", "U1", "", "channel",
                "<@UBOT> hello", "", false, false);
    }

    private static List<Object> dispositionsFor(List<Map<String, Object>> logs, String messageTs) {
        return logs.stream().filter(values -> messageTs.equals(values.get("slackMessageTs")))
                .map(values -> values.get("disposition")).toList();
    }

    private static List<Object> correlationsFor(List<Map<String, Object>> logs, String messageTs, String correlationName) {
        return logs.stream().filter(values -> messageTs.equals(values.get("slackMessageTs")))
                .map(values -> values.get(correlationName)).distinct().toList();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static MessageSubmission replyIntake(
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
        return new MessageSubmission(eventId, teamId, channelId, rootThreadTs, messageTs,
                SlackIntakeClassification.ACCEPTED, Optional.of(message));
    }

    private static MessageReceipt receive(ConversationStore store, IncomingMessage message) {
        ConversationModels models = TestConfiguredModels.withDefault("test");
        return store.receive(message, models.defaultSelection(), models);
    }

    private static IncomingMessage incoming(String sourceId) {
        return new IncomingMessage(sourceId, "alice", "http-source-" + sourceId, "message");
    }

    private static int count(JdbcTemplate jdbcTemplate, String query) {
        Integer count = jdbcTemplate.queryForObject(query, Integer.class);
        return Objects.requireNonNull(count, "Count query must return a value").intValue();
    }

    private DriverManagerDataSource dataSource() {
        return dataSource;
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }

    private static final class ConcurrentLogAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }

        private List<ILoggingEvent> events() {
            return List.copyOf(events);
        }
    }
}
