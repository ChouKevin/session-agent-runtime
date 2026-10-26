package com.java.system.sessionagent.storage.adapter.postgres;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.java.system.sessionagent.support.PostgresTestDatabase;
import org.junit.jupiter.api.AfterAll;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMessageIntakePostgresIT {
    private static final PostgresTestDatabase POSTGRES = PostgresTestDatabase.create();

    @AfterAll
    static void closeDatabase() {
        POSTGRES.close();
    }
    private static final ModelSelection TEST_SELECTION = new ModelSelection("test", "v1:test");
    @BeforeEach void migrate() { POSTGRES.start(); flyway().clean(); flyway().migrate(); }
    @AfterEach void clean() { flyway().clean(); }
    @Test void explicit_source_replay_precedes_full_capacity_and_new_command_classification() {
        ConversationStore store = new PostgresConversationStore(dataSource(), Clock.systemUTC(), new ObjectMapper());
        IncomingMessage command = new IncomingMessage("thread", "alice", "explicit", "/skill:review  question");
        MessageReceipt receipt = receive(store, command);
        for (int index = 0; index < 99; index++) {
            receive(store, new IncomingMessage("other-" + index, "alice", "other-" + index, "ordinary"));
        }
        assertThat(store.receive(command, TEST_SELECTION,
                model -> { throw new AssertionError("Duplicate must not select a model"); })).isEqualTo(receipt);
        assertThatThrownBy(() -> store.receive(
                new IncomingMessage("overflow", "alice", "overflow", "/skill:missing question"), TEST_SELECTION,
                model -> { throw new AssertionError("Capacity rejection must precede model lookup"); }))
                .hasMessage("Unfinished job capacity is full");
        assertThat(new JdbcTemplate(dataSource()).queryForObject("select count(*) from skill_command", Integer.class)).isEqualTo(1);
        assertThat(store.loadContextHistory(receipt.sessionId()).commands()).hasSize(1);
    }
    @Test void retains_inbound_idempotency_and_rejects_conflicting_duplicate_content() {
        ConversationStore store = new PostgresConversationStore(dataSource(), Clock.fixed(Instant.parse("2026-08-31T10:00:01Z"), ZoneOffset.UTC), new ObjectMapper());
        MessageReceipt first = receive(store, new IncomingMessage("thread", "alice", "source", "first"));
        MessageReceipt duplicate = receive(store, new IncomingMessage("thread", "alice", "source", "first"));
        assertThat(duplicate).isEqualTo(first);
        assertThatThrownBy(() -> receive(store, new IncomingMessage("thread", "alice", "source", "changed"))).isInstanceOf(MessageConflictException.class);
        assertThat(store.loadHistory(first.sessionId())).singleElement().isInstanceOfSatisfying(UserMessage.class, message -> {
            assertThat(message.message()).isEqualTo("first");
            assertThat(message.participantId()).isEqualTo("alice");
            assertThat(message.sequence().value()).isEqualTo(1L);
            assertThat(message.messageJobId()).contains(first.messageJobId());
        });
        assertThat(new JdbcTemplate(dataSource()).queryForObject("select count(*) from message_job", Integer.class)).isEqualTo(1);
    }
    @Test void allocates_ordered_sequences_under_concurrent_intake_without_duplicate_source_jobs() throws Exception {
        ConversationStore store = new PostgresConversationStore(dataSource(), Clock.fixed(Instant.parse("2026-08-31T10:00:01Z"), ZoneOffset.UTC), new ObjectMapper());
        List<MessageReceipt> duplicates = concurrently(() -> receive(store, new IncomingMessage("thread", "alice", "same", "hello")));
        List<MessageReceipt> distinct = concurrently(() -> receive(store, new IncomingMessage("thread", "alice",
                "source-" + Thread.currentThread().threadId(), "message")));
        assertThat(duplicates).containsOnly(duplicates.getFirst());
        assertThat(distinct).extracting(receipt -> receipt.messageJobId())
                .hasSize(2).doesNotHaveDuplicates()
                .doesNotContain(duplicates.getFirst().messageJobId());
        assertThat(distinct).extracting(receipt -> receipt.sessionId()).containsOnly(duplicates.getFirst().sessionId());
        assertThat(store.loadHistory(duplicates.getFirst().sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L);
        assertThat(new JdbcTemplate(dataSource()).queryForObject("select count(*) from message_job", Integer.class)).isEqualTo(3);
    }

    @Test void rejects_a_new_source_when_one_hundred_jobs_are_unfinished() {
        ConversationStore store = new PostgresConversationStore(dataSource(), Clock.fixed(Instant.parse("2026-08-31T10:00:01Z"), ZoneOffset.UTC), new ObjectMapper());

        for (int index = 0; index < 100; index++) {
            receive(store, new IncomingMessage("thread-" + index, "alice", "source-" + index, "message"));
        }

        assertThatThrownBy(() -> receive(store, new IncomingMessage("overflow", "alice", "overflow", "message")))
                .hasMessage("Unfinished job capacity is full");
        assertThat(new JdbcTemplate(dataSource()).queryForObject("select count(*) from message_job", Integer.class)).isEqualTo(100);
        assertThat(new JdbcTemplate(dataSource()).queryForObject("select count(*) from conversation_session where session_key = 'overflow'", Integer.class))
                .isZero();
    }
    private <T> List<T> concurrently(Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<T> synchronizedAction = () -> { ready.countDown(); start.await(); return action.call(); };
            Future<T> first = executor.submit(synchronizedAction);
            Future<T> second = executor.submit(synchronizedAction);
            ready.await();
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }
    private static MessageReceipt receive(ConversationStore store, IncomingMessage incomingMessage) {
        return store.receive(incomingMessage, TEST_SELECTION, model -> Optional.of(TEST_SELECTION));
    }
    private DriverManagerDataSource dataSource() { return POSTGRES.dataSource(); }
    private Flyway flyway() { return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load(); }
}
