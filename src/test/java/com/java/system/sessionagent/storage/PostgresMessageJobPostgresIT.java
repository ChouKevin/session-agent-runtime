package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMessageJobPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-16T02:00:00Z");

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        POSTGRES.start();
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void clearSchema() {
        flyway().clean();
    }

    @Test
    void claimsOnlyTheEarliestAvailableJobWithinASessionAndKeepsItExclusive() {
        ConversationStore store = newStore();
        MessageReceipt first = receive(store, "thread-1", "source-1");
        MessageReceipt second = receive(store, "thread-1", "source-2");
        jdbcTemplate.update("update message_job set available_at = clock_timestamp() + interval '60 seconds' where message_job_id = ?", id(first));

        assertThat(store.claimNext("worker-1", Duration.ofSeconds(30))).isEmpty();

        jdbcTemplate.update("update message_job set available_at = clock_timestamp() where message_job_id = ?", id(first));
        Optional<MessageWorkClaim> claim = store.claimNext("worker-1", Duration.ofSeconds(30));

        assertThat(claim).hasValueSatisfying(value -> assertThat(value.messageJobId()).isEqualTo(first.messageJobId()));
        assertThat(store.claimNext("worker-2", Duration.ofSeconds(30))).isEmpty();
        assertThat(jobStatus(second)).isEqualTo("PENDING");
    }

    @Test
    void claimsIndependentSessionsConcurrentlyThroughSkipLocked() throws Exception {
        ConversationStore store = newStore();
        MessageReceipt first = receive(store, "thread-1", "source-1");
        MessageReceipt second = receive(store, "thread-2", "source-2");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Optional<MessageWorkClaim>> claim = () -> {
                ready.countDown();
                start.await();
                return store.claimNext("worker-" + Thread.currentThread().threadId(), Duration.ofSeconds(30));
            };
            Future<Optional<MessageWorkClaim>> firstClaim = executor.submit(claim);
            Future<Optional<MessageWorkClaim>> secondClaim = executor.submit(claim);
            ready.await();
            start.countDown();

            List<UUID> claimedIds = List.of(firstClaim.get().orElseThrow(), secondClaim.get().orElseThrow()).stream()
                    .map(value -> id(value.messageJobId().value()))
                    .sorted()
                    .toList();

            assertThat(claimedIds).containsExactlyInAnyOrder(id(first), id(second));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void skipsAHeldEarlierRowAndClaimsASeparateSessionWithoutWaiting() throws Exception {
        ConversationStore store = newStore();
        MessageReceipt lockedReceipt = receive(store, "thread-1", "source-1");
        MessageReceipt availableReceipt = receive(store, "thread-2", "source-2");
        jdbcTemplate.update("update message_job set available_at = clock_timestamp() where message_job_id = ?", id(lockedReceipt));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select message_job_id from message_job where message_job_id = ? for update")) {
                statement.setObject(1, id(lockedReceipt));
                statement.executeQuery();
            }

            Future<Optional<MessageWorkClaim>> claim = executor.submit(
                    () -> store.claimNext("worker-2", Duration.ofSeconds(30)));

            assertThat(claim.get(2, java.util.concurrent.TimeUnit.SECONDS))
                    .hasValueSatisfying(value -> assertThat(value.messageJobId()).isEqualTo(availableReceipt.messageJobId()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void allowsExactlyOneConcurrentClaimForOneSessionWithoutPersistenceFailure() throws Exception {
        ConversationStore store = newStore();
        receive(store, "thread-1", "source-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Optional<MessageWorkClaim>> claim = () -> {
                ready.countDown();
                start.await();
                return store.claimNext("worker-" + Thread.currentThread().threadId(), Duration.ofSeconds(30));
            };
            Future<Optional<MessageWorkClaim>> firstClaim = executor.submit(claim);
            Future<Optional<MessageWorkClaim>> secondClaim = executor.submit(claim);
            ready.await();
            start.countDown();

            List<Optional<MessageWorkClaim>> claims = List.of(
                    firstClaim.get(2, java.util.concurrent.TimeUnit.SECONDS),
                    secondClaim.get(2, java.util.concurrent.TimeUnit.SECONDS));

            assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from message_job where status = 'WORKING'", Integer.class))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reclaimsAnExpiredJobWithANewPositiveClaimNumberAndRejectsTheStaleFence() {
        ConversationStore store = newStore();
        MessageReceipt receipt = receive(store, "thread-1", "source-1");
        MessageWorkClaim original = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?", id(receipt));
        MessageWorkClaim replacement = store.claimNext("worker-2", Duration.ofSeconds(30)).orElseThrow();

        assertThat(replacement.messageJobId()).isEqualTo(receipt.messageJobId());
        assertThat(replacement.claimNumber()).isEqualTo(original.claimNumber() + 1);
        assertThat(store.extendClaim(original, Duration.ofSeconds(30))).isFalse();
        assertThat(store.reserveModelCall(original, NOW.plusSeconds(31))).isEmpty();
        assertThat(store.scheduleRetry(original, Duration.ofSeconds(90))).isFalse();
        assertThat(store.extendClaim(replacement, Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void uses_the_postgres_clock_for_expiry_and_ignores_a_delayed_worker_timestamp() {
        ConversationStore store = newStore();
        MessageReceipt receipt = receive(store, "thread-1", "source-1");
        MessageWorkClaim original = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?", id(receipt));

        assertThat(store.reserveModelCall(original, NOW.minusSeconds(3600))).isEmpty();
        MessageWorkClaim replacement = store.claimNext("worker-2", Duration.ofSeconds(30)).orElseThrow();

        assertThat(replacement.claimNumber()).isEqualTo(original.claimNumber() + 1);
        assertThat(store.extendClaim(original, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    void rejectsAnAbaStaleClaimFromTheSameWorkerWithoutChangingPersistedState() {
        ConversationStore store = newStore();
        MessageReceipt receipt = receive(store, "thread-1", "source-1");
        MessageWorkClaim original = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?", id(receipt));
        MessageWorkClaim replacement = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        JobState before = jobState(receipt);

        assertThat(replacement.claimNumber()).isGreaterThan(original.claimNumber());
        assertThat(store.extendClaim(original, Duration.ofSeconds(30))).isFalse();
        assertThat(store.reserveModelCall(original, NOW.plusSeconds(32))).isEmpty();
        assertThat(store.scheduleRetry(original, Duration.ofSeconds(90))).isFalse();

        assertThat(jobState(receipt)).isEqualTo(before);
    }

    @Test
    void refusesToShortenAnUnexpiredClaimWhenTheWorkerClockMovesBackward() {
        ConversationStore store = newStore();
        MessageReceipt receipt = receive(store, "thread-1", "source-1");
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        JobState before = jobState(receipt);

        assertThat(store.extendClaim(claim, Duration.ofSeconds(10))).isFalse();

        assertThat(jobState(receipt)).isEqualTo(before);
    }

    @Test
    void reservesConfiguredModelCallsUnderTheCurrentFence() {
        ConversationStore store = newStore();
        receive(store, "thread-1", "source-1");
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();

        List<Integer> reservations = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> store.reserveModelCall(claim, 12, NOW.plusSeconds(index)).orElseThrow())
                .toList();

        assertThat(reservations).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertThat(store.reserveModelCall(claim, 12, NOW.plusSeconds(13))).isEqualTo(OptionalInt.empty());
    }

    @Test
    void allowsOnlyOneConcurrentTwelfthModelCallReservation() throws Exception {
        ConversationStore store = newStore();
        MessageReceipt receipt = receive(store, "thread-1", "source-1");
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        for (int ordinal = 1; ordinal <= 11; ordinal++) {
            store.reserveModelCall(claim, 12, NOW).orElseThrow();
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<OptionalInt> reserve = () -> {
                ready.countDown();
                start.await();
                return store.reserveModelCall(claim, 12, NOW);
            };
            Future<OptionalInt> firstReservation = executor.submit(reserve);
            Future<OptionalInt> secondReservation = executor.submit(reserve);
            ready.await();
            start.countDown();

            assertThat(List.of(
                    firstReservation.get(2, java.util.concurrent.TimeUnit.SECONDS),
                    secondReservation.get(2, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(OptionalInt.of(12), OptionalInt.empty());
            assertThat(jdbcTemplate.queryForObject(
                    "select model_calls from message_job where message_job_id = ?", Integer.class, id(receipt))).isEqualTo(12);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void schedulesRetryOnlyForTheCurrentUnexpiredFence() {
        ConversationStore store = newStore();
        receive(store, "thread-1", "source-1");
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();

        assertThat(store.scheduleRetry(claim, Duration.ofSeconds(45))).isTrue();
        assertThat(jdbcTemplate.queryForObject("select status from message_job", String.class)).isEqualTo("RETRY");
        assertThat(store.extendClaim(claim, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    void completes_a_job_with_a_terminal_runtime_batch_without_an_assistant_reply() {
        ConversationStore store = newStore();
        MessageReceipt receipt = receive(store, "thread-1", "source-1");
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();

        store.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("MODEL_OUTPUT_INVALID", "The model returned no usable output.")),
                ConversationStore.JobUpdate.COMPLETE), NOW);

        assertThat(store.readJob(receipt.messageJobId()))
                .hasValueSatisfying(job -> {
                    assertThat(job.status()).isEqualTo(JobStatus.DONE);
                    assertThat(job.replySequence()).hasValue(new SessionSequence(2));
                });
        assertThat(store.loadHistory(receipt.sessionId()).getLast()).isInstanceOf(RuntimeMessage.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from assistant_message", Integer.class)).isZero();
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
    }

    private ConversationStore newStore() {
        return new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MessageReceipt receive(ConversationStore store, String sessionKey, String sourceMessageId) {
        return store.receive(new IncomingMessage(sessionKey, "alice", sourceMessageId, "hello"));
    }

    private String jobStatus(MessageReceipt receipt) {
        return jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class, id(receipt));
    }

    private JobState jobState(MessageReceipt receipt) {
        return jdbcTemplate.queryForObject("""
                select status, model_calls, retry_count, available_at, worker_id, claim_number, locked_until
                from message_job
                where message_job_id = ?
                """, (resultSet, rowNumber) -> new JobState(
                resultSet.getString("status"),
                resultSet.getInt("model_calls"),
                resultSet.getInt("retry_count"),
                resultSet.getObject("available_at", OffsetDateTime.class),
                Optional.ofNullable(resultSet.getString("worker_id")),
                resultSet.getLong("claim_number"),
                Optional.ofNullable(resultSet.getObject("locked_until", OffsetDateTime.class))), id(receipt));
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private UUID id(MessageReceipt receipt) {
        return id(receipt.messageJobId().value());
    }

    private UUID id(String value) {
        return UUID.fromString(value);
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record JobState(
            String status,
            int modelCalls,
            int retryCount,
            OffsetDateTime availableAt,
            Optional<String> workerId,
            long claimNumber,
            Optional<OffsetDateTime> lockedUntil) {
    }
}
