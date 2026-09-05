package com.java.system.sessionagent.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.application.ConversationMessageService;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessageSource;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.storage.PostgresConversationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackPostgresIntakePostgresIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        flyway().clean();
        flyway().migrate();
    }

    @AfterEach
    void clean() {
        flyway().clean();
    }

    @Test
    void atomically_persists_a_slack_binding_receipt_and_provider_neutral_message_job() {
        SlackPostgresRootIntake intake = intake();

        MessageReceipt receipt = intake.receive(rootIntake("T1", "C1", "1.000001"));
        MessageReceipt replayReceipt = intake.receive(rootIntake("T1", "C1", "1.000001"));

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
        assertThat(receipt.sessionId().value()).isNotBlank();
        assertThat(replayReceipt).isEqualTo(receipt);
        assertThat(count(jdbcTemplate, "select count(*) from slack_thread_binding")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from slack_event_receipt")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from source_message where source_type = 'slack'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from session_message where role = 'USER'")).isEqualTo(1);
        assertThat(count(jdbcTemplate, "select count(*) from message_job")).isEqualTo(1);
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

    private SlackPostgresRootIntake intake() {
        DriverManagerDataSource dataSource = dataSource();
        Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
        ConversationStore conversationStore = new PostgresConversationStore(dataSource, clock, new ObjectMapper());
        MessageIntakePort messageIntakePort = new ConversationMessageService(conversationStore);
        return new SlackPostgresRootIntake(dataSource, messageIntakePort, clock);
    }

    private static SlackRootIntake rootIntake(String teamId, String channelId, String messageTs) {
        IncomingMessage message = new IncomingMessage(
                IncomingMessageSource.SLACK,
                "slack/" + teamId + "/" + channelId + "/" + messageTs,
                "U1",
                "slack/" + teamId + "/" + channelId + "/" + messageTs,
                "hello");
        return new SlackRootIntake(teamId, channelId, messageTs, messageTs, message);
    }

    private static int count(JdbcTemplate jdbcTemplate, String query) {
        Integer count = jdbcTemplate.queryForObject(query, Integer.class);
        return Objects.requireNonNull(count, "Count query must return a value").intValue();
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }
}
