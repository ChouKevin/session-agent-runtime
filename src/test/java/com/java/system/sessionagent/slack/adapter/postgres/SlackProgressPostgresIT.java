package com.java.system.sessionagent.slack.adapter.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.application.JobCancellationService;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.storage.adapter.postgres.jdbc.PostgresIntakeCapacity;
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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        SlackDeliveryPart part = delivery.nextPart(notice, () -> List.of("queued"), Duration.ofMinutes(1)).orElseThrow();
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
        jdbc.update("update slack_delivery set status='SENT',slack_message_ts='final',sent_at=clock_timestamp() where delivery_id=?", answer);
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
        jdbc.update("update slack_delivery set status='SENT', slack_message_ts='answer', sent_at=clock_timestamp() where delivery_id=?", answer);
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("傳送");
        jdbc.update("update slack_delivery_part set status='SENT',slack_message_ts='part',sent_at=clock_timestamp() where delivery_id=?", answer);
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("已完成");
        jdbc.update("update message_job set failure_code='MODEL_UNAVAILABLE' where message_job_id=?", job);
        assertThat(progress.refresh(claim).orElseThrow().request().text()).contains("處理失敗");
    }

    @Test
    void fast_completion_is_not_reported_as_running() {
        UUID job = accept("1.000001", "");
        complete(job);
        sendNotice("notice");
        UUID answer = answer(job);
        SlackDeliveryClaim answerClaim = delivery.claimNext("answer", Duration.ofMinutes(1), 5).orElseThrow();
        SlackDeliveryPart part = delivery.nextPart(answerClaim, () -> List.of("answer"), Duration.ofMinutes(1)).orElseThrow();
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
        SlackDeliveryPart part = delivery.nextPart(answerClaim, () -> List.of("answer"), Duration.ofMinutes(1)).orElseThrow();
        assertThat(delivery.markPartSent(answerClaim, part, "final")).isTrue();
        assertThat(jdbc.queryForObject("select status from slack_delivery where delivery_id=?", String.class, answerId)).isEqualTo("SENT");
    }

    private static SlackWebApi updating(java.util.function.Consumer<SlackUpdateRequest> update) {
        return new SlackWebApi() {
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
        SlackDeliveryPart part = delivery.nextPart(claim, () -> List.of("queued"), Duration.ofMinutes(1)).orElseThrow();
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
