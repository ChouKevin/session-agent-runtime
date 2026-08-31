package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ObservationId;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresConversationCommitPostgresIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:01Z");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void migrateFreshSchema() {
        POSTGRES.start();
        flyway().clean();
        flyway().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void cleanSchema() {
        flyway().clean();
    }

    @Test
    void creates_only_the_final_provider_neutral_conversation_schema() {
        Set<String> tables = Set.copyOf(jdbcTemplate.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE' and table_name <> 'flyway_schema_history'
                """, String.class));
        Set<String> observationColumns = Set.copyOf(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'tool_observation'
                """, String.class));
        Set<String> jobColumns = Set.copyOf(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'message_job'
                """, String.class));
        String roleChecks = jdbcTemplate.queryForObject("""
                select string_agg(pg_get_constraintdef(constraint_record.oid), ' ')
                from pg_constraint constraint_record join pg_class relation on relation.oid = constraint_record.conrelid
                where relation.relname = 'session_message' and constraint_record.contype = 'c'
                """, String.class);
        String jobChecks = jdbcTemplate.queryForObject("""
                select string_agg(pg_get_constraintdef(constraint_record.oid), ' ')
                from pg_constraint constraint_record join pg_class relation on relation.oid = constraint_record.conrelid
                where relation.relname = 'message_job' and constraint_record.contype = 'c'
                """, String.class);

        assertThat(tables).containsExactlyInAnyOrder("conversation_session", "source_message", "session_message", "user_message",
                "message_job", "assistant_message", "tool_observation", "runtime_message");
        assertThat(observationColumns).containsExactlyInAnyOrder("session_id", "sequence", "role", "observation_id", "tool_name", "input", "output");
        assertThat(jobColumns).doesNotContain("reply_sequence");
        assertThat(roleChecks).contains("USER", "TOOL", "ASSISTANT", "RUNTIME").doesNotContain("FEEDBACK");
        assertThat(jobChecks).contains("model_calls >= 0").doesNotContain("between 0 and 12");
    }

    @Test
    void atomically_appends_final_tool_and_assistant_messages() {
        ConversationStore store = store();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        store.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.ToolObservationData(new ObservationId("4455b5ba-7b93-44cf-bd76-0d756e325eb5"), "lookup", "{}", "plain output"),
                new ConversationStore.AssistantData("done")), ConversationStore.JobUpdate.COMPLETE), NOW);

        assertThat(store.loadHistory(receipt.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L);
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class,
                java.util.UUID.fromString(receipt.messageJobId().value()))).isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject("select count(*) from tool_observation", Integer.class)).isEqualTo(1);
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
