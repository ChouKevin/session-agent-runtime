package com.java.system.sessionagent.storage.adapter.postgres;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelRouteMismatchException;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

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
        assertThat(store.loadHistory(first.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L);
        store.append(reclaimed, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), NOW.plusSeconds(1));
        MessageReceipt second = store.receive(new IncomingMessage("thread", "alice", "second", "two"));
        MessageWorkClaim secondClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.loadHistory(second.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L, 4L);
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

        assertThat(claims.stream().filter(optionalClaim -> optionalClaim.isPresent())).hasSize(1);
        MessageWorkClaim claim = claims.stream().flatMap(optionalClaim -> optionalClaim.stream())
                .findFirst().orElseThrow();
        List<OptionalInt> reservations = concurrently(() -> store.reserveModelCall(claim, 1, NOW));

        assertThat(reservations).containsExactlyInAnyOrder(OptionalInt.of(1), OptionalInt.empty());
        assertThat(jdbcTemplate.queryForObject("select model_calls from message_job where message_job_id = ?", Integer.class,
                UUID.fromString(receipt.messageJobId().value()))).isEqualTo(1);
    }

    @Test
    void consumes_a_reserved_ordinal_across_reclaim_while_leaving_uncommitted_tool_work_replayable() {
        ConversationStore store = store();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim original = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        assertThat(store.reserveModelCall(original, 2, NOW)).hasValue(1);
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?",
                UUID.fromString(original.messageJobId().value()));

        MessageWorkClaim recovered = store.claimNext("recovery-worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.loadHistory(receipt.sessionId())).extracting(message -> message.sequence().value()).containsExactly(1L);
        assertThat(store.reserveModelCall(recovered, 2, NOW)).hasValue(2);
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

    @Test
    void binds_one_model_route_for_the_complete_job() {
        ConversationStore store = store();
        store.receive(new IncomingMessage("thread", "alice", "route-job", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        store.bindModelRoute(claim, new ModelRouteId("gemini-primary"));
        store.bindModelRoute(claim, new ModelRouteId("gemini-primary"));

        assertThatThrownBy(() -> store.bindModelRoute(claim, new ModelRouteId("codex-primary")))
                .isExactlyInstanceOf(ModelRouteMismatchException.class);
    }

    @Test
    void binds_the_current_session_selection_at_intake_without_rewriting_existing_or_duplicate_jobs() {
        ConversationStore store = store();
        ModelSelection first = new ModelSelection("google-primary", "v1:google-a");
        ModelSelection second = new ModelSelection("openai-primary", "v1:openai-b");

        MessageReceipt original = store.receive(new IncomingMessage("thread", "alice", "first", "one"), first,
                model -> selection(model, first, second));
        MessageWorkClaim originalClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(originalClaim.modelSelection()).isEqualTo(first);
        assertThat(store.scheduleRetry(originalClaim, Duration.ZERO)).isTrue();

        assertThat(store.selectModel(original.sessionId(), first)).contains(first);
        assertThat(jdbcTemplate.queryForObject("select count(*) from message_job", Integer.class)).isEqualTo(1);
        assertThat(store.selectModel(original.sessionId(), second)).contains(second);
        MessageReceipt duplicate = store.receive(new IncomingMessage("thread", "alice", "first", "one"), first,
                model -> selection(model, first, second));
        MessageReceipt afterSwitch = store.receive(new IncomingMessage("thread", "alice", "second", "two"), first,
                model -> selection(model, first, second));
        MessageReceipt otherSession = store.receive(new IncomingMessage("other-thread", "alice", "other", "three"), first,
                model -> selection(model, first, second));

        assertThat(duplicate).isEqualTo(original);
        assertThat(jobSelection(original)).isEqualTo(first);
        assertThat(jobSelection(afterSwitch)).isEqualTo(second);
        assertThat(jobSelection(otherSession)).isEqualTo(first);
    }

    @Test
    void uses_the_selection_committed_before_intake_when_intake_precedes_a_switch() {
        ConversationStore store = store();
        ModelSelection first = new ModelSelection("google-primary", "v1:google-a");
        ModelSelection second = new ModelSelection("openai-primary", "v1:openai-b");

        MessageReceipt beforeSwitch = store.receive(new IncomingMessage("thread", "alice", "first", "one"), first,
                model -> selection(model, first, second));
        assertThat(store.selectModel(beforeSwitch.sessionId(), second)).contains(second);
        MessageReceipt afterSwitch = store.receive(new IncomingMessage("thread", "alice", "second", "two"), first,
                model -> selection(model, first, second));

        assertThat(jobSelection(beforeSwitch)).isEqualTo(first);
        assertThat(jobSelection(afterSwitch)).isEqualTo(second);
    }

    @Test
    void serializes_a_latch_released_session_switch_and_intake_without_a_partial_job_binding() throws Exception {
        ConversationStore store = store();
        ModelSelection first = new ModelSelection("google-primary", "v1:google-a");
        ModelSelection second = new ModelSelection("openai-primary", "v1:openai-b");
        MessageReceipt initial = store.receive(new IncomingMessage("thread", "alice", "initial", "one"), first,
                model -> selection(model, first, second));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection connection = dataSource().getConnection();
                PreparedStatement lock = connection.prepareStatement(
                        "select session_id from conversation_session where session_id = ? for update")) {
            connection.setAutoCommit(false);
            lock.setObject(1, UUID.fromString(initial.sessionId().value()));
            lock.executeQuery();
            Future<Optional<ModelSelection>> switchResult = executor.submit(() -> {
                ready.countDown();
                release.await();
                return store.selectModel(initial.sessionId(), second);
            });
            Future<MessageReceipt> intakeResult = executor.submit(() -> {
                ready.countDown();
                release.await();
                return store.receive(new IncomingMessage("thread", "alice", "concurrent", "two"), first,
                        model -> selection(model, first, second));
            });
            ready.await();
            release.countDown();
            connection.commit();

            MessageReceipt concurrent = intakeResult.get();
            assertThat(switchResult.get()).contains(second);
            assertThat(jobSelection(concurrent)).isIn(first, second);
            assertThat(jdbcTemplate.queryForObject("select selected_model from conversation_session where session_id = ?", String.class,
                    UUID.fromString(initial.sessionId().value()))).isEqualTo(second.model());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void keeps_exact_usage_checkpoints_separate_when_only_the_configured_fingerprint_changes() {
        ConversationStore store = store();
        ModelSelection original = new ModelSelection("openai-primary", "v1:configuration-a");
        ModelSelection changed = new ModelSelection("openai-primary", "v2:configuration-b");
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("openai-primary"), "gpt-5-mini", 32_000);
        String shape = "a".repeat(64);
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"), original,
                model -> Optional.of(original));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        store.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE, Optional.empty(), Optional.of(
                        new ConversationStore.UsageCheckpointData(original, descriptor, 1, 20, 10, 30, shape, 0))), NOW);

        assertThat(store.loadUsageCheckpoint(receipt.sessionId(), original, descriptor, shape, 0)).isPresent();
        assertThat(store.loadUsageCheckpoint(receipt.sessionId(), changed, descriptor, shape, 0)).isEmpty();
    }

    private ModelSelection jobSelection(MessageReceipt receipt) {
        return jdbcTemplate.queryForObject("select model_key, model_fingerprint from message_job where message_job_id = ?",
                (resultSet, row) -> new ModelSelection(resultSet.getString("model_key"), resultSet.getString("model_fingerprint")),
                UUID.fromString(receipt.messageJobId().value()));
    }

    private static java.util.Optional<ModelSelection> selection(
            String model,
            ModelSelection first,
            ModelSelection second) {
        if (first.model().equals(model)) {
            return java.util.Optional.of(first);
        }
        if (second.model().equals(model)) {
            return java.util.Optional.of(second);
        }
        return java.util.Optional.empty();
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
        return new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }
}
