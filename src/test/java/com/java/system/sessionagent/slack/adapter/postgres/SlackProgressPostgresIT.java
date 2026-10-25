package com.java.system.sessionagent.slack.adapter.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.application.JobCancellationService;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.storage.adapter.postgres.jdbc.PostgresIntakeCapacity;
import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.JobCancellation;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.skill.adapter.tool.SkillToolCatalog;
import com.java.system.sessionagent.skill.domain.SkillMetadata;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.skill.domain.SkillResource;
import com.java.system.sessionagent.skill.port.SkillLibrary;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresSkillLoadObserver;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.java.system.sessionagent.tool.port.ToolInvocation;
import com.java.system.sessionagent.tool.port.ToolExecutionContext;
import com.java.system.sessionagent.tool.port.ToolExecutionAuthority;
import com.java.system.sessionagent.tool.port.SkillReadReceipt;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.slack.application.MessageHandler;
import com.java.system.sessionagent.slack.application.SlackProgressWorker;
import com.java.system.sessionagent.slack.port.out.SlackWebApi;
import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.configuration.SlackProgressProperties;
import com.java.system.sessionagent.slack.domain.*;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresConversationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlackProgressPostgresIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private SlackPostgresDeliveryStore delivery;
    private SlackPostgresProgressStore progress;
    private MessageHandler handler;

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        delivery = new SlackPostgresDeliveryStore(dataSource);
        progress = store();
        PostgresConversationStore conversations = new PostgresConversationStore(dataSource, Clock.systemUTC(), new ObjectMapper());
        handler = new MessageHandler("UBOT", new PostgresMessageSubmission(dataSource,
                new MessageService(conversations, TestConfiguredModels.withDefault("test"), new NoOpConversationTelemetry()),
                new JobCancellationService(conversations), Clock.systemUTC(), new PostgresIntakeCapacity(dataSource)));
    }

    @Test
    void migration_expands_observed_states_without_removing_the_distinct_notice_kind_check() {
        accept("1.000001", "");
        sendNotice("notice");
        progress.refresh(claim()).orElseThrow();

        assertThatThrownBy(() -> jdbc.update("update slack_question_progress set notice_kind='ANSWER'"))
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        assertThat(jdbc.update("update slack_question_progress set observed_state='WAITING_USER'")).isOne();
        assertThatThrownBy(() -> jdbc.update("update slack_question_progress set observed_state='UNSUPPORTED'"))
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void caches_exact_threaded_question_link_with_claim_fencing_and_reuses_it_after_restart() {
        accept("1.000001", "");
        UUID reply = accept("2.000002", "1.000001");
        sendNotice("root-notice"); sendNotice("reply-notice");
        jdbc.update("update slack_question_progress set retired=true where message_job_id<>?", reply);
        SlackProgressClaim old = claim();
        progress.refresh(old).orElseThrow();
        String link = "https://example.slack.com/archives/C1/p2000002?thread_ts=1.000001&cid=C1";
        assertThatThrownBy(() -> progress.saveQuestionPermalink(old,
                "https://example.slack.com/archives/C1/p1000001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> progress.saveQuestionPermalink(old,
                "https://example.slack.com/archives/C2/p2000002")).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("update slack_question_progress set locked_until=clock_timestamp()-interval '1 second' where message_job_id=?", reply);
        assertThat(progress.saveQuestionPermalink(old, link)).isFalse();
        openGate();
        SlackProgressClaim current = claim();
        assertThat(progress.saveQuestionPermalink(old, link)).isFalse();
        assertThat(progress.saveQuestionPermalink(current, link)).isTrue();
        assertThat(progress.markSent(current, 1)).isFalse();
        progress = store();
        openGate();
        SlackProgressUpdate reconstructed = progress.refresh(claim()).orElseThrow();
        assertThat(reconstructed.questionPermalink()).contains(link);
        assertThat(reconstructed.sourceMessageTs()).isEqualTo("2.000002");
        assertThat(reconstructed.request().text()).contains("<" + link + "|原始問題>");
        assertThat(reconstructed.request().messageTs()).isEqualTo("reply-notice");
        assertThat(jdbc.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sum(model_calls) from message_job", Integer.class)).isZero();
    }

    @Test
    void unresolved_question_never_renders_an_undocumented_message_deep_link() {
        accept("1.000001", ""); sendNotice("notice");
        SlackProgressUpdate update = progress.refresh(claim()).orElseThrow();
        assertThat(update.request().text()).doesNotContain("slack://", "|原始問題>");
    }

    @Test
    void actual_transient_job_retry_is_not_terminal_failure() {
        UUID job = accept("1.000001", ""); sendNotice("notice");
        PostgresConversationStore conversations = new PostgresConversationStore(dataSource, Clock.systemUTC(), new ObjectMapper());
        MessageWorkClaim modelClaim = conversations.claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        assertThat(conversations.scheduleRetry(modelClaim, Duration.ofSeconds(1), JobFailure.MODEL_UNAVAILABLE)).isTrue();
        assertThat(jdbc.queryForObject("select failure_code from message_job where message_job_id=?", String.class, job))
                .isEqualTo("MODEL_UNAVAILABLE");
        SlackProgressClaim progressClaim = claim();
        SlackProgressUpdate update = progress.refresh(progressClaim).orElseThrow();
        assertThat(update.request().text()).contains("重試");
        assertThat(update.terminal()).isFalse();
        assertThat(progress.markSent(progressClaim, update.revision())).isTrue();
        assertThat(jdbc.queryForObject("select terminal_settle_until from slack_question_progress", java.sql.Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("select retired from slack_question_progress", Boolean.class)).isFalse();
    }

    @Test
    void historical_failed_and_cancelled_jobs_with_delivered_answers_are_not_reopened() {
        for (String terminal : List.of("FAILED", "CANCELLED")) {
            String source = terminal.equals("FAILED") ? "1.000001" : "2.000002";
            UUID job = accept(source, ""); sendNotice("notice-" + terminal);
            complete(job);
            if (terminal.equals("FAILED")) {
                jdbc.update("update message_job set failure_code='MODEL_UNAVAILABLE' where message_job_id=?", job);
            } else {
                jdbc.update("update message_job set status='CANCELLED',cancellation_requested_at=clock_timestamp() where message_job_id=?", job);
            }
            answer(job);
            sendNotice("final-" + terminal);
            jdbc.update("delete from slack_question_progress where message_job_id=?", job);
            progress.discover();
            assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isZero();
        }
    }

    @Test
    void registration_failure_rolls_back_parent_and_part_confirmation_and_can_retry_safely() {
        accept("1.000001", "");
        SlackDeliveryClaim notice = delivery.claimNext("notice", Duration.ofMinutes(1), 5).orElseThrow();
        SlackDeliveryPart part = delivery.nextPart(notice, Duration.ofMinutes(1)).orElseThrow();
        jdbc.execute("""
                create function fail_progress_registration() returns trigger language plpgsql as $$
                begin raise exception 'injected registration failure'; end;
                $$
                """);
        jdbc.execute("create trigger fail_progress_registration before insert on slack_question_progress for each row execute function fail_progress_registration()");
        assertThatThrownBy(() -> delivery.markPartSent(notice, part, "confirmed"))
                .hasStackTraceContaining("injected registration failure");
        assertThat(jdbc.queryForObject("select status from slack_delivery where delivery_id=?", String.class, notice.deliveryId())).isEqualTo("WORKING");
        assertThat(jdbc.queryForObject("select status from slack_delivery_part where delivery_id=?", String.class, notice.deliveryId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select slack_message_ts from slack_delivery where delivery_id=?", String.class, notice.deliveryId())).isNull();
        assertThat(jdbc.queryForObject("select slack_message_ts from slack_delivery_part where delivery_id=?", String.class, notice.deliveryId())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isZero();
        jdbc.execute("drop trigger fail_progress_registration on slack_question_progress");
        jdbc.execute("drop function fail_progress_registration()");
        assertThat(delivery.markPartSent(notice, part, "confirmed")).isTrue();
        assertThat(jdbc.queryForObject("select status from slack_delivery where delivery_id=?", String.class, notice.deliveryId())).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select status from slack_delivery_part where delivery_id=?", String.class, notice.deliveryId())).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select target_message_ts from slack_question_progress", String.class)).isEqualTo("confirmed");
    }

    @Test
    void retry_and_failed_delivery_never_claim_success_and_changed_state_resets_failure_budget() {
        UUID job = accept("1.000001", ""); sendNotice("notice");
        jdbc.update("update message_job set status='RETRY' where message_job_id=?", job);
        SlackProgressClaim claim = claim();
        SlackProgressUpdate retry = progress.refresh(claim).orElseThrow();
        assertThat(retry.request().text()).contains("重試");
        assertThat(progress.recordFailure(claim, retry.revision(), SlackDeliveryFailureCategory.TRANSIENT, Optional.empty())).isTrue();
        complete(job);
        UUID answer = answer(job);
        jdbc.update("update slack_delivery set status='FAILED',failure_category='PERMANENT' where delivery_id=?", answer);
        openGate();
        SlackProgressClaim failedClaim = claim();
        SlackProgressUpdate failure = progress.refresh(failedClaim).orElseThrow();
        assertThat(failure.request().text()).contains("傳送失敗");
        assertThat(failure.terminal()).isTrue();
        assertThat(jdbc.queryForObject("select consecutive_failures from slack_question_progress", Integer.class)).isZero();
        assertThat(progress.recordFailure(failedClaim, failure.revision(), SlackDeliveryFailureCategory.PERMANENT, Optional.empty())).isTrue();
        progress.discover();
        assertThat(progress.claimNext("retired", Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void recovery_discovers_missing_active_targets_but_not_historical_success_or_unconfirmed_notices() {
        UUID job = accept("1.000001", ""); sendNotice("notice");
        jdbc.update("delete from slack_question_progress");
        progress.discover();
        assertThat(jdbc.queryForObject("select target_message_ts from slack_question_progress", String.class)).isEqualTo("notice");
        complete(job);
        UUID answer = answer(job);
        jdbc.update("insert into slack_delivery_part(delivery_id,part_index,part_text,status,slack_message_ts,sent_at) values (?,0,'answer','SENT','final',clock_timestamp())", answer);
        jdbc.update("update slack_delivery set status='SENT',items_prepared=true,slack_message_ts='final',sent_at=clock_timestamp() where delivery_id=?", answer);
        jdbc.update("delete from slack_question_progress");
        progress.discover();
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isZero();
        accept("2.000002", "1.000001");
        progress.discover();
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isZero();
    }

    @Test
    void targets_each_confirmed_notice_for_its_own_job() {
        UUID first = accept("1.000001", "");
        UUID second = accept("2.000002", "1.000001");
        sendNotice("notice-one");
        sendNotice("notice-two");
        SlackProgressClaim claim = claim();
        SlackProgressUpdate update = progress.refresh(claim).orElseThrow();
        assertThat(update.request().messageTs()).isEqualTo(claim.messageJobId().equals(first) ? "notice-one" : "notice-two");
        assertThat(update.request().text()).contains("排隊", "Z", "不代表");
        assertThat(List.of(first, second)).contains(claim.messageJobId());
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isEqualTo(2);
        assertThat(progress.markSent(claim, update.revision())).isTrue();
        openGate();
        SlackProgressClaim other = claim();
        SlackProgressUpdate otherUpdate = progress.refresh(other).orElseThrow();
        assertThat(other.messageJobId()).isNotEqualTo(claim.messageJobId());
        assertThat(otherUpdate.request().messageTs()).isEqualTo(other.messageJobId().equals(first) ? "notice-one" : "notice-two");
        assertThat(otherUpdate.sourceMessageTs()).isEqualTo(other.messageJobId().equals(first) ? "1.000001" : "2.000002");
    }

    @Test
    void source_replay_and_reconstruction_reuse_target() {
        UUID job = accept("1.000001", "");
        sendNotice("notice-one");
        accept("1.000001", "");
        progress = store();
        progress.discover();
        SlackProgressUpdate update = progress.refresh(claim()).orElseThrow();
        assertThat(update.request().messageTs()).isEqualTo("notice-one");
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress where message_job_id = ?", Integer.class, job)).isOne();
    }

    @Test
    void processing_requires_unexpired_lease() {
        UUID job = accept("1.000001", "");
        sendNotice("notice");
        jdbc.update("update message_job set failure_code='MODEL_UNAVAILABLE' where message_job_id=?", job);
        jdbc.update("update message_job set status='WORKING', worker_id='model', claim_number=1, locked_until=clock_timestamp()+interval '1 minute', phase='MODEL_CALL', phase_started_at=clock_timestamp() where message_job_id=?", job);
        SlackProgressClaim claim = claim();
        SlackProgressUpdate active = progress.refresh(claim).orElseThrow();
        assertThat(active.request().text()).contains("模型");
        jdbc.update("update message_job set phase='TOOL_EXECUTION',current_tool_name='secret-tool' where message_job_id=?", job);
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("查詢").doesNotContain("secret-tool");
        jdbc.update("update message_job set phase='HISTORY_COMMIT',current_tool_name=null where message_job_id=?", job);
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("準備回覆").doesNotContain("已完成");
        jdbc.update("update message_job set locked_until=clock_timestamp()-interval '1 second' where message_job_id=?", job);
        SlackProgressUpdate interrupted = progress.refresh(claim).orElseThrow();
        assertThat(interrupted.revision()).isGreaterThan(active.revision());
        assertThat(interrupted.request().text()).contains("中斷");
        assertThat(progress.markSent(claim, active.revision())).isFalse();
        jdbc.update("update message_job set cancellation_requested_at=clock_timestamp() where message_job_id=?", job);
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("停止中");
    }

    @Test
    void terminal_job_waits_for_all_answer_parts() {
        UUID job = accept("1.000001", "");
        sendNotice("notice");
        complete(job);
        SlackProgressClaim claim = claim();
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("傳送");
        UUID answer = answer(job);
        jdbc.update("insert into slack_delivery_part(delivery_id,part_index,part_text,status) values (?,0,'one','PENDING'), (?,1,'two','PENDING')", answer, answer);
        jdbc.update("update slack_delivery set status='SENT',items_prepared=true,slack_message_ts='answer',sent_at=clock_timestamp() where delivery_id=?", answer);
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("傳送");
        jdbc.update("update slack_delivery_part set status='SENT',slack_message_ts='part',sent_at=clock_timestamp() where delivery_id=?", answer);
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("已完成");
        jdbc.update("update message_job set failure_code='MODEL_UNAVAILABLE' where message_job_id=?", job);
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("處理失敗");
    }

    @Test
    void completion_requires_preparation_and_every_file_confirmation_and_recovery_does_not_reopen_success() {
        UUID job = accept("1.000001", ""); sendNotice("notice");
        complete(job);
        UUID answer = answer(job);
        SlackProgressClaim claim = claim();
        assertState(claim, SlackProgressState.DELIVERING);
        jdbc.update("""
                insert into slack_delivery_part(delivery_id,part_index,part_text,presentation,status,slack_message_ts,sent_at)
                values (?,0,'answer','MARKDOWN','SENT','text',clock_timestamp()),
                       (?,1,'Report','FILE','SENT','F1',clock_timestamp()), (?,2,'Report','FILE','PENDING',null,null)
                """, answer, answer, answer);
        for (int index : List.of(1, 2)) {
            jdbc.update("""
                    insert into slack_delivery_upload(delivery_id,part_index,identity,filename,title,media_type,content,state,file_id,upload_url)
                    values (?,?,?,'report.md','Report','text/markdown',? ,?,?,'https://files.slack.com/upload/v1/test')
                    """, answer, index, UUID.randomUUID(), new byte[] {1}, index == 1 ? "SENT" : "UPLOADED", "F" + index);
        }
        jdbc.update("update slack_delivery set status='SENT',slack_message_ts='text',sent_at=clock_timestamp() where delivery_id=?", answer);
        assertState(claim, SlackProgressState.DELIVERING);
        jdbc.update("update slack_delivery set items_prepared=true where delivery_id=?", answer);
        assertState(claim, SlackProgressState.DELIVERING);
        jdbc.update("update slack_delivery_part set status='SENT',slack_message_ts='F2',sent_at=clock_timestamp() where delivery_id=? and part_index=2", answer);
        assertState(claim, SlackProgressState.DELIVERING);
        jdbc.update("update slack_delivery_upload set state='SENT' where delivery_id=? and part_index=2", answer);
        assertState(claim, SlackProgressState.COMPLETED);
        jdbc.update("delete from slack_question_progress where message_job_id=?", job);
        progress = store();
        progress.discover();
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isZero();
    }

    @Test
    void failed_or_unconfirmed_file_is_delivery_failure_without_overriding_job_failure_or_cancellation() {
        UUID job = accept("1.000001", ""); sendNotice("notice");
        complete(job);
        UUID answer = answer(job);
        jdbc.update("insert into slack_delivery_part(delivery_id,part_index,part_text,presentation,status) values (?,0,'Report','FILE','PENDING')", answer);
        jdbc.update("""
                insert into slack_delivery_upload(delivery_id,part_index,identity,filename,title,media_type,content,state,file_id,upload_url)
                values (?,0,?,'report.md','Report','text/markdown',?,'FAILED','F1','https://files.slack.com/upload/v1/test')
                """, answer, UUID.randomUUID(), new byte[] {1});
        SlackProgressClaim claim = claim();
        assertState(claim, SlackProgressState.DELIVERY_FAILED);
        jdbc.update("update slack_delivery_upload set state='UNCONFIRMED' where delivery_id=?", answer);
        assertState(claim, SlackProgressState.DELIVERY_FAILED);
        jdbc.update("update message_job set failure_code='MODEL_UNAVAILABLE' where message_job_id=?", job);
        assertState(claim, SlackProgressState.FAILED);
        jdbc.update("update message_job set status='CANCELLED',failure_code=null,cancellation_requested_at=clock_timestamp() where message_job_id=?", job);
        assertState(claim, SlackProgressState.CANCELLED);
        assertThat(jdbc.queryForObject("select model_calls from message_job where message_job_id=?", Integer.class, job)).isZero();
    }

    private void assertState(SlackProgressClaim claim, SlackProgressState expected) {
        assertThat(progress.refresh(claim)).isPresent();
        assertThat(jdbc.queryForObject("select observed_state from slack_question_progress where message_job_id=?",
                String.class, claim.messageJobId())).isEqualTo(expected.name());
    }

    @Test
    void fast_completion_is_not_reported_as_running() {
        UUID job = accept("1.000001", "");
        complete(job);
        sendNotice("notice");
        UUID answer = answer(job);
        SlackDeliveryClaim answerClaim = delivery.claimNext("answer", Duration.ofMinutes(1), 5).orElseThrow();
        SlackDeliveryPart part = delivery.nextPart(answerClaim, Duration.ofMinutes(1)).orElseThrow();
        assertThat(delivery.markPartSent(answerClaim, part, "final")).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isOne();
        progress.discover();
        SlackProgressUpdate update = progress.refresh(claim()).orElseThrow();
        assertThat(update.terminal()).isTrue();
        assertThat(update.request().text()).contains("已完成");
        assertThat(answer).isNotNull();
    }

    @Test
    void workspace_cooldown_coordinates_claims() throws Exception {
        accept("1.000001", ""); accept("2.000002", "1.000001");
        sendNotice("one"); sendNotice("two");
        try (java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<SlackProgressClaim>> first = executor.submit(() -> store().claimNext("one", Duration.ofMinutes(1)));
            Future<Optional<SlackProgressClaim>> second = executor.submit(() -> store().claimNext("two", Duration.ofMinutes(1)));
            assertThat(List.of(first.get(), second.get()).stream().filter(Optional::isPresent).count()).isOne();
        }
        jdbc.update("update slack_progress_workspace set next_allowed_at=clock_timestamp()-interval '1 second'");
        assertThat(progress.claimNext("next", Duration.ofMinutes(1))).isPresent();
    }

    @Test
    void retry_fences_survive_timestamp_refresh_and_honor_workspace_retry_after() {
        accept("1.000001", ""); sendNotice("notice");
        List<java.time.Instant> observations = new java.util.ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            SlackProgressClaim claim = claim();
            SlackProgressUpdate update = progress.refresh(claim).orElseThrow();
            observations.add(update.observedAt());
            assertThat(progress.refresh(claim).orElseThrow().observedAt()).isEqualTo(update.observedAt());
            assertThat(progress.refresh(claim).orElseThrow().revision()).isEqualTo(update.revision());
            assertThat(progress.recordFailure(claim, update.revision(), SlackDeliveryFailureCategory.RATE_LIMIT, Optional.of(Duration.ofSeconds(9)))).isTrue();
            assertThat(progress.markSent(claim, update.revision())).isFalse();
            assertThat(jdbc.queryForObject("select consecutive_failures from slack_question_progress", Integer.class)).isEqualTo(attempt);
            assertThat(progress.claimNext("blocked", Duration.ofSeconds(1))).isEmpty();
            jdbc.update("update slack_progress_workspace set next_allowed_at=clock_timestamp()-interval '1 second'");
            jdbc.update("update slack_question_progress set next_due_at=clock_timestamp()-interval '1 second'");
        }
        assertThat(observations.stream().distinct().count()).isOne();
        assertThat(progress.claimNext("exhausted", Duration.ofSeconds(1))).isEmpty();
        progress.discover();
        assertThat(jdbc.queryForObject("select retired from slack_question_progress", Boolean.class)).isTrue();
    }

    @Test
    void unchanged_running_status_refreshes_only_when_due_using_database_utc_time() {
        UUID job = accept("1.000001", ""); sendNotice("notice");
        jdbc.update("update message_job set status='WORKING',worker_id='model',claim_number=1,locked_until=clock_timestamp()+interval '2 minutes' where message_job_id=?", job);
        SlackProgressClaim first = claim();
        java.time.Instant before = java.time.Instant.now();
        SlackProgressUpdate initial = progress.refresh(first).orElseThrow();
        assertThat(initial.observedAt()).isBetween(before.minusSeconds(1), java.time.Instant.now().plusSeconds(1));
        assertThat(progress.markSent(first, initial.revision())).isTrue();
        openGate();
        assertThat(progress.claimNext("too-soon", Duration.ofMinutes(1))).isEmpty();
        jdbc.update("update slack_question_progress set next_due_at=clock_timestamp()-interval '1 second'");
        SlackProgressClaim next = claim();
        SlackProgressUpdate refreshed = progress.refresh(next).orElseThrow();
        assertThat(refreshed.observedAt()).isAfter(initial.observedAt());
        assertThat(refreshed.revision()).isGreaterThan(initial.revision());
    }

    @Test
    void lease_loss_and_terminal_settling_require_owned_reconciliation() {
        UUID job = accept("1.000001", ""); sendNotice("notice");
        jdbc.update("update message_job set status='CANCELLED',completed_at=clock_timestamp(),cancellation_requested_at=clock_timestamp() where message_job_id=?", job);
        SlackProgressClaim stale = claim();
        SlackProgressUpdate update = progress.refresh(stale).orElseThrow();
        assertThat(update.request().text()).contains("已取消");
        jdbc.update("update slack_question_progress set locked_until=clock_timestamp()-interval '1 second'");
        assertThat(progress.refresh(stale)).isEmpty();
        assertThat(progress.markSent(stale, update.revision())).isFalse();
        openGate();
        SlackProgressClaim fresh = claim();
        SlackProgressUpdate terminal = progress.refresh(fresh).orElseThrow();
        assertThat(progress.markSent(fresh, terminal.revision())).isTrue();
        assertThat(jdbc.queryForObject("select retired from slack_question_progress", Boolean.class)).isFalse();
        jdbc.update("update slack_question_progress set terminal_settle_until=clock_timestamp()-interval '1 second', next_due_at=clock_timestamp()-interval '1 second'");
        openGate();
        SlackProgressClaim reconciliation = claim();
        SlackProgressUpdate finalUpdate = progress.refresh(reconciliation).orElseThrow();
        assertThat(progress.markSent(reconciliation, finalUpdate.revision())).isTrue();
        progress.discover();
        assertThat(jdbc.queryForObject("select retired from slack_question_progress", Boolean.class)).isTrue();
    }

    @Test
    void recovery_is_bounded_and_registration_is_in_the_notice_confirmation_transaction() {
        for (int index = 1; index <= 101; index++) {
            UUID job = accept(index + ".000001", "");
            sendNotice("notice-" + index);
            complete(job);
        }
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isEqualTo(101);
        jdbc.update("delete from slack_question_progress");
        progress.discover();
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isEqualTo(100);
        progress.discover();
        assertThat(jdbc.queryForObject("select count(*) from slack_question_progress", Integer.class)).isEqualTo(101);
    }

    @Test
    void worker_http_outside_transactions_rejects_old_ack_and_recovers_latest_after_lease_expiry() {
        UUID job = accept("1.000001", ""); sendNotice("notice");
        cacheQuestionLinks();
        List<SlackUpdateRequest> requests = new java.util.ArrayList<>();
        SlackWebApi api = updating(request -> {
            requests.add(request);
            // A separate connection sees committed claim ownership while HTTP is in flight.
            assertThat(jdbc.queryForObject("select worker_id from slack_question_progress", String.class)).isEqualTo("http-worker");
            if (requests.size() == 1) {
                jdbc.update("update message_job set status='CANCELLED',completed_at=clock_timestamp(),cancellation_requested_at=clock_timestamp() where message_job_id=?", job);
            }
        });
        SlackProgressWorker worker = new SlackProgressWorker(progress, api, new SlackDeliveryProperties(), "http-worker");
        assertThat(worker.poll()).isTrue();
        assertThat(jdbc.queryForObject("select last_sent_revision from slack_question_progress", Long.class)).isZero();
        openGate();
        assertThat(worker.poll()).isFalse();
        jdbc.update("update slack_question_progress set locked_until=clock_timestamp()-interval '1 second'");
        assertThat(worker.poll()).isTrue();
        assertThat(requests).hasSize(2).allSatisfy(request -> assertThat(request.messageTs()).isEqualTo("notice"));
        assertThat(requests.get(0).text()).contains("排隊");
        assertThat(requests.get(1).text()).contains("已取消");
        assertThat(jdbc.queryForObject("select retired from slack_question_progress", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select terminal_settle_until >= last_sent_at + interval '30 seconds' from slack_question_progress", Boolean.class)).isTrue();
        openGate();
        assertThat(worker.poll()).isFalse();
        jdbc.update("update slack_question_progress set terminal_settle_until=clock_timestamp()-interval '1 second',next_due_at=clock_timestamp()-interval '1 second'");
        assertThat(worker.poll()).isTrue();
        assertThat(requests).hasSize(3);
        assertThat(requests.get(2).text()).contains("已取消");
        assertThat(jdbc.queryForObject("select retired from slack_question_progress", Boolean.class)).isTrue();
    }

    @Test
    void worker_rate_limit_gates_other_questions_and_permanent_target_failure_leaves_answer_delivery_intact() {
        UUID first = accept("1.000001", ""); sendNotice("one");
        accept("2.000002", "1.000001"); sendNotice("two");
        cacheQuestionLinks();
        List<SlackUpdateRequest> requests = new java.util.ArrayList<>();
        SlackProgressWorker worker = new SlackProgressWorker(progress, updating(request -> {
            requests.add(request);
            if (requests.size() == 1) {
                throw new SlackPostFailure(SlackDeliveryFailureCategory.RATE_LIMIT, Optional.of(Duration.ofSeconds(9)));
            }
            throw new SlackPostFailure(SlackDeliveryFailureCategory.PERMANENT, Optional.empty());
        }), new SlackDeliveryProperties(), "http-worker");
        assertThat(worker.poll()).isTrue();
        assertThat(worker.poll()).isFalse();
        assertThat(requests).hasSize(1);
        assertThat(jdbc.queryForObject("select next_allowed_at > clock_timestamp()+interval '8 seconds' from slack_progress_workspace", Boolean.class)).isTrue();
        complete(first);
        UUID answerId = answer(first);
        jdbc.update("update slack_question_progress set next_due_at=clock_timestamp()-interval '1 day' where message_job_id=?", first);
        openGate();
        assertThat(worker.poll()).isTrue();
        assertThat(requests.get(1).messageTs()).isEqualTo("one");
        assertThat(requests.get(1).text()).contains("傳送");
        assertThat(jdbc.queryForObject("select retired from slack_question_progress where message_job_id=?", Boolean.class, first)).isTrue();
        assertThat(jdbc.queryForObject("select status from slack_delivery where delivery_id=?", String.class, answerId)).isEqualTo("PENDING");
        SlackDeliveryClaim answerClaim = delivery.claimNext("answer", Duration.ofMinutes(1), 5).orElseThrow();
        SlackDeliveryPart part = delivery.nextPart(answerClaim, Duration.ofMinutes(1)).orElseThrow();
        assertThat(delivery.markPartSent(answerClaim, part, "final")).isTrue();
        assertThat(jdbc.queryForObject("select status from slack_delivery where delivery_id=?", String.class, answerId)).isEqualTo("SENT");
    }

    @Test
    void explicit_load_only_becomes_loaded_after_skill_commit_and_its_ack_removes_the_badge() {
        UUID job = acceptSkill("1.000001");
        sendNotice("notice");
        PostgresConversationStore conversations = conversations();
        MessageWorkClaim work = conversations.claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        conversations.startSkillCommand(work);
        SlackProgressClaim presentation = claim();
        SlackProgressUpdate loading = progress.refresh(presentation).orElseThrow();
        assertSkill(loading, "review", SlackProgressUpdate.SkillPresentation.Kind.STARTED);

        conversations.resolveSkillCommand(work, new SkillReadResult.Success(resource("review")), Instant.now());
        assertThat(progress.markSent(presentation, loading.revision())).isFalse();
        SlackProgressUpdate loaded = progress.refresh(presentation).orElseThrow();
        assertSkill(loaded, "review", SlackProgressUpdate.SkillPresentation.Kind.COMMITTED_SUCCESS);
        assertThat(loaded.revision()).isGreaterThan(loading.revision());
        assertThat(progress.markSent(presentation, loaded.revision())).isTrue();

        openGate();
        SlackProgressClaim ordinaryClaim = claim();
        SlackProgressUpdate ordinary = progress.refresh(ordinaryClaim).orElseThrow();
        assertThat(ordinary.skillPresentation()).isEmpty();
        assertThat(ordinary.request().text()).doesNotContain("review", "PRIVATE_SKILL_BODY");
        assertThat(jdbc.queryForObject("select retired from slack_question_progress where message_job_id=?", Boolean.class, job)).isFalse();
        assertThat(jdbc.queryForObject("select model_calls from message_job where message_job_id=?", Integer.class, job)).isZero();
    }

    @Test
    void fast_automatic_commit_survives_terminal_completion_notice_registration_and_restart_before_first_poll() {
        UUID job = accept("1.000001", "");
        PostgresConversationStore conversations = conversations();
        MessageWorkClaim work = conversations.claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        ToolOutput output = automaticRead(work, "review", new SkillReadResult.Success(resource("review")));
        commitRead(work, output);
        conversations.append(work, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.AssistantData("answer")), ConversationStore.JobUpdate.COMPLETE,
                ConversationStore.CompletionDisposition.ANSWER), Instant.now());
        sendNotice("notice-after-completion");
        delivery.discover();
        sendNotice("answer");
        jdbc.update("delete from slack_question_progress where message_job_id=?", job);
        progress = store();
        progress.discover();

        SlackProgressClaim first = claim();
        SlackProgressUpdate loaded = progress.refresh(first).orElseThrow();
        assertSkill(loaded, "review", SlackProgressUpdate.SkillPresentation.Kind.COMMITTED_SUCCESS);
        assertThat(loaded.request().messageTs()).isEqualTo("notice-after-completion");
        assertThat(progress.markSent(first, loaded.revision())).isTrue();
        assertThat(jdbc.queryForObject("select retired from slack_question_progress", Boolean.class)).isFalse();

        openGate();
        SlackProgressClaim terminal = claim();
        SlackProgressUpdate completed = progress.refresh(terminal).orElseThrow();
        assertThat(completed.skillPresentation()).isEmpty();
        assertThat(completed.terminal()).isTrue();
        assertThat(completed.request().text()).doesNotContain("review");
        assertState(terminal, SlackProgressState.COMPLETED);
    }

    @Test
    void second_committed_outcome_during_http_is_not_acknowledged_or_coalesced_with_the_first() {
        accept("1.000001", "");
        sendNotice("notice");
        cacheQuestionLinks();
        MessageWorkClaim work = conversations().claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        ToolOutput first = automaticRead(work, "review", new SkillReadResult.Success(resource("review")));
        commitRead(work, first);
        List<SlackUpdateRequest> requests = new java.util.ArrayList<>();
        SlackProgressWorker worker = new SlackProgressWorker(progress, updating(request -> {
            requests.add(request);
            if (requests.size() == 1) {
                commitRead(work, automaticRead(work, "audit", new SkillReadResult.Success(resource("audit"))));
            }
        }), new SlackDeliveryProperties(), "http-worker");

        assertThat(worker.poll()).isTrue();
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().text()).contains("review").doesNotContain("audit");
        assertThat(jdbc.queryForObject("select count(*) from skill_load_attempt where result_sent_at is not null", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from skill_load_attempt where result_sent_at is null", Integer.class)).isOne();
        openGate();
        assertThat(worker.poll()).isTrue();
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().text()).contains("audit").doesNotContain("review");
        openGate();
        assertThat(worker.poll()).isTrue();
        assertThat(requests.getLast().text()).doesNotContain("audit", "review");
        assertThat(jdbc.queryForObject("select count(*) from skill_load_attempt where result_sent_at is not null", Integer.class)).isEqualTo(2);
    }

    @Test
    void failed_delivery_and_restart_preserve_the_exact_pending_outcome_and_reject_expired_claim_ack() {
        accept("1.000001", "");
        sendNotice("notice");
        MessageWorkClaim work = conversations().claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        ToolOutput output = automaticRead(work, "review", new SkillReadResult.Success(resource("review")));
        commitRead(work, output);
        SlackProgressClaim failed = claim();
        SlackProgressUpdate initial = progress.refresh(failed).orElseThrow();
        assertThat(progress.recordFailure(failed, initial.revision(), SlackDeliveryFailureCategory.TRANSIENT, Optional.empty())).isTrue();
        progress = store();
        jdbc.update("update slack_question_progress set next_due_at=clock_timestamp()-interval '1 second'");
        openGate();
        SlackProgressClaim expired = claim();
        SlackProgressUpdate retried = progress.refresh(expired).orElseThrow();
        assertSkill(retried, "review", SlackProgressUpdate.SkillPresentation.Kind.COMMITTED_SUCCESS);
        assertThat(retried.skillPresentation()).isEqualTo(initial.skillPresentation());
        jdbc.update("update slack_question_progress set locked_until=clock_timestamp()-interval '1 second'");
        openGate();
        SlackProgressClaim recovered = claim();
        SlackProgressUpdate latest = progress.refresh(recovered).orElseThrow();
        assertThat(progress.markSent(expired, retried.revision())).isFalse();
        assertThat(progress.markSent(recovered, latest.revision() + 1)).isFalse();
        assertThat(jdbc.queryForObject("select result_sent_at from skill_load_attempt", java.sql.Timestamp.class)).isNull();
        assertThat(progress.markSent(recovered, latest.revision())).isTrue();
        assertThat(jdbc.queryForObject("select result_sent_at from skill_load_attempt", java.sql.Timestamp.class)).isNotNull();
    }

    @Test
    void failed_automatic_read_is_a_durable_failed_notice_not_a_loaded_result() {
        accept("1.000001", "");
        sendNotice("notice");
        MessageWorkClaim work = conversations().claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        commitRead(work, automaticRead(work, "review", new SkillReadResult.Failure("SKILL_UNAVAILABLE")));
        SlackProgressUpdate failure = progress.refresh(claim()).orElseThrow();
        assertSkill(failure, "review", SlackProgressUpdate.SkillPresentation.Kind.COMMITTED_FAILURE);
        assertThat(jdbc.queryForObject("select observed_state from slack_question_progress", String.class)).isEqualTo("SKILL_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from skill_load_attempt where status='COMMITTED_SUCCESS'", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellation_shows_loaded_only_when_the_actual_automatic_observation_commits(boolean commitActualRead) {
        UUID job = accept("1.000001", "");
        sendNotice("notice");
        PostgresConversationStore conversations = conversations();
        MessageWorkClaim work = conversations.claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        ToolOutput output = automaticRead(work, "review", new SkillReadResult.Success(resource("review")));
        conversations.requestCancellation(work.messageJobId());
        if (commitActualRead) {
            commitRead(work, output);
        } else {
            conversations.append(work, new ConversationStore.MessageBatch(List.of(
                    new ConversationStore.RuntimeData(JobCancellation.CODE, JobCancellation.MESSAGE)),
                    ConversationStore.JobUpdate.CANCEL, ConversationStore.CompletionDisposition.NONE), Instant.now());
        }
        SlackProgressClaim pending = claim();
        SlackProgressUpdate result = progress.refresh(pending).orElseThrow();
        assertSkill(result, "review", commitActualRead ? SlackProgressUpdate.SkillPresentation.Kind.COMMITTED_SUCCESS
                : SlackProgressUpdate.SkillPresentation.Kind.INTERRUPTED);
        assertThat(progress.markSent(pending, result.revision())).isTrue();
        assertThat(jdbc.queryForObject("select retired from slack_question_progress where message_job_id=?", Boolean.class, job)).isFalse();
        openGate();
        SlackProgressClaim cancelled = claim();
        SlackProgressUpdate terminal = progress.refresh(cancelled).orElseThrow();
        assertThat(terminal.skillPresentation()).isEmpty();
        assertThat(terminal.terminal()).isTrue();
        assertState(cancelled, SlackProgressState.CANCELLED);
    }

    @Test
    void expired_read_is_interrupted_after_restart_without_inventing_success() {
        accept("1.000001", "");
        sendNotice("notice");
        MessageWorkClaim work = conversations().claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        automaticRead(work, "review", new SkillReadResult.Success(resource("review")));
        jdbc.update("update message_job set locked_until=clock_timestamp()-interval '1 second'");
        progress = store();
        progress.discover();
        SlackProgressClaim pending = claim();
        SlackProgressUpdate interrupted = progress.refresh(pending).orElseThrow();
        assertSkill(interrupted, "review", SlackProgressUpdate.SkillPresentation.Kind.INTERRUPTED);
        assertThat(jdbc.queryForObject("select history_sequence from skill_load_attempt", Long.class)).isNull();
        assertThat(progress.markSent(pending, interrupted.revision())).isTrue();
        openGate();
        SlackProgressClaim ordinary = claim();
        assertState(ordinary, SlackProgressState.INTERRUPTED);
    }

    @Test
    void waiting_job_presents_pending_skill_then_waiting_not_report_delivery_and_can_settle_independently() {
        accept("1.000001", "");
        sendNotice("notice");
        PostgresConversationStore conversations = conversations();
        MessageWorkClaim work = conversations.claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        commitRead(work, automaticRead(work, "review", new SkillReadResult.Success(resource("review"))));
        conversations.enterWrapUp(work, Instant.now());
        conversations.append(work, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("choose next action")),
                ConversationStore.JobUpdate.COMPLETE, ConversationStore.CompletionDisposition.WAITING), Instant.now());
        SlackProgressClaim pending = claim();
        SlackProgressUpdate loaded = progress.refresh(pending).orElseThrow();
        assertSkill(loaded, "review", SlackProgressUpdate.SkillPresentation.Kind.COMMITTED_SUCCESS);
        assertThat(progress.markSent(pending, loaded.revision())).isTrue();
        openGate();
        SlackProgressClaim waiting = claim();
        SlackProgressUpdate update = progress.refresh(waiting).orElseThrow();
        assertThat(update.skillPresentation()).isEmpty();
        assertThat(update.terminal()).isTrue();
        assertState(waiting, SlackProgressState.WAITING_USER);
        assertThat(update.request().text()).contains(SlackProgressState.WAITING_USER.description())
                .doesNotContain(SlackProgressState.COMPLETED.description(), "review");
        assertThat(progress.markSent(waiting, update.revision())).isTrue();
        jdbc.update("update slack_question_progress set terminal_settle_until=clock_timestamp()-interval '1 second',next_due_at=clock_timestamp()-interval '1 second'");
        openGate();
        SlackProgressClaim settled = claim();
        SlackProgressUpdate finalUpdate = progress.refresh(settled).orElseThrow();
        assertThat(progress.markSent(settled, finalUpdate.revision())).isTrue();
        assertThat(jdbc.queryForObject("select retired from slack_question_progress", Boolean.class)).isTrue();
        UUID next = accept("2.000002", "1.000001");
        assertThat(conversations.claimNext("next-model", Duration.ofMinutes(1)).orElseThrow().messageJobId().value()).isEqualTo(next.toString());
    }

    @Test
    void rolled_back_explicit_history_never_projects_a_loaded_notice() {
        acceptSkill("1.000001");
        sendNotice("notice");
        PostgresConversationStore conversations = conversations();
        MessageWorkClaim work = conversations.claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        conversations.startSkillCommand(work);
        jdbc.execute("""
                create function reject_skill_resolution() returns trigger language plpgsql as $$
                begin raise exception 'resolution rollback'; end;
                $$;
                create trigger reject_skill_resolution before update on skill_command
                    for each row execute function reject_skill_resolution();
                """);
        assertThatThrownBy(() -> conversations.resolveSkillCommand(work,
                new SkillReadResult.Success(resource("review")), Instant.now())).isInstanceOf(RuntimeException.class);
        SlackProgressClaim pending = claim();
        SlackProgressUpdate loading = progress.refresh(pending).orElseThrow();
        assertSkill(loading, "review", SlackProgressUpdate.SkillPresentation.Kind.STARTED);
        assertThat(jdbc.queryForObject("select count(*) from skill_message", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from skill_load_attempt where status='COMMITTED_SUCCESS'", Integer.class)).isZero();
        jdbc.update("update message_job set locked_until=clock_timestamp()-interval '1 second'");
        progress.discover();
        SlackProgressUpdate interrupted = progress.refresh(pending).orElseThrow();
        assertSkill(interrupted, "review", SlackProgressUpdate.SkillPresentation.Kind.INTERRUPTED);
    }

    @Test
    void reference_read_names_its_owning_skill_without_publishing_a_definition_reload() {
        accept("1.000001", "");
        sendNotice("notice");
        MessageWorkClaim work = conversations().claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        String uri = "skill://review/references/guide.md";
        ToolOutput reference = automaticRead(work, "review", uri,
                new SkillReadResult.Success(new SkillResource(uri, "a".repeat(64), "PRIVATE_SKILL_BODY")));
        commitRead(work, reference);

        SlackProgressUpdate notice = progress.refresh(claim()).orElseThrow();
        assertSkill(notice, "review", SlackProgressUpdate.SkillPresentation.Kind.COMMITTED_SUCCESS);
        assertThat(notice.skillPresentation().orElseThrow().resourceKind())
                .isEqualTo(SlackProgressUpdate.SkillPresentation.ResourceKind.REFERENCE);
        assertThat(notice.request().text()).doesNotContain(SlackProgressState.SKILL_LOADED.description(), "guide.md", "references/");
    }

    @Test
    void acknowledging_loading_does_not_acknowledge_the_later_committed_result() {
        acceptSkill("1.000001");
        sendNotice("notice");
        PostgresConversationStore conversations = conversations();
        MessageWorkClaim work = conversations.claimNext("model", Duration.ofMinutes(1)).orElseThrow();
        conversations.startSkillCommand(work);
        SlackProgressClaim loadingClaim = claim();
        SlackProgressUpdate loading = progress.refresh(loadingClaim).orElseThrow();
        assertSkill(loading, "review", SlackProgressUpdate.SkillPresentation.Kind.STARTED);
        assertThat(progress.markSent(loadingClaim, loading.revision())).isTrue();
        assertThat(jdbc.queryForObject("select started_sent_at from skill_load_attempt", java.sql.Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForObject("select result_sent_at from skill_load_attempt", java.sql.Timestamp.class)).isNull();

        conversations.resolveSkillCommand(work, new SkillReadResult.Success(resource("review")), Instant.now());
        openGate();
        SlackProgressClaim resultClaim = claim();
        SlackProgressUpdate result = progress.refresh(resultClaim).orElseThrow();
        assertSkill(result, "review", SlackProgressUpdate.SkillPresentation.Kind.COMMITTED_SUCCESS);
        assertThat(progress.markSent(resultClaim, result.revision())).isTrue();
    }

    private PostgresConversationStore conversations() {
        return new PostgresConversationStore(dataSource, Clock.systemUTC(), new ObjectMapper());
    }

    private static SkillResource resource(String name) {
        return new SkillResource("skill://" + name, "a".repeat(64), "PRIVATE_SKILL_BODY");
    }

    private ToolOutput automaticRead(MessageWorkClaim work, String name, SkillReadResult result) {
        return automaticRead(work, name, "skill://" + name, result);
    }

    private ToolOutput automaticRead(MessageWorkClaim work, String name, String uri, SkillReadResult result) {
        ModelCallReservationResult.Reserved reserved = (ModelCallReservationResult.Reserved)
                conversations().reserveModelCall(work, ModelCallPurpose.EXECUTION, 12, Instant.now());
        SkillLibrary library = mock(SkillLibrary.class);
        when(library.metadata()).thenReturn(List.of(new SkillMetadata(name, "Example", "skill://" + name)));
        when(library.read(uri)).thenReturn(result);
        ToolInvocation invocation = new ToolInvocation(new ToolExecutionContext(UUID.fromString(work.sessionId().value()),
                UUID.fromString(work.messageJobId().value()), reserved.totalOrdinal(), 0),
                new ToolExecutionAuthority(work.workerId(), work.claimNumber()));
        try (ToolSnapshot snapshot = new SkillToolCatalog(library, new PostgresSkillLoadObserver(dataSource)).snapshot()) {
            return snapshot.invoke(invocation, new ToolName("readSkill"), Map.of("uri", uri));
        }
    }

    private void commitRead(MessageWorkClaim work, ToolOutput output) {
        SkillReadReceipt receipt = output.skillReadReceipt().orElseThrow();
        ToolCallId call = new ToolCallId(receipt.attemptId().toString());
        conversations().append(work, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.empty(), List.of(
                        new ConversationStore.ToolCallData(call, "readSkill", Map.of("uri", receipt.resourceUri())))),
                new ConversationStore.ToolObservationData(call, "readSkill", output.asStructuredValue(),
                        output.artifactReceipt(), output.skillReadReceipt())),
                ConversationStore.JobUpdate.KEEP_WORKING, ConversationStore.CompletionDisposition.NONE), Instant.now());
    }

    private static void assertSkill(SlackProgressUpdate update, String name, SlackProgressUpdate.SkillPresentation.Kind kind) {
        assertThat(update.skillPresentation()).hasValueSatisfying(presentation -> {
            assertThat(presentation.skillName()).isEqualTo(name);
            assertThat(presentation.kind()).isEqualTo(kind);
        });
        assertThat(update.request().text()).contains(name).doesNotContain("PRIVATE_SKILL_BODY", "skill://", "SKILL.md");
        assertThat(update.terminal()).isFalse();
    }

    private UUID acceptSkill(String timestamp) {
        handler.handle(new IncomingEvent("event-" + timestamp, "T1", "C1", timestamp, "", "U1", "", "channel",
                "<@UBOT> /skill:review question", "", false, false));
        return jdbc.queryForObject("select message_job_id from slack_message_receipt where message_ts=?", UUID.class, timestamp);
    }

    private static SlackWebApi updating(java.util.function.Consumer<SlackUpdateRequest> update) {
        return new com.java.system.sessionagent.slack.TextSlackWebApi() {
            @Override
            public String post(SlackPostRequest request) { throw new AssertionError("Progress cannot post"); }
            @Override
            public void update(SlackUpdateRequest request) { update.accept(request); }
            @Override
            public String getPermalink(String channelId, String messageTs) {
                return "https://example.slack.com/archives/" + channelId + "/p" + messageTs.replace(".", "");
            }
        };
    }

    private void cacheQuestionLinks() {
        jdbc.update("update slack_question_progress set question_permalink='https://example.slack.com/archives/' || channel_id || '/p' || replace(source_message_ts,'.','')");
    }

    private SlackPostgresProgressStore store() {
        return new SlackPostgresProgressStore(dataSource, new SlackProgressProperties(),
                new SlackDeliveryProperties(Duration.ofSeconds(30), Duration.ofMillis(1), Duration.ofMillis(5), 3));
    }
    private UUID accept(String timestamp, String root) {
        handler.handle(new IncomingEvent("event-" + timestamp, "T1", "C1", timestamp, root, "U1", "", "channel", "<@UBOT> question", "", false, false));
        return jdbc.queryForObject("select message_job_id from slack_message_receipt where message_ts=?", UUID.class, timestamp);
    }
    private void sendNotice(String timestamp) {
        SlackDeliveryClaim claim = delivery.claimNext("notice", Duration.ofMinutes(1), 5).orElseThrow();
        SlackDeliveryPart part = delivery.nextPart(claim, Duration.ofMinutes(1)).orElseThrow();
        assertThat(delivery.markPartSent(claim, part, timestamp)).isTrue();
    }
    private SlackProgressClaim claim() { return progress.claimNext("progress", Duration.ofMinutes(1)).orElseThrow(); }
    private void openGate() { jdbc.update("update slack_progress_workspace set next_allowed_at=clock_timestamp()-interval '1 second'"); }
    private void complete(UUID job) { jdbc.update("update message_job set status='DONE',completed_at=clock_timestamp() where message_job_id=?", job); }
    private UUID answer(UUID job) {
        // Create a real terminal history event; delivery discovery then resolves its source identity.
        UUID session = jdbc.queryForObject("select session_id from message_job where message_job_id=?", UUID.class, job);
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.update("insert into session_message(session_id,sequence,message_job_id,role,created_at) values (?,100,?,'ASSISTANT',clock_timestamp())", session, job);
            jdbc.update("insert into assistant_message(session_id,sequence,message) values (?,100,'answer')", session);
        });
        delivery.discover();
        return jdbc.queryForObject("select delivery_id from slack_delivery where kind='ANSWER' and message_job_id=?", UUID.class, job);
    }
}
