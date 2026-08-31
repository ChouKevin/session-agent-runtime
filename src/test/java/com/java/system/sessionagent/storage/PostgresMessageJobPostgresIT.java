package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMessageJobPostgresIT {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @BeforeEach void migrate() { POSTGRES.start(); flyway().clean(); flyway().migrate(); }
    @AfterEach void clean() { flyway().clean(); }
    @Test void keeps_committed_history_visible_across_retry_and_global_job_ordering() {
        ConversationStore store = new PostgresConversationStore(dataSource(), Clock.fixed(Instant.parse("2026-08-31T10:00:01Z"), ZoneOffset.UTC));
        MessageReceipt first = store.receive(new IncomingMessage("thread", "alice", "first", "one"));
        MessageWorkClaim firstClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(firstClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("working")), ConversationStore.JobUpdate.KEEP_WORKING), Instant.parse("2026-08-31T10:00:01Z"));
        assertThat(store.scheduleRetry(firstClaim, Duration.ZERO)).isTrue();
        assertThat(store.loadHistory(first.sessionId())).extracting(message -> message.sequence().value()).containsExactly(1L, 2L);
        MessageWorkClaim reclaimed = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(reclaimed, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")), ConversationStore.JobUpdate.COMPLETE), Instant.parse("2026-08-31T10:00:02Z"));
        MessageReceipt second = store.receive(new IncomingMessage("thread", "alice", "second", "two"));
        MessageWorkClaim secondClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(secondClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("second done")), ConversationStore.JobUpdate.COMPLETE), Instant.parse("2026-08-31T10:00:03Z"));
        assertThat(store.loadHistory(second.sessionId())).extracting(message -> message.sequence().value()).containsExactly(1L, 2L, 3L, 4L, 5L);
    }
    private DriverManagerDataSource dataSource() { return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private Flyway flyway() { return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load(); }
}
