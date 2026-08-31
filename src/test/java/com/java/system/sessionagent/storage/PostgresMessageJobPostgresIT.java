package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMessageJobPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:01Z");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        flyway().clean();
        flyway().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void clean() {
        flyway().clean();
    }

    @Test
    void keeps_committed_history_visible_across_retry_and_global_job_ordering() {
        ConversationStore store = store();
        MessageReceipt first = store.receive(new IncomingMessage("thread", "alice", "first", "one"));
        MessageWorkClaim firstClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(firstClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("working")),
                ConversationStore.JobUpdate.KEEP_WORKING), NOW);
        assertThat(store.scheduleRetry(firstClaim, Duration.ZERO)).isTrue();
        assertThat(store.loadHistory(first.sessionId())).extracting(message -> message.sequence().value()).containsExactly(1L, 2L);
        MessageWorkClaim reclaimed = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(reclaimed, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), NOW.plusSeconds(1));
        MessageReceipt second = store.receive(new IncomingMessage("thread", "alice", "second", "two"));
        MessageWorkClaim secondClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(secondClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("second done")),
                ConversationStore.JobUpdate.COMPLETE), NOW.plusSeconds(2));
        assertThat(store.loadHistory(second.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void allows_only_one_concurrent_claim_and_model_reservation_for_a_session() throws Exception {
        ConversationStore store = store();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"));
        List<Optional<MessageWorkClaim>> claims = concurrently(() -> store.claimNext(
                "worker-" + Thread.currentThread().threadId(), Duration.ofSeconds(30)));

        assertThat(claims.stream().filter(Optional::isPresent)).hasSize(1);
        MessageWorkClaim claim = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        List<OptionalInt> reservations = concurrently(() -> store.reserveModelCall(claim, 1, NOW));

        assertThat(reservations).containsExactlyInAnyOrder(OptionalInt.of(1), OptionalInt.empty());
        assertThat(jdbcTemplate.queryForObject("select model_calls from message_job where message_job_id = ?", Integer.class,
                UUID.fromString(receipt.messageJobId().value()))).isEqualTo(1);
    }

    @Test
    void rejects_an_aba_stale_claim_without_allocating_or_appending_a_message() {
        ConversationStore store = store();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim original = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?",
                UUID.fromString(receipt.messageJobId().value()));
        MessageWorkClaim replacement = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        assertThat(replacement.claimNumber()).isGreaterThan(original.claimNumber());
        assertThatThrownBy(() -> store.append(original, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.AssistantData("stale")), ConversationStore.JobUpdate.KEEP_WORKING), NOW))
                .isExactlyInstanceOf(StaleWorkClaimException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select next_sequence from conversation_session", Long.class)).isEqualTo(2L);
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

    private ConversationStore store() {
        return new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }
}
