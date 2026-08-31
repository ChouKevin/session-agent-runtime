package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMessageIntakePostgresIT {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @BeforeEach void migrate() { POSTGRES.start(); flyway().clean(); flyway().migrate(); }
    @AfterEach void clean() { flyway().clean(); }
    @Test void retains_inbound_idempotency_and_rejects_conflicting_duplicate_content() {
        ConversationStore store = new PostgresConversationStore(dataSource(), Clock.fixed(Instant.parse("2026-08-31T10:00:01Z"), ZoneOffset.UTC));
        MessageReceipt first = store.receive(new IncomingMessage("thread", "alice", "source", "first"));
        MessageReceipt duplicate = store.receive(new IncomingMessage("thread", "alice", "source", "first"));
        assertThat(duplicate).isEqualTo(first);
        assertThatThrownBy(() -> store.receive(new IncomingMessage("thread", "alice", "source", "changed"))).isInstanceOf(MessageConflictException.class);
    }
    @Test void allocates_ordered_sequences_under_concurrent_intake_without_duplicate_source_jobs() throws Exception {
        ConversationStore store = new PostgresConversationStore(dataSource(), Clock.fixed(Instant.parse("2026-08-31T10:00:01Z"), ZoneOffset.UTC));
        List<MessageReceipt> duplicates = concurrently(() -> store.receive(new IncomingMessage("thread", "alice", "same", "hello")));
        List<MessageReceipt> distinct = concurrently(() -> store.receive(new IncomingMessage("thread", "alice",
                "source-" + Thread.currentThread().threadId(), "message")));
        assertThat(duplicates).containsOnly(duplicates.getFirst());
        assertThat(distinct).extracting(MessageReceipt::messageJobId).doesNotContain(duplicates.getFirst().messageJobId());
        assertThat(store.loadHistory(duplicates.getFirst().sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L);
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
    private DriverManagerDataSource dataSource() { return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private Flyway flyway() { return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load(); }
}
