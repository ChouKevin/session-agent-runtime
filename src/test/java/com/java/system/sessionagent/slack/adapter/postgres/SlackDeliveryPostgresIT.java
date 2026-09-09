package com.java.system.sessionagent.slack.adapter.postgres;

import com.java.system.sessionagent.slack.application.MessageHandler;
import com.java.system.sessionagent.slack.application.SlackDeliveryWorker;
import com.java.system.sessionagent.slack.domain.IncomingEvent;
import com.java.system.sessionagent.slack.domain.MessageSubmission;
import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryPart;
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
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    void persists_fixed_parts_and_resumes_after_a_rate_limited_second_part_without_resending_the_prefix() {
        completeSlackJobWithTerminalRuntime("1.000001", "x".repeat(4_001));
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        List<SlackPostRequest> posts = new ArrayList<>();
        AtomicBoolean rateLimited = new AtomicBoolean();
        SlackWebApi slack = request -> {
            posts.add(request);
            if (posts.size() == 2 && rateLimited.compareAndSet(false, true)) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, Optional.of(Duration.ofSeconds(1)));
            }
            return "2.00000" + posts.size();
        };
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveryStore, slack, new SlackDeliveryProperties(), "delivery-worker");

        assertThat(worker.poll()).isTrue();
        UUID deliveryId = answerDeliveryId();
        assertThat(deliveryStore.read(deliveryId)).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.RETRY);
            assertThat(delivery.totalParts()).contains(2);
            assertThat(delivery.sentParts()).isEqualTo(1);
        });
        jdbcTemplate.update("""
                update slack_delivery set next_attempt_at = clock_timestamp(), terminal_text = 'changed splitter source'
                where delivery_id = ?
                """, deliveryId);
        SlackPostgresDeliveryStore restartedStore = new SlackPostgresDeliveryStore(dataSource());
        SlackDeliveryClaim restartedClaim = restartedStore.claimNext("restart-probe", Duration.ofSeconds(30), 5).orElseThrow();
        assertThat(restartedStore.nextPart(restartedClaim, () -> {
            throw new AssertionError("Persisted delivery parts must not invoke a changed splitter");
        }, Duration.ofSeconds(30))).hasValueSatisfying(part -> assertThat(part.text()).isEqualTo("x"));
        assertThat(restartedStore.scheduleRetry(restartedClaim, SlackDeliveryFailureCategory.TRANSIENT, Duration.ZERO)).isTrue();
        SlackDeliveryWorker recoveredWorker = new SlackDeliveryWorker(new SlackPostgresDeliveryStore(dataSource()), slack,
                new SlackDeliveryProperties(), "recovered-delivery-worker");

        assertThat(recoveredWorker.poll()).isTrue();

        assertThat(posts).extracting(SlackPostRequest::text).containsExactly("x".repeat(4_000), "x", "x");
        assertThat(deliveryStore.read(deliveryId)).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.SENT);
            assertThat(delivery.totalParts()).contains(2);
            assertThat(delivery.sentParts()).isEqualTo(2);
        });
    }

    @Test
    void omits_whitespace_only_delivery_parts_without_changing_the_committed_terminal_answer() {
        String terminalText = "\n".repeat(4_000) + "  indented answer";
        MessageReceipt receipt = completeSlackJobWithTerminalRuntime("1.000001", terminalText);
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        List<SlackPostRequest> posts = new ArrayList<>();
        SlackWebApi slack = request -> {
            posts.add(request);
            return "2.000001";
        };
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveryStore, slack, new SlackDeliveryProperties(), "delivery-worker");

        assertThat(worker.poll()).isTrue();

        UUID deliveryId = answerDeliveryId();
        assertThat(posts).extracting(SlackPostRequest::text).containsExactly("  indented answer");
        assertThat(deliveryStore.read(deliveryId)).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.SENT);
            assertThat(delivery.totalParts()).contains(1);
            assertThat(delivery.sentParts()).isEqualTo(1);
        });
        assertThat(jdbcTemplate.queryForList("""
                select part_index from slack_delivery_part where delivery_id = ? order by part_index
                """, Integer.class, deliveryId)).containsExactly(0);
        assertThat(jdbcTemplate.queryForList("""
                select part_text from slack_delivery_part where delivery_id = ? order by part_index
                """, String.class, deliveryId)).containsExactly("  indented answer");
        assertThat(jdbcTemplate.queryForObject("""
                select runtime.message from runtime_message runtime
                join session_message session on session.session_id = runtime.session_id and session.sequence = runtime.sequence
                where session.message_job_id = ?
                """, String.class, UUID.fromString(receipt.messageJobId().value()))).isEqualTo(terminalText);
    }

    @Test
    void renews_the_parent_lease_before_each_part_so_multiple_sends_outlive_the_original_lease() throws Exception {
        completeSlackJobWithTerminalRuntime("1.000001", "x".repeat(4_001));
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        List<SlackPostRequest> posts = new ArrayList<>();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch allowFirstSendToFinish = new CountDownLatch(1);
        CountDownLatch secondSendStarted = new CountDownLatch(1);
        CountDownLatch allowSecondSendToFinish = new CountDownLatch(1);
        AtomicReference<Instant> originalLeaseExpiry = new AtomicReference<>();
        SlackWebApi controlledSlack = request -> {
            posts.add(request);
            if (posts.size() == 1) {
                originalLeaseExpiry.set(jdbcTemplate.queryForObject(
                        "select locked_until from slack_delivery where kind = 'ANSWER'", Timestamp.class).toInstant());
                firstSendStarted.countDown();
                await(allowFirstSendToFinish);
            } else {
                secondSendStarted.countDown();
                await(allowSecondSendToFinish);
            }
            return "2.00000" + posts.size();
        };
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveryStore, controlledSlack,
                new SlackDeliveryProperties(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(1), 5),
                "delivery-worker");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> poll = executor.submit(worker::poll);
            assertThat(firstSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Instant initialExpiry = originalLeaseExpiry.get();
            assertThat(initialExpiry).isNotNull();
            awaitDatabaseClockAfter(initialExpiry.minusMillis(200));
            allowFirstSendToFinish.countDown();
            assertThat(secondSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseClockAfter(initialExpiry);
            assertThat(jdbcTemplate.queryForObject(
                    "select locked_until > clock_timestamp() from slack_delivery where kind = 'ANSWER'", Boolean.class)).isTrue();
            allowSecondSendToFinish.countDown();
            assertThat(poll.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowFirstSendToFinish.countDown();
            allowSecondSendToFinish.countDown();
            executor.shutdownNow();
        }

        assertThat(posts).hasSize(2);
        assertThat(deliveryStore.read(answerDeliveryId())).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.SENT);
            assertThat(delivery.sentParts()).isEqualTo(2);
        });
    }

    @Test
    void serializes_nonfinal_progress_with_an_overlapping_reclaim() throws Exception {
        completeSlackJobWithTerminalRuntime("1.000001", "x".repeat(4_001));
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();
        SlackDeliveryClaim oldClaim = deliveryStore.claimNext("first-worker", Duration.ofSeconds(30), 5).orElseThrow();
        SlackDeliveryPart firstPart = deliveryStore.nextPart(oldClaim,
                () -> List.of("x".repeat(4_000), "x"), Duration.ofSeconds(30)).orElseThrow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection partLock = dataSource().getConnection()) {
            partLock.setAutoCommit(false);
            try (PreparedStatement statement = partLock.prepareStatement("""
                    select 1 from slack_delivery_part where delivery_id = ? and part_index = 0 for update
                    """)) {
                statement.setObject(1, oldClaim.deliveryId());
                statement.executeQuery();
            }
            Future<Boolean> confirmation = executor.submit(() -> deliveryStore.markPartSent(oldClaim, firstPart, "2.000001"));
            awaitDatabaseLockWaiter("update slack_delivery_part part");
            Future<Integer> reclaim = executor.submit(() -> jdbcTemplate.update("""
                    update slack_delivery set worker_id = 'recovered-worker', claim_number = claim_number + 1,
                        locked_until = clock_timestamp() + interval '30 seconds'
                    where delivery_id = ?
                    """, oldClaim.deliveryId()));
            awaitDatabaseLockWaiter("update slack_delivery set worker_id");

            partLock.commit();

            assertThat(confirmation.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reclaim.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from slack_delivery_part where delivery_id = ? and status = 'SENT'
                """, Integer.class, oldClaim.deliveryId())).isEqualTo(1);
    }

    @Test
    void retains_a_known_truncation_timestamp_on_the_failed_part_without_marking_the_parent_sent() {
        completeSlackJobWithTerminalRuntime();
        markQueuedNoticeSent();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        SlackWebApi truncatingSlack = request -> {
            throw new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty(), Optional.of("2.000099"));
        };
        SlackDeliveryWorker worker = new SlackDeliveryWorker(deliveryStore, truncatingSlack, new SlackDeliveryProperties(),
                "delivery-worker");

        assertThat(worker.poll()).isTrue();

        assertThat(deliveryStore.read(answerDeliveryId())).hasValueSatisfying(delivery -> {
            assertThat(delivery.status()).isEqualTo(SlackDeliveryStatus.FAILED);
            assertThat(delivery.sentParts()).isZero();
        });
        assertThat(jdbcTemplate.queryForObject("""
                select slack_message_ts from slack_delivery_part where delivery_id = ? and part_index = 0
                """, String.class, answerDeliveryId())).isEqualTo("2.000099");
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
    void rejects_conflicting_busy_notice_for_a_source_while_permitting_its_answer() {
        completeSlackJobWithTerminalRuntime();
        SlackPostgresDeliveryStore deliveryStore = new SlackPostgresDeliveryStore(dataSource());
        deliveryStore.discover();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into slack_delivery(delivery_id, kind, team_id, channel_id, root_thread_ts, message_ts, terminal_text,
                    status, attempt_count, next_attempt_at, claim_number, created_at)
                values (?, 'BUSY', 'T1', 'C1', '1.000001', '1.000001', '目前忙碌中。',
                    'PENDING', 0, clock_timestamp(), 0, clock_timestamp())
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(deliveryStore.findBySource("T1", "C1", "1.000001"))
                .extracting(delivery -> delivery.kind().name())
                .containsExactly("QUEUED", "ANSWER");
    }

    @Test
    void migrates_a_v2_answer_delivery_with_its_accepted_source_and_progress_preserved() {
        flyway().clean();
        Flyway.configure().dataSource(dataSource()).cleanDisabled(false).target("2").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
        PostgresConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
        ConversationModels models = TestConfiguredModels.withDefault("test");
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "v2-session", "U1", "v2-source",
                "v2 request");
        MessageReceipt receipt = conversationStore.receive(message, models.defaultSelection(), models);
        completeNextWithTerminalRuntime(conversationStore);
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID messageJobId = UUID.fromString(receipt.messageJobId().value());
        Long terminalSequence = jdbcTemplate.queryForObject("""
                select sequence from session_message
                where session_id = ? and message_job_id = ? and role = 'RUNTIME'
                """, Long.class, sessionId, messageJobId);
        UUID deliveryId = UUID.randomUUID();

        jdbcTemplate.update("""
                insert into slack_message_receipt(team_id, channel_id, message_ts, top_level, classification, session_id,
                    message_job_id, created_at)
                values ('Tv2', 'Cv2', '9.000001', true, 'ACCEPTED', ?, ?, clock_timestamp())
                """, sessionId, messageJobId);
        jdbcTemplate.update("""
                insert into slack_delivery(delivery_id, terminal_session_id, terminal_sequence, message_job_id, channel_id,
                    root_thread_ts, terminal_text, status, attempt_count, next_attempt_at, claim_number, slack_message_ts,
                    created_at, sent_at)
                values (?, ?, ?, ?, 'Cv2', '9.000001', 'v2 terminal text', 'SENT', 3, clock_timestamp(), 3,
                    '10.000001', clock_timestamp(), clock_timestamp())
                """, deliveryId, sessionId, terminalSequence, messageJobId);

        flyway().migrate();

        Map<String, Object> delivery = jdbcTemplate.queryForMap("""
                select kind, team_id, channel_id, root_thread_ts, message_ts, terminal_session_id, terminal_sequence,
                    message_job_id, terminal_text, status, attempt_count, claim_number, slack_message_ts
                from slack_delivery where delivery_id = ?
                """, deliveryId);
        assertThat(delivery).containsEntry("kind", "ANSWER").containsEntry("team_id", "Tv2")
                .containsEntry("channel_id", "Cv2").containsEntry("root_thread_ts", "9.000001")
                .containsEntry("message_ts", "9.000001").containsEntry("terminal_session_id", sessionId)
                .containsEntry("terminal_sequence", terminalSequence).containsEntry("message_job_id", messageJobId)
                .containsEntry("terminal_text", "v2 terminal text").containsEntry("status", "SENT")
                .containsEntry("attempt_count", 3).containsEntry("claim_number", 3L)
                .containsEntry("slack_message_ts", "10.000001");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from slack_delivery delivery
                join slack_message_receipt receipt on receipt.team_id = delivery.team_id
                    and receipt.channel_id = delivery.channel_id and receipt.message_ts = delivery.message_ts
                    and receipt.message_job_id = delivery.message_job_id
                where delivery.delivery_id = ? and receipt.classification = 'ACCEPTED'
                """, Integer.class, deliveryId)).isEqualTo(1);
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
        List<SlackPostRequest> posts = new ArrayList<>();
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
        return completeSlackJobWithTerminalRuntime(messageTs, "Runtime terminal response.");
    }

    private MessageReceipt completeSlackJobWithTerminalRuntime(String messageTs, String terminalText) {
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
        completeNextWithTerminalRuntime(conversationStore, terminalText);
        return receipt;
    }

    private static MessageSubmission acceptedIntake(String eventId, String rootThreadTs, String messageTs) {
        IncomingMessage message = new IncomingMessage(IncomingMessageSource.SLACK, "slack:T1:C1:" + rootThreadTs, "U1",
                "slack:T1:C1:" + messageTs, "request");
        return new MessageSubmission(eventId, "T1", "C1", rootThreadTs, messageTs, SlackIntakeClassification.ACCEPTED,
                Optional.of(message));
    }

    private static void completeNextWithTerminalRuntime(PostgresConversationStore conversationStore) {
        completeNextWithTerminalRuntime(conversationStore, "Runtime terminal response.");
    }

    private static void completeNextWithTerminalRuntime(PostgresConversationStore conversationStore, String terminalText) {
        MessageWorkClaim claim = conversationStore.claimNext("conversation-worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(conversationStore.reserveModelCall(claim, 2, NOW)).hasValue(1);
        conversationStore.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.of("Intermediate tool-call text"), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "lookup", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "lookup", Map.of("result", "value")),
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", terminalText)),
                ConversationStore.JobUpdate.COMPLETE), NOW);
    }

    private UUID answerDeliveryId() {
        return jdbcTemplate.queryForObject("select delivery_id from slack_delivery where kind = 'ANSWER'", UUID.class);
    }

    private void awaitDatabaseClockAfter(Instant instant) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean after = jdbcTemplate.queryForObject("select clock_timestamp() > ?", Boolean.class, Timestamp.from(instant));
            if (Boolean.TRUE.equals(after)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("PostgreSQL clock did not pass the original Slack delivery lease");
    }

    private void awaitDatabaseLockWaiter(String queryFragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock' and query like ?
                    """, Integer.class, "%" + queryFragment + "%");
            if (waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Expected PostgreSQL lock waiter was not observed");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for controlled Slack response");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
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
        private final List<SlackPostRequest> posts = new ArrayList<>();
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
