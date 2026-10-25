package com.java.system.sessionagent.slack.adapter.postgres;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Typed SQL boundary for Slack's PostgreSQL tables. */
@Mapper
public interface SlackDatabaseMapper extends BaseMapper<SlackDatabaseMapper.DeliveryEntity> {

    @Insert("""
            insert into slack_thread_binding(team_id, channel_id, root_thread_ts, session_id, created_at)
            values (#{teamId}, #{channelId}, #{rootThreadTs}, #{sessionId,jdbcType=OTHER}, #{createdAt})
            on conflict (team_id, channel_id, root_thread_ts) do nothing
            """)
    int insertThreadBinding(
            @Param("teamId") String teamId,
            @Param("channelId") String channelId,
            @Param("rootThreadTs") String rootThreadTs,
            @Param("sessionId") UUID sessionId,
            @Param("createdAt") OffsetDateTime createdAt);

    @Select("""
            select session_id
            from slack_thread_binding
            where team_id = #{teamId} and channel_id = #{channelId} and root_thread_ts = #{rootThreadTs}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<UUID> findThreadBinding(
            @Param("teamId") String teamId,
            @Param("channelId") String channelId,
            @Param("rootThreadTs") String rootThreadTs);

    @Insert("""
            insert into slack_message_receipt(team_id, channel_id, message_ts, top_level, classification, session_id, message_job_id, created_at)
            values (#{teamId}, #{channelId}, #{messageTs}, #{topLevel}, #{classification},
                #{sessionId,jdbcType=OTHER}, #{messageJobId,jdbcType=OTHER}, #{createdAt})
            on conflict (team_id, channel_id, message_ts) do update
            set classification = excluded.classification,
                session_id = excluded.session_id,
                message_job_id = excluded.message_job_id
            where slack_message_receipt.top_level
                and slack_message_receipt.classification <> 'ACCEPTED'
                and slack_message_receipt.classification not in ('BUSY', 'STOP_REQUEST')
                and excluded.top_level
                and excluded.classification in ('ACCEPTED', 'BUSY')
            """)
    int insertLogicalReceipt(
            @Param("teamId") String teamId,
            @Param("channelId") String channelId,
            @Param("messageTs") String messageTs,
            @Param("topLevel") boolean topLevel,
            @Param("classification") String classification,
            @Param("sessionId") UUID sessionId,
            @Param("messageJobId") UUID messageJobId,
            @Param("createdAt") OffsetDateTime createdAt);

    @Select("""
            select top_level, classification, session_id, message_job_id
            from slack_message_receipt
            where team_id = #{teamId} and channel_id = #{channelId} and message_ts = #{messageTs}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<MessageReceiptRow> findLogicalReceipt(
            @Param("teamId") String teamId,
            @Param("channelId") String channelId,
            @Param("messageTs") String messageTs);

    @Insert("""
            insert into slack_event_receipt(event_id, team_id, channel_id, message_ts, classification, correlation_id, created_at)
            values (#{eventId}, #{teamId}, #{channelId}, #{messageTs}, #{classification},
                #{correlationId,jdbcType=OTHER}, #{createdAt})
            on conflict (event_id) do nothing
            """)
    int insertEventReceipt(
            @Param("eventId") String eventId,
            @Param("teamId") String teamId,
            @Param("channelId") String channelId,
            @Param("messageTs") String messageTs,
            @Param("classification") String classification,
            @Param("correlationId") UUID correlationId,
            @Param("createdAt") OffsetDateTime createdAt);

    @Select("""
            select team_id, channel_id, message_ts, classification, correlation_id
            from slack_event_receipt
            where event_id = #{eventId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<EventReceiptRow> findEventReceipt(@Param("eventId") String eventId);

    @Select("""
            select pg_advisory_xact_lock(hashtextextended(json_build_array(#{teamId}, #{channelId}, #{messageTs})::text, 0))
            """)
    String acquireLogicalMessageLock(
            @Param("teamId") String teamId,
            @Param("channelId") String channelId,
            @Param("messageTs") String messageTs);

    @Select(value = """
            with candidate as (
                select candidate_delivery.delivery_id
                from slack_delivery candidate_delivery
                where candidate_delivery.attempt_count < #{maximumAttempts}
                  and ((candidate_delivery.status in ('PENDING', 'RETRY') and candidate_delivery.next_attempt_at <= clock_timestamp())
                   or (candidate_delivery.status = 'WORKING' and candidate_delivery.locked_until <= clock_timestamp()))
                  and (candidate_delivery.kind <> 'ANSWER' or not exists (
                      select 1
                      from slack_delivery queued
                      where queued.kind = 'QUEUED'
                        and queued.message_job_id = candidate_delivery.message_job_id
                        and queued.status not in ('SENT', 'FAILED')))
                  and (candidate_delivery.kind <> 'ANSWER' or not exists (
                      select 1
                      from slack_delivery earlier_answer
                      where earlier_answer.kind = 'ANSWER'
                        and earlier_answer.terminal_session_id = candidate_delivery.terminal_session_id
                        and earlier_answer.terminal_sequence < candidate_delivery.terminal_sequence
                        and earlier_answer.status not in ('SENT', 'FAILED')))
                order by candidate_delivery.next_attempt_at, candidate_delivery.created_at, candidate_delivery.delivery_id
                for update skip locked limit 1)
            update slack_delivery delivery set status = 'WORKING', worker_id = #{workerId},
                locked_until = clock_timestamp() + (#{leaseMilliseconds} * interval '1 millisecond'),
                claim_number = delivery.claim_number + 1, attempt_count = delivery.attempt_count + 1
            from candidate where delivery.delivery_id = candidate.delivery_id
            returning delivery.delivery_id, delivery.kind, delivery.terminal_session_id as session_id, delivery.message_job_id, delivery.claim_number,
                delivery.attempt_count, delivery.worker_id, delivery.locked_until, delivery.channel_id,
                delivery.root_thread_ts, delivery.terminal_text
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<DeliveryClaimRow> claimNext(
            @Param("maximumAttempts") int maximumAttempts,
            @Param("workerId") String workerId,
            @Param("leaseMilliseconds") long leaseMilliseconds);

    @Update("""
            update slack_delivery set locked_until = clock_timestamp() + (#{leaseMilliseconds} * interval '1 millisecond')
            where delivery_id = #{deliveryId} and status = 'WORKING' and worker_id = #{workerId} and claim_number = #{claimNumber}
                and locked_until > clock_timestamp()
            """)
    int renewLease(
            @Param("deliveryId") UUID deliveryId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber,
            @Param("leaseMilliseconds") long leaseMilliseconds);


    @Select("""
            select delivery_id from slack_delivery
            where delivery_id = #{deliveryId} and status = 'WORKING' and worker_id = #{workerId}
                and claim_number = #{claimNumber} and locked_until > clock_timestamp()
            for update
            """)
    List<UUID> lockOwnedDelivery(
            @Param("deliveryId") UUID deliveryId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber);

    @Update("""
            update slack_delivery_part part set status = 'SENT', slack_message_ts = #{slackMessageTs}, sent_at = clock_timestamp()
            from slack_delivery delivery
            where part.delivery_id = #{deliveryId} and part.part_index = #{partIndex} and part.status = 'PENDING'
                and delivery.delivery_id = part.delivery_id and delivery.status = 'WORKING'
                and delivery.worker_id = #{workerId} and delivery.claim_number = #{claimNumber}
                and delivery.locked_until > clock_timestamp()
                and delivery.items_prepared
                and (part.presentation <> 'FILE' or exists (
                    select 1 from slack_delivery_upload upload where upload.delivery_id=part.delivery_id
                        and upload.part_index=part.part_index and upload.state='SENT'
                        and upload.finalization_claim=#{claimNumber}))
            """)
    int markPartSent(
            @Param("slackMessageTs") String slackMessageTs,
            @Param("deliveryId") UUID deliveryId,
            @Param("partIndex") int partIndex,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber);

    @Select("select count(*) from slack_delivery_part where delivery_id = #{deliveryId} and status <> 'SENT'")
    int countUnsentParts(@Param("deliveryId") UUID deliveryId);

    @Update("""
            update slack_delivery set status = 'SENT', slack_message_ts = #{slackMessageTs}, sent_at = clock_timestamp(),
                worker_id = null, locked_until = null
            where delivery_id = #{deliveryId} and status = 'WORKING' and worker_id = #{workerId} and claim_number = #{claimNumber}
                and locked_until > clock_timestamp()
                and items_prepared
                and not exists (select 1 from slack_delivery_part part where part.delivery_id=slack_delivery.delivery_id
                    and part.status <> 'SENT')
                and not exists (select 1 from slack_delivery_upload upload where upload.delivery_id=slack_delivery.delivery_id
                    and upload.state <> 'SENT')
            """)
    int markDeliverySent(
            @Param("slackMessageTs") String slackMessageTs,
            @Param("deliveryId") UUID deliveryId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber);

    @Insert("""
            insert into slack_question_progress(message_job_id, notice_delivery_id, team_id, channel_id,
                source_message_ts, target_message_ts)
            select message_job_id, delivery_id, team_id, channel_id, message_ts, slack_message_ts
            from slack_delivery where delivery_id = #{deliveryId} and kind = 'QUEUED' and status = 'SENT'
            on conflict (message_job_id) do nothing
            """)
    int registerProgress(@Param("deliveryId") UUID deliveryId);

    @Update("""
            update slack_delivery_part part set status = 'FAILED', slack_message_ts = #{slackMessageTs}
            from slack_delivery delivery
            where part.delivery_id = #{deliveryId} and part.part_index = #{partIndex} and part.status = 'PENDING'
                and delivery.delivery_id = part.delivery_id and delivery.status = 'WORKING'
                and delivery.worker_id = #{workerId} and delivery.claim_number = #{claimNumber}
                and delivery.locked_until > clock_timestamp()
            """)
    int markPartFailed(
            @Param("slackMessageTs") String slackMessageTs,
            @Param("deliveryId") UUID deliveryId,
            @Param("partIndex") int partIndex,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber);

    @Update("""
            update slack_delivery set status = 'FAILED', failure_category = #{category}, worker_id = null, locked_until = null
            where delivery_id = #{deliveryId} and status = 'WORKING' and worker_id = #{workerId} and claim_number = #{claimNumber}
                and locked_until > clock_timestamp()
            """)
    int markDeliveryFailed(
            @Param("category") String category,
            @Param("deliveryId") UUID deliveryId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber);

    @Update("""
            update slack_delivery set status = 'RETRY', failure_category = #{category},
                next_attempt_at = clock_timestamp() + (#{delayMilliseconds} * interval '1 millisecond'), worker_id = null, locked_until = null
            where delivery_id = #{deliveryId} and status = 'WORKING' and worker_id = #{workerId} and claim_number = #{claimNumber}
                and locked_until > clock_timestamp()
                and not exists (select 1 from slack_delivery_upload upload
                    where upload.delivery_id=slack_delivery.delivery_id and upload.state='FINALIZING')
            """)
    int scheduleRetry(
            @Param("category") String category,
            @Param("delayMilliseconds") long delayMilliseconds,
            @Param("deliveryId") UUID deliveryId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber);

    @Update("""
            update slack_delivery set status = 'FAILED', failure_category = coalesce(failure_category, 'TRANSIENT'),
                worker_id = null, locked_until = null
            where status = 'WORKING' and locked_until <= clock_timestamp() and attempt_count >= #{maximumAttempts}
            """)
    int terminalizeExpiredExhaustedDeliveries(@Param("maximumAttempts") int maximumAttempts);

    @Select("""
            select delivery_id, kind, status, attempt_count, failure_category, slack_message_ts, next_attempt_at, message_job_id,
                (select count(*) from slack_delivery_part part where part.delivery_id = slack_delivery.delivery_id) as total_parts,
                (select count(*) from slack_delivery_part part where part.delivery_id = slack_delivery.delivery_id
                    and part.status = 'SENT') as sent_parts
            from slack_delivery
            where delivery_id = #{deliveryId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<DeliveryViewRow> findDelivery(@Param("deliveryId") UUID deliveryId);

    @Select("""
            select delivery_id, kind, status, attempt_count, failure_category, slack_message_ts, next_attempt_at, message_job_id,
                (select count(*) from slack_delivery_part part where part.delivery_id = slack_delivery.delivery_id) as total_parts,
                (select count(*) from slack_delivery_part part where part.delivery_id = slack_delivery.delivery_id
                    and part.status = 'SENT') as sent_parts
            from slack_delivery where team_id = #{teamId} and channel_id = #{channelId} and message_ts = #{messageTs}
            order by created_at, delivery_id
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<DeliveryViewRow> findDeliveriesBySource(
            @Param("teamId") String teamId, @Param("channelId") String channelId, @Param("messageTs") String messageTs);

    @Insert("""
            insert into slack_delivery(delivery_id, kind, message_job_id, team_id, channel_id, root_thread_ts, message_ts, terminal_text,
                status, attempt_count, next_attempt_at, claim_number, created_at)
            values (#{deliveryId}, #{kind}, #{messageJobId,jdbcType=OTHER}, #{teamId}, #{channelId}, #{rootThreadTs}, #{messageTs}, #{text},
                'PENDING', 0, clock_timestamp(), 0, clock_timestamp())
            on conflict (team_id, channel_id, message_ts) where kind in ('QUEUED', 'BUSY', 'STOP') do nothing
            """)
    int insertNoticeDelivery(
            @Param("deliveryId") UUID deliveryId, @Param("kind") String kind, @Param("messageJobId") UUID messageJobId,
            @Param("teamId") String teamId, @Param("channelId") String channelId, @Param("rootThreadTs") String rootThreadTs,
            @Param("messageTs") String messageTs, @Param("text") String text);

    @Select("""
            select job.message_job_id, terminal.session_id, terminal.sequence, receipt.team_id, binding.channel_id, binding.root_thread_ts,
                receipt.message_ts,
                case terminal.role when 'ASSISTANT' then assistant.message else runtime.message end as terminal_text
            from message_job job
            join slack_message_receipt receipt on receipt.message_job_id = job.message_job_id
                and receipt.session_id = job.session_id and receipt.classification = 'ACCEPTED'
            join slack_thread_binding binding on binding.session_id = job.session_id
            join session_message terminal on terminal.session_id = job.session_id and terminal.message_job_id = job.message_job_id
            left join assistant_message assistant on assistant.session_id = terminal.session_id and assistant.sequence = terminal.sequence
            left join runtime_message runtime on runtime.session_id = terminal.session_id and runtime.sequence = terminal.sequence
            where job.status in ('DONE', 'CANCELLED') and terminal.role in ('ASSISTANT', 'RUNTIME')
              and (case terminal.role when 'ASSISTANT' then assistant.message else runtime.message end) ~ '[^[:space:]]'
              and terminal.sequence = (select max(later.sequence) from session_message later
                  where later.session_id = job.session_id and later.message_job_id = job.message_job_id)
              and not exists (select 1 from slack_delivery delivery
                  where delivery.terminal_session_id = terminal.session_id and delivery.terminal_sequence = terminal.sequence)
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<NewDeliveryRow> discoverDeliveries();

    @Insert("""
            insert into slack_delivery(delivery_id, kind, terminal_session_id, terminal_sequence, message_job_id, team_id, channel_id,
                root_thread_ts, message_ts, terminal_text, status, attempt_count, next_attempt_at, claim_number, created_at)
            values (#{deliveryId}, 'ANSWER', #{sessionId}, #{sequence}, #{messageJobId}, #{teamId}, #{channelId}, #{rootThreadTs},
                #{messageTs}, #{terminalText},
                'PENDING', 0, clock_timestamp(), 0, clock_timestamp())
            on conflict (terminal_session_id, terminal_sequence) do nothing
            """)
    int insertDelivery(
            @Param("deliveryId") UUID deliveryId,
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("messageJobId") UUID messageJobId,
            @Param("teamId") String teamId,
            @Param("channelId") String channelId,
            @Param("rootThreadTs") String rootThreadTs,
            @Param("messageTs") String messageTs,
            @Param("terminalText") String terminalText);

    @Select("""
            select session_id
            from slack_thread_binding
            where channel_id = #{channelId} and root_thread_ts = #{rootThreadTs}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<UUID> findSessionByBinding(
            @Param("channelId") String channelId,
            @Param("rootThreadTs") String rootThreadTs);

    @Select("""
            select team_id, channel_id, root_thread_ts, session_id, created_at
            from slack_thread_binding
            where session_id = #{sessionId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<ExternalBindingRow> findBinding(@Param("sessionId") UUID sessionId);

    @TableName("slack_delivery")
    class DeliveryEntity {

        @TableId(value = "delivery_id", type = IdType.INPUT)
        private UUID deliveryId;
        private String kind;
        private UUID terminalSessionId;
        private long terminalSequence;
        private UUID messageJobId;
        private String channelId;
        private String rootThreadTs;
        private String terminalText;
        private String status;
        private int attemptCount;
        private OffsetDateTime nextAttemptAt;
        private long claimNumber;
        private String workerId;
        private OffsetDateTime lockedUntil;
        private String failureCategory;
        private String slackMessageTs;
        private OffsetDateTime createdAt;
        private OffsetDateTime sentAt;

        public UUID getDeliveryId() {
            return deliveryId;
        }

        public String getKind() { return kind; }

        public void setKind(String kind) { this.kind = kind; }

        public void setDeliveryId(UUID deliveryId) {
            this.deliveryId = deliveryId;
        }

        public UUID getTerminalSessionId() {
            return terminalSessionId;
        }

        public void setTerminalSessionId(UUID terminalSessionId) {
            this.terminalSessionId = terminalSessionId;
        }

        public long getTerminalSequence() {
            return terminalSequence;
        }

        public void setTerminalSequence(long terminalSequence) {
            this.terminalSequence = terminalSequence;
        }

        public UUID getMessageJobId() {
            return messageJobId;
        }

        public void setMessageJobId(UUID messageJobId) {
            this.messageJobId = messageJobId;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getRootThreadTs() {
            return rootThreadTs;
        }

        public void setRootThreadTs(String rootThreadTs) {
            this.rootThreadTs = rootThreadTs;
        }

        public String getTerminalText() {
            return terminalText;
        }

        public void setTerminalText(String terminalText) {
            this.terminalText = terminalText;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getAttemptCount() {
            return attemptCount;
        }

        public void setAttemptCount(int attemptCount) {
            this.attemptCount = attemptCount;
        }

        public OffsetDateTime getNextAttemptAt() {
            return nextAttemptAt;
        }

        public void setNextAttemptAt(OffsetDateTime nextAttemptAt) {
            this.nextAttemptAt = nextAttemptAt;
        }

        public long getClaimNumber() {
            return claimNumber;
        }

        public void setClaimNumber(long claimNumber) {
            this.claimNumber = claimNumber;
        }

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public OffsetDateTime getLockedUntil() {
            return lockedUntil;
        }

        public void setLockedUntil(OffsetDateTime lockedUntil) {
            this.lockedUntil = lockedUntil;
        }

        public String getFailureCategory() {
            return failureCategory;
        }

        public void setFailureCategory(String failureCategory) {
            this.failureCategory = failureCategory;
        }

        public String getSlackMessageTs() {
            return slackMessageTs;
        }

        public void setSlackMessageTs(String slackMessageTs) {
            this.slackMessageTs = slackMessageTs;
        }

        public OffsetDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(OffsetDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public OffsetDateTime getSentAt() {
            return sentAt;
        }

        public void setSentAt(OffsetDateTime sentAt) {
            this.sentAt = sentAt;
        }
    }

    record EventReceiptRow(
            String teamId,
            String channelId,
            String messageTs,
            String classification,
            UUID correlationId) {
    }

    record MessageReceiptRow(boolean topLevel, String classification, UUID sessionId, UUID messageJobId) {
    }

    record DeliveryClaimRow(
            UUID deliveryId,
            String kind,
            UUID sessionId,
            UUID messageJobId,
            long claimNumber,
            int attemptCount,
            String workerId,
            OffsetDateTime lockedUntil,
            String channelId,
            String rootThreadTs,
            String terminalText) {
    }

    record DeliveryViewRow(
            UUID deliveryId,
            String kind,
            String status,
            int attemptCount,
            String failureCategory,
            String slackMessageTs,
            OffsetDateTime nextAttemptAt,
            UUID messageJobId,
            int totalParts,
            int sentParts) {
    }


    record NewDeliveryRow(
            UUID messageJobId,
            UUID sessionId,
            long sequence,
            String teamId,
            String channelId,
            String rootThreadTs,
            String messageTs,
            String terminalText) {
    }

    record ExternalBindingRow(
            String teamId,
            String channelId,
            String rootThreadTs,
            UUID sessionId,
            OffsetDateTime createdAt) {
    }
}
