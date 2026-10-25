package com.java.system.sessionagent.slack.adapter.postgres;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface SlackProgressMapper {
    String OWNED = " message_job_id = #{claim.messageJobId} and worker_id = #{claim.workerId}"
            + " and claim_number = #{claim.claimNumber} and locked_until > clock_timestamp() and not retired ";

    @Update("""
            update skill_load_attempt load set status='INTERRUPTED',finished_at=clock_timestamp(),error_code='SKILL_INTERRUPTED'
            from message_job job where load.message_job_id=job.message_job_id and load.status='STARTED'
                and (job.status <> 'WORKING' or job.locked_until <= clock_timestamp()
                    or job.claim_number <> load.claim_number or job.worker_id is distinct from load.worker_id)
            """)
    int interruptAbandonedLoads();

    @Insert("""
            insert into slack_question_progress(message_job_id,notice_delivery_id,team_id,channel_id,source_message_ts,target_message_ts)
            select notice.message_job_id,notice.delivery_id,notice.team_id,notice.channel_id,notice.message_ts,notice.slack_message_ts
            from slack_delivery notice join slack_progress_observation observation on observation.message_job_id=notice.message_job_id
            where notice.kind='QUEUED' and notice.status='SENT'
                and (observation.job_status not in ('DONE', 'CANCELLED') or not observation.answers_delivered
                    or observation.presentation_id is not null)
                and not exists (select 1 from slack_question_progress progress where progress.message_job_id=notice.message_job_id)
            order by notice.created_at,notice.delivery_id limit 100
            on conflict (message_job_id) do nothing
            """)
    int discover();

    @Insert("""
            insert into slack_progress_workspace(team_id)
            select distinct team_id from slack_question_progress where not retired
            on conflict (team_id) do nothing
            """)
    int discoverWorkspaces();

    @Select(value = """
            with workspace as (
                select gate.team_id from slack_progress_workspace gate
                where gate.next_allowed_at <= clock_timestamp() and exists (
                    select 1 from slack_question_progress p join slack_progress_observation o using(message_job_id)
                    where p.team_id=gate.team_id and not p.retired
                        and (p.locked_until is null or p.locked_until <= clock_timestamp())
                        and (p.next_due_at <= clock_timestamp() or
                            row(p.observed_state,p.presentation_id,p.presentation_kind) is distinct from row(o.state,o.presentation_id,o.presentation_kind)))
                order by (select min(p.next_due_at) from slack_question_progress p where p.team_id=gate.team_id and not p.retired), gate.team_id
                for update of gate skip locked limit 1
            ), candidate as (
                select p.message_job_id from slack_question_progress p join workspace using(team_id)
                join slack_progress_observation o using(message_job_id)
                where not p.retired and (p.locked_until is null or p.locked_until <= clock_timestamp())
                    and (p.next_due_at <= clock_timestamp() or
                        row(p.observed_state,p.presentation_id,p.presentation_kind) is distinct from row(o.state,o.presentation_id,o.presentation_kind))
                order by p.next_due_at,p.message_job_id for update of p skip locked limit 1
            ), gate as (
                update slack_progress_workspace set next_allowed_at=clock_timestamp()+(#{workspaceMs}*interval '1 millisecond')
                where team_id in (select team_id from workspace) and exists(select 1 from candidate)
            )
            update slack_question_progress p set worker_id=#{workerId},claim_number=p.claim_number+1,
                locked_until=clock_timestamp()+(#{leaseMs}*interval '1 millisecond')
            from candidate where p.message_job_id=candidate.message_job_id
            returning p.message_job_id,p.worker_id,p.claim_number
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<ClaimRow> claim(@Param("workerId") String workerId, @Param("leaseMs") long leaseMs, @Param("workspaceMs") long workspaceMs);

    @Select("select message_job_id from slack_question_progress where " + OWNED + " for update")
    List<UUID> lock(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim);

    @Select(value = """
            update slack_question_progress p set
                observed_state=o.state,presentation_id=o.presentation_id,presentation_kind=o.presentation_kind,
                skill_name=o.skill_name,resource_kind=o.resource_kind,observed_at=statement_timestamp(),revision=p.revision+1,
                consecutive_failures=case when row(p.observed_state,p.presentation_id,p.presentation_kind)
                    is distinct from row(o.state,o.presentation_id,o.presentation_kind) then 0 else p.consecutive_failures end,
                failure_category=case when row(p.observed_state,p.presentation_id,p.presentation_kind)
                    is distinct from row(o.state,o.presentation_id,o.presentation_kind) then null else p.failure_category end,
                terminal_settle_until=case when row(p.observed_state,p.presentation_id,p.presentation_kind)
                    is distinct from row(o.state,o.presentation_id,o.presentation_kind) then null else p.terminal_settle_until end
            from slack_progress_observation o where p.message_job_id=o.message_job_id
                and p.message_job_id=#{claim.messageJobId} and p.worker_id=#{claim.workerId} and p.claim_number=#{claim.claimNumber}
                and p.locked_until > clock_timestamp() and not p.retired
                and (row(p.observed_state,p.presentation_id,p.presentation_kind) is distinct from row(o.state,o.presentation_id,o.presentation_kind)
                    or (p.next_due_at <= clock_timestamp() and p.last_sent_revision=p.revision))
            returning p.channel_id,p.target_message_ts,p.team_id,p.source_message_ts,p.observed_state,p.observed_at,p.revision,
                p.question_permalink,p.presentation_id,p.presentation_kind,p.skill_name,p.resource_kind
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<ObservationRow> refresh(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim);

    @Select("select channel_id,target_message_ts,team_id,source_message_ts,observed_state,observed_at,revision,question_permalink,"
            + "presentation_id,presentation_kind,skill_name,resource_kind from slack_question_progress where " + OWNED)
    @Options(useCache = false)
    List<ObservationRow> current(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim);

    @Update("""
            update slack_question_progress set question_permalink=#{permalink},worker_id=null,locked_until=null,
                next_due_at=clock_timestamp(),consecutive_failures=0,failure_category=null
            where
            """ + OWNED + " and question_permalink is null")
    int saveQuestionPermalink(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim,
            @Param("permalink") String permalink);

    @Select(value = """
            with sent as (
                update slack_question_progress p set last_sent_revision=revision,last_sent_at=clock_timestamp(),
                    consecutive_failures=0,failure_category=null,worker_id=null,locked_until=null,
                    retired=#{terminal} and coalesce(terminal_settle_until <= clock_timestamp(),false),
                    next_due_at=case when presentation_id is not null then clock_timestamp()
                        when #{terminal} then coalesce(terminal_settle_until,clock_timestamp()+(#{settleMs}*interval '1 millisecond'))
                        else clock_timestamp()+(#{refreshMs}*interval '1 millisecond') end,
                    terminal_settle_until=case when #{terminal} then
                        coalesce(terminal_settle_until,clock_timestamp()+(#{settleMs}*interval '1 millisecond')) else null end
                where p.message_job_id=#{claim.messageJobId} and p.worker_id=#{claim.workerId}
                    and p.claim_number=#{claim.claimNumber} and p.locked_until > clock_timestamp() and not p.retired
                    and revision=#{revision} and exists (select 1 from slack_progress_observation o
                        where o.message_job_id=p.message_job_id and
                            row(p.observed_state,p.presentation_id,p.presentation_kind)
                                is not distinct from row(o.state,o.presentation_id,o.presentation_kind))
                returning p.presentation_id,p.presentation_kind
            ), acknowledged as (
                update skill_load_attempt load set
                    started_sent_at=case when sent.presentation_kind='STARTED' then clock_timestamp() else load.started_sent_at end,
                    result_sent_at=case when sent.presentation_kind <> 'STARTED' then clock_timestamp() else load.result_sent_at end
                from sent where load.attempt_id=sent.presentation_id
            )
            select count(*) from sent
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int sent(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim, @Param("revision") long revision,
            @Param("terminal") boolean terminal, @Param("settleMs") long settleMs, @Param("refreshMs") long refreshMs);

    @Select("select consecutive_failures from slack_question_progress where " + OWNED + " and revision=#{revision}")
    List<Integer> failures(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim, @Param("revision") long revision);
    @Update("""
            update slack_question_progress set consecutive_failures=consecutive_failures+1,failure_category=#{category},
                retired=#{retire},next_due_at=clock_timestamp()+(#{delayMs}*interval '1 millisecond'),worker_id=null,locked_until=null
            where
            """ + OWNED + " and revision=#{revision}")
    int failed(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim, @Param("revision") long revision,
            @Param("category") String category,@Param("retire") boolean retire,@Param("delayMs") long delayMs);

    @Update("""
            update slack_progress_workspace set next_allowed_at=greatest(next_allowed_at,clock_timestamp()+(#{delayMs}*interval '1 millisecond'))
            where team_id=(select team_id from slack_question_progress where message_job_id=#{jobId})
            """)
    int cooldown(@Param("jobId") UUID jobId,@Param("delayMs") long delayMs);

    record ClaimRow(UUID messageJobId, String workerId, long claimNumber) { }
    record ObservationRow(String channelId, String targetMessageTs, String teamId, String sourceMessageTs,
            String observedState, OffsetDateTime observedAt, long revision, String questionPermalink,
            UUID presentationId, String presentationKind, String skillName, String resourceKind) { }
}
