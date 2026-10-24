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

    @Insert("""
            insert into slack_question_progress(message_job_id,notice_delivery_id,team_id,channel_id,source_message_ts,target_message_ts)
            select notice.message_job_id,notice.delivery_id,notice.team_id,notice.channel_id,notice.message_ts,notice.slack_message_ts
            from slack_delivery notice join slack_progress_observation observation on observation.message_job_id=notice.message_job_id
            where notice.kind='QUEUED' and notice.status='SENT'
                and (observation.job_status not in ('DONE', 'CANCELLED') or not observation.answers_delivered)
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
                        and (p.next_due_at <= clock_timestamp() or p.observed_state is distinct from o.state))
                order by (select min(p.next_due_at) from slack_question_progress p where p.team_id=gate.team_id and not p.retired), gate.team_id
                for update of gate skip locked limit 1
            ), candidate as (
                select p.message_job_id from slack_question_progress p join workspace using(team_id)
                join slack_progress_observation o using(message_job_id)
                where not p.retired and (p.locked_until is null or p.locked_until <= clock_timestamp())
                    and (p.next_due_at <= clock_timestamp() or p.observed_state is distinct from o.state)
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
                observed_state=o.state, observed_at=statement_timestamp(), revision=p.revision+1,
                consecutive_failures=case when p.observed_state is distinct from o.state then 0 else p.consecutive_failures end,
                failure_category=case when p.observed_state is distinct from o.state then null else p.failure_category end,
                terminal_settle_until=case when p.observed_state is distinct from o.state then null else p.terminal_settle_until end
            from slack_progress_observation o where p.message_job_id=o.message_job_id
                and p.message_job_id=#{claim.messageJobId} and p.worker_id=#{claim.workerId} and p.claim_number=#{claim.claimNumber}
                and p.locked_until > clock_timestamp() and not p.retired
                and (p.observed_state is distinct from o.state
                    or (p.next_due_at <= clock_timestamp() and p.last_sent_revision=p.revision))
            returning p.channel_id,p.target_message_ts,p.team_id,p.source_message_ts,p.observed_state,p.observed_at,p.revision,p.question_permalink
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<ObservationRow> refresh(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim);

    @Select("select channel_id,target_message_ts,team_id,source_message_ts,observed_state,observed_at,revision,question_permalink from slack_question_progress where " + OWNED)
    @Options(useCache = false)
    List<ObservationRow> current(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim);

    @Update("""
            update slack_question_progress set question_permalink=#{permalink},worker_id=null,locked_until=null,
                next_due_at=clock_timestamp(),consecutive_failures=0,failure_category=null
            where
            """ + OWNED + " and question_permalink is null")
    int saveQuestionPermalink(@Param("claim") com.java.system.sessionagent.slack.domain.SlackProgressClaim claim,
            @Param("permalink") String permalink);

    @Update("""
            update slack_question_progress set last_sent_revision=revision,last_sent_at=clock_timestamp(),
                consecutive_failures=0,failure_category=null,worker_id=null,locked_until=null,
                retired=#{terminal} and coalesce(terminal_settle_until <= clock_timestamp(),false),
                next_due_at=case when #{terminal} then coalesce(terminal_settle_until,clock_timestamp()+(#{settleMs}*interval '1 millisecond'))
                    else clock_timestamp()+(#{refreshMs}*interval '1 millisecond') end,
                terminal_settle_until=case when #{terminal} then coalesce(terminal_settle_until,clock_timestamp()+(#{settleMs}*interval '1 millisecond')) else null end
            where
            """ + OWNED + " and revision=#{revision} and observed_state=(select state from slack_progress_observation o"
            + " where o.message_job_id=slack_question_progress.message_job_id)")
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
            String observedState, OffsetDateTime observedAt, long revision, String questionPermalink) { }
}
