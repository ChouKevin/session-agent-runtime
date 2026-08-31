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
    private DriverManagerDataSource dataSource() { return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private Flyway flyway() { return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load(); }
}
