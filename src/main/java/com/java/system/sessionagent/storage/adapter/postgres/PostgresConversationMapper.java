package com.java.system.sessionagent.storage.adapter.postgres;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Typed SQL boundary for the runtime conversation tables.
 *
 * <p>The statements which coordinate leases, sequence allocation, and atomic
 * history writes intentionally remain explicit PostgreSQL SQL.  The mapper is
 * the only persistence boundary used by {@link PostgresConversationStore}; it
 * does not expose a generic SQL execution escape hatch.</p>
 */
@Mapper
public interface PostgresConversationMapper extends BaseMapper<PostgresConversationMapper.MessageJobEntity> {

    @Select("select pg_advisory_xact_lock(731_937_205)")
    String lockIntakeCapacity();

    @Select("select count(*) from message_job where status in ('PENDING', 'WORKING', 'RETRY')")
    long countUnfinishedJobs();

    @Select("select pg_advisory_xact_lock(#{firstLockKey}, #{secondLockKey})")
    String lockSourceIdentity(@Param("firstLockKey") int firstLockKey, @Param("secondLockKey") int secondLockKey);

    @Insert("""
            insert into conversation_session(session_id, source_type, session_key, selected_model, created_at)
            values (#{sessionId}, #{sourceType}, #{sessionKey}, #{selectedModel}, #{createdAt})
            on conflict (source_type, session_key) do nothing
            """)
    int insertSessionIfAbsent(
            @Param("sessionId") UUID sessionId,
            @Param("sourceType") String sourceType,
            @Param("sessionKey") String sessionKey,
            @Param("selectedModel") String selectedModel,
            @Param("createdAt") OffsetDateTime createdAt);

    @Select("""
            select session_id, selected_model
            from conversation_session
            where source_type = #{sourceType} and session_key = #{sessionKey}
            for update
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<SessionRow> lockSession(
            @Param("sourceType") String sourceType,
            @Param("sessionKey") String sessionKey);

    @Select("select session_id, selected_model from conversation_session where session_id = #{sessionId} for update")
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<SessionRow> lockSessionById(@Param("sessionId") UUID sessionId);

    @Update("update conversation_session set selected_model = #{selectedModel} where session_id = #{sessionId}")
    int selectModel(@Param("sessionId") UUID sessionId, @Param("selectedModel") String selectedModel);

    @Select(value = """
            update conversation_session set next_sequence = next_sequence + 1
            where session_id = #{sessionId}
            returning next_sequence - 1
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<Long> allocateSequence(@Param("sessionId") UUID sessionId);

    @Select("""
            select source.session_id, source.content_hash, job.message_job_id
            from source_message source
            join message_job job on job.session_id = source.session_id
                and job.user_message_sequence = source.user_message_sequence
            where source.source_type = #{sourceType} and source.source_message_id = #{sourceMessageId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<SourceMessageRow> findSourceMessage(
            @Param("sourceType") String sourceType,
            @Param("sourceMessageId") String sourceMessageId);

    @Insert("""
            insert into source_message(source_type, source_message_id, session_id, user_message_sequence, content_hash, created_at)
            values (#{sourceType}, #{sourceMessageId}, #{sessionId}, #{sequence}, #{contentHash}, #{createdAt})
            """)
    int insertSourceMessage(
            @Param("sourceType") String sourceType,
            @Param("sourceMessageId") String sourceMessageId,
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("contentHash") String contentHash,
            @Param("createdAt") OffsetDateTime createdAt);

    @Insert("""
            insert into session_message(session_id, sequence, message_job_id, role, created_at)
            values (#{sessionId}, #{sequence}, null, 'USER', #{createdAt})
            """)
    int insertUserSessionMessage(
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("createdAt") OffsetDateTime createdAt);

    @Insert("""
            insert into user_message(session_id, sequence, participant_id, source_type, source_message_id, message)
            values (#{sessionId}, #{sequence}, #{participantId}, #{sourceType}, #{sourceMessageId}, #{message})
            """)
    int insertUserMessage(
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("participantId") String participantId,
            @Param("sourceType") String sourceType,
            @Param("sourceMessageId") String sourceMessageId,
            @Param("message") String message);

    @Insert("""
            insert into message_job(message_job_id, session_id, user_message_sequence, model_key, model_fingerprint, status, available_at, created_at)
            values (#{jobId}, #{sessionId}, #{sequence}, #{modelKey}, #{modelFingerprint}, 'PENDING', clock_timestamp(), #{createdAt})
            """)
    int insertMessageJob(
            @Param("jobId") UUID jobId,
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("modelKey") String modelKey,
            @Param("modelFingerprint") String modelFingerprint,
            @Param("createdAt") OffsetDateTime createdAt);

    @Select(value = """
            with candidate as (
                select candidate.message_job_id, candidate.session_id
                from message_job candidate
                where ((candidate.status in ('PENDING', 'RETRY') and candidate.available_at <= clock_timestamp())
                        or (candidate.status = 'WORKING' and candidate.locked_until <= clock_timestamp()))
                  and not exists (
                      select 1 from message_job earlier
                      where earlier.session_id = candidate.session_id
                        and earlier.message_job_id <> candidate.message_job_id
                        and earlier.user_message_sequence < candidate.user_message_sequence
                        and earlier.status in ('PENDING', 'RETRY', 'WORKING'))
                  and not exists (
                      select 1 from message_job working
                      where working.session_id = candidate.session_id
                        and working.message_job_id <> candidate.message_job_id and working.status = 'WORKING')
                  and candidate.session_id != all(#{excludedSessionIds}::uuid[])
                order by candidate.available_at, candidate.created_at, candidate.message_job_id
                for update skip locked limit 1)
            update message_job job set status = 'WORKING', worker_id = #{workerId},
                locked_until = clock_timestamp() + (#{leaseMilliseconds} * interval '1 millisecond'), claim_number = job.claim_number + 1,
                phase = 'PREPARING', phase_started_at = clock_timestamp(), current_tool_name = null, failure_code = null
            from candidate where job.message_job_id = candidate.message_job_id and job.session_id = candidate.session_id
            returning job.message_job_id, job.session_id, job.model_key, job.model_fingerprint, job.worker_id, job.claim_number, job.locked_until,
                clock_timestamp() as claimed_at
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<WorkClaimRow> claimNext(
            @Param("workerId") String workerId,
            @Param("leaseMilliseconds") long leaseMilliseconds,
            @Param("excludedSessionIds") String[] excludedSessionIds);

    @Update("""
            update message_job set locked_until = clock_timestamp() + (#{leaseMilliseconds} * interval '1 millisecond')
            where message_job_id = #{messageJobId} and session_id = #{sessionId} and status = 'WORKING' and worker_id = #{workerId}
              and claim_number = #{claimNumber} and locked_until > clock_timestamp()
              and locked_until < clock_timestamp() + (#{leaseMilliseconds} * interval '1 millisecond')
            """)
    int extendClaim(
            @Param("leaseMilliseconds") long leaseMilliseconds,
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber);

    @Select("""
            select session.session_id as session_marker,
                message.sequence, message.message_job_id as event_message_job_id, user_job.message_job_id as user_message_job_id,
                message.created_at, message.role, user_detail.participant_id, user_detail.message as user_message,
                assistant_detail.message as assistant_message, calls_detail.message as assistant_tool_calls_message,
                calls_detail.calls, observation_detail.tool_call_id, observation_detail.tool_name, observation_detail.output,
                runtime_detail.code, runtime_detail.message as runtime_message
            from conversation_session session
            left join session_message message on message.session_id = session.session_id and message.sequence > #{afterSequence}
            left join user_message user_detail on user_detail.session_id = message.session_id
                and user_detail.sequence = message.sequence
            left join message_job user_job on user_job.session_id = message.session_id
                and user_job.user_message_sequence = message.sequence
            left join assistant_message assistant_detail on assistant_detail.session_id = message.session_id
                and assistant_detail.sequence = message.sequence
            left join assistant_tool_calls calls_detail on calls_detail.session_id = message.session_id
                and calls_detail.sequence = message.sequence
            left join tool_observation observation_detail on observation_detail.session_id = message.session_id
                and observation_detail.sequence = message.sequence
            left join runtime_message runtime_detail on runtime_detail.session_id = message.session_id
                and runtime_detail.sequence = message.sequence
            where session.session_id = #{sessionId}
            order by message.sequence asc
            limit #{limit}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<HistoryPageRow> loadHistoryPage(
            @Param("sessionId") UUID sessionId,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit);

    @Select("""
            select message.sequence, message.created_at, detail.participant_id, detail.message, job.message_job_id
            from session_message message join user_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
            join message_job job on job.session_id = message.session_id and job.user_message_sequence = message.sequence
            where message.session_id = #{sessionId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<UserMessageRow> loadUserMessages(@Param("sessionId") UUID sessionId);

    @Select("""
            select message.sequence, message.message_job_id, message.created_at, detail.tool_call_id, detail.tool_name, detail.output
            from session_message message join tool_observation detail on detail.session_id = message.session_id and detail.sequence = message.sequence
            where message.session_id = #{sessionId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<ToolObservationRow> loadToolObservations(@Param("sessionId") UUID sessionId);

    @Select("""
            select message.sequence, message.message_job_id, message.created_at, detail.message, detail.calls
            from session_message message join assistant_tool_calls detail on detail.session_id = message.session_id and detail.sequence = message.sequence
            where message.session_id = #{sessionId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<AssistantToolCallsRow> loadAssistantToolCalls(@Param("sessionId") UUID sessionId);

    @Select("""
            select message.sequence, message.message_job_id, message.created_at, detail.message
            from session_message message join assistant_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
            where message.session_id = #{sessionId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<AssistantMessageRow> loadAssistantMessages(@Param("sessionId") UUID sessionId);

    @Select("""
            select message.sequence, message.message_job_id, message.created_at, detail.code, detail.message
            from session_message message join runtime_message detail on detail.session_id = message.session_id and detail.sequence = message.sequence
            where message.session_id = #{sessionId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<RuntimeMessageRow> loadRuntimeMessages(@Param("sessionId") UUID sessionId);

    @Select("""
            select message_job_id, model_route_id
            from message_job
            where message_job_id = #{messageJobId} and session_id = #{sessionId}
            for update
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<ModelRouteRow> lockModelRoute(
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId);

    @Update("""
            update message_job set model_route_id = #{modelRouteId}
            where message_job_id = #{messageJobId} and session_id = #{sessionId}
            """)
    int bindModelRoute(
            @Param("modelRouteId") String modelRouteId,
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId);

    @Select("""
            select continuation.assistant_sequence, continuation.model_route_id, continuation.format, continuation.payload
            from model_continuation continuation
            join message_job job on job.message_job_id = continuation.message_job_id
                and job.session_id = continuation.session_id
            where continuation.message_job_id = #{messageJobId} and continuation.session_id = #{sessionId}
              and job.status = 'WORKING' and job.worker_id = #{workerId} and job.claim_number = #{claimNumber}
              and job.locked_until > clock_timestamp()
            order by continuation.assistant_sequence
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<ModelContinuationRow> loadContinuations(
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber);

    @Select("""
            select response_sequence, model_call_ordinal, prompt_tokens, completion_tokens, total_tokens, created_at
            from context_usage_checkpoint where session_id = #{sessionId} and model_key = #{modelKey}
              and model_fingerprint = #{modelFingerprint} and model_route_id = #{modelRouteId} and model_id = #{modelId}
              and request_shape_fingerprint = #{requestShapeFingerprint} and compact_generation = #{compactGeneration}
            order by response_sequence desc limit 1
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<UsageCheckpointRow> loadUsageCheckpoint(
            @Param("sessionId") UUID sessionId,
            @Param("modelKey") String modelKey,
            @Param("modelFingerprint") String modelFingerprint,
            @Param("modelRouteId") String modelRouteId,
            @Param("modelId") String modelId,
            @Param("requestShapeFingerprint") String requestShapeFingerprint,
            @Param("compactGeneration") long compactGeneration);

    @Select("""
            select generation, message_job_id, reason, summary, covered_through, model_route_id, model_id,
                context_window_tokens, request_shape_fingerprint, estimate_before_tokens, estimate_after_tokens, created_at
            from session_compaction where session_id = #{sessionId}
            order by generation desc limit 1
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<CompactionRow> loadCompaction(@Param("sessionId") UUID sessionId);

    @Select("select exists(select 1 from session_compaction where message_job_id = #{messageJobId} and reason = 'OVERFLOW')")
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    Boolean hasOverflowCompaction(@Param("messageJobId") UUID messageJobId);

    @Insert("""
            insert into session_compaction(session_id, generation, message_job_id, reason, summary, covered_through,
                model_route_id, model_id, context_window_tokens, request_shape_fingerprint, estimate_before_tokens,
                estimate_after_tokens, created_at)
            values (#{sessionId}, #{generation}, #{messageJobId}, #{reason}, #{summary}, #{coveredThrough},
                #{modelRouteId}, #{modelId}, #{contextWindowTokens}, #{requestShapeFingerprint}, #{estimateBeforeTokens},
                #{estimateAfterTokens}, #{createdAt})
            """)
    int insertCompaction(
            @Param("sessionId") UUID sessionId,
            @Param("generation") long generation,
            @Param("messageJobId") UUID messageJobId,
            @Param("reason") String reason,
            @Param("summary") String summary,
            @Param("coveredThrough") long coveredThrough,
            @Param("modelRouteId") String modelRouteId,
            @Param("modelId") String modelId,
            @Param("contextWindowTokens") long contextWindowTokens,
            @Param("requestShapeFingerprint") String requestShapeFingerprint,
            @Param("estimateBeforeTokens") long estimateBeforeTokens,
            @Param("estimateAfterTokens") long estimateAfterTokens,
            @Param("createdAt") OffsetDateTime createdAt);

    @Delete("""
            delete from model_continuation where message_job_id = #{messageJobId} and session_id = #{sessionId}
                and assistant_sequence <= #{coveredThrough}
            """)
    int deleteCoveredContinuations(
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("coveredThrough") long coveredThrough);

    @Select(value = """
            update message_job set model_calls = model_calls + 1,
                phase = 'MODEL_CALL', phase_started_at = #{startedAt}, current_tool_name = null, failure_code = null
            where message_job_id = #{messageJobId} and session_id = #{sessionId} and status = 'WORKING' and worker_id = #{workerId}
              and claim_number = #{claimNumber} and locked_until > clock_timestamp() and model_calls < #{maxModelCalls}
            returning model_calls
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<Integer> reserveModelCall(
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber,
            @Param("maxModelCalls") int maxModelCalls,
            @Param("startedAt") OffsetDateTime startedAt);

    @Update("""
            update message_job set phase = #{phase}, phase_started_at = #{startedAt},
                current_tool_name = #{toolName,jdbcType=VARCHAR}, failure_code = null
            where message_job_id = #{messageJobId} and session_id = #{sessionId} and status = 'WORKING' and worker_id = #{workerId}
              and claim_number = #{claimNumber} and locked_until > clock_timestamp()
            """)
    int updateProgress(
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber,
            @Param("phase") String phase,
            @Param("startedAt") OffsetDateTime startedAt,
            @Param("toolName") String toolName);

    @Insert("""
            insert into session_message(session_id, sequence, message_job_id, role, created_at)
            values (#{sessionId}, #{sequence}, #{jobId}, #{role}, #{createdAt})
            """)
    int insertSessionMessage(
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("jobId") UUID jobId,
            @Param("role") String role,
            @Param("createdAt") OffsetDateTime createdAt);

    @Insert("insert into assistant_message(session_id, sequence, message) values (#{sessionId}, #{sequence}, #{message})")
    int insertAssistantMessage(
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("message") String message);

    @Insert("insert into assistant_tool_calls(session_id, sequence, message, calls) values (#{sessionId}, #{sequence}, #{message,jdbcType=VARCHAR}, #{callsJson}::jsonb)")
    int insertAssistantToolCalls(
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("message") String message,
            @Param("callsJson") String callsJson);

    @Insert("""
            insert into tool_observation(session_id, sequence, tool_call_id, tool_name, output)
            values (#{sessionId}, #{sequence}, #{toolCallId}, #{toolName}, #{outputJson}::jsonb)
            """)
    int insertToolObservation(
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("toolCallId") String toolCallId,
            @Param("toolName") String toolName,
            @Param("outputJson") String outputJson);

    @Insert("insert into runtime_message(session_id, sequence, code, message) values (#{sessionId}, #{sequence}, #{code}, #{message})")
    int insertRuntimeMessage(
            @Param("sessionId") UUID sessionId,
            @Param("sequence") long sequence,
            @Param("code") String code,
            @Param("message") String message);

    @Insert("""
            insert into context_usage_checkpoint(session_id, response_sequence, model_route_id, model_id, model_key, model_fingerprint, model_call_ordinal,
                prompt_tokens, completion_tokens, total_tokens, request_shape_fingerprint, compact_generation, created_at)
            values (#{sessionId}, #{responseSequence}, #{modelRouteId}, #{modelId}, #{modelKey}, #{modelFingerprint}, #{modelCallOrdinal}, #{promptTokens},
                #{completionTokens}, #{totalTokens}, #{requestShapeFingerprint}, #{compactGeneration}, #{createdAt})
            """)
    int insertUsageCheckpoint(
            @Param("sessionId") UUID sessionId,
            @Param("responseSequence") long responseSequence,
            @Param("modelRouteId") String modelRouteId,
            @Param("modelId") String modelId,
            @Param("modelKey") String modelKey,
            @Param("modelFingerprint") String modelFingerprint,
            @Param("modelCallOrdinal") int modelCallOrdinal,
            @Param("promptTokens") long promptTokens,
            @Param("completionTokens") long completionTokens,
            @Param("totalTokens") long totalTokens,
            @Param("requestShapeFingerprint") String requestShapeFingerprint,
            @Param("compactGeneration") long compactGeneration,
            @Param("createdAt") OffsetDateTime createdAt);

    @Insert("""
            insert into model_continuation(message_job_id, session_id, assistant_sequence, model_route_id, format, payload)
            values (#{messageJobId}, #{sessionId}, #{assistantSequence}, #{modelRouteId}, #{format}, #{payload})
            """)
    int insertContinuation(
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("assistantSequence") long assistantSequence,
            @Param("modelRouteId") String modelRouteId,
            @Param("format") String format,
            @Param("payload") byte[] payload);

    @Delete("delete from model_continuation where message_job_id = #{messageJobId} and session_id = #{sessionId}")
    int deleteContinuations(@Param("messageJobId") UUID messageJobId, @Param("sessionId") UUID sessionId);

    @Update("""
            update message_job set status = 'RETRY', retry_count = retry_count + 1,
                available_at = clock_timestamp() + (#{retryMilliseconds} * interval '1 millisecond'), worker_id = null, locked_until = null,
                phase = null, phase_started_at = null, current_tool_name = null, failure_code = #{failureCode}
            where message_job_id = #{messageJobId} and session_id = #{sessionId} and status = 'WORKING' and worker_id = #{workerId}
              and claim_number = #{claimNumber} and locked_until > clock_timestamp()
            """)
    int scheduleRetry(
            @Param("retryMilliseconds") long retryMilliseconds,
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber,
            @Param("failureCode") String failureCode);

    @Update("""
            update message_job set locked_until = locked_until
            where message_job_id = #{messageJobId} and session_id = #{sessionId} and status = 'WORKING' and worker_id = #{workerId}
              and claim_number = #{claimNumber} and locked_until > clock_timestamp()
            """)
    int requireLiveClaim(
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber);

    @Update("""
            update message_job set phase = 'PREPARING', phase_started_at = #{startedAt}, current_tool_name = null,
                failure_code = #{failureCode,jdbcType=VARCHAR}
            where message_job_id = #{messageJobId} and session_id = #{sessionId} and status = 'WORKING' and worker_id = #{workerId}
              and claim_number = #{claimNumber} and locked_until > clock_timestamp()
            """)
    int finishWorkingBatch(
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber,
            @Param("startedAt") OffsetDateTime startedAt,
            @Param("failureCode") String failureCode);

    @Update("""
            update message_job set status = 'DONE', completed_at = #{completedAt}, worker_id = null, locked_until = null,
                phase = null, phase_started_at = null, current_tool_name = null, failure_code = #{failureCode,jdbcType=VARCHAR}
            where message_job_id = #{messageJobId} and session_id = #{sessionId} and status = 'WORKING' and worker_id = #{workerId}
              and claim_number = #{claimNumber} and locked_until > clock_timestamp()
            """)
    int completeJob(
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("messageJobId") UUID messageJobId,
            @Param("sessionId") UUID sessionId,
            @Param("workerId") String workerId,
            @Param("claimNumber") long claimNumber,
            @Param("failureCode") String failureCode);

    @Select("""
            select message_job_id, session_id, status, retry_count, model_calls,
                case when status = 'WORKING' and locked_until > statement_timestamp() then phase end as phase,
                case when status = 'WORKING' and locked_until > statement_timestamp() then phase_started_at end as phase_started_at,
                case when status = 'WORKING' and locked_until > statement_timestamp() then current_tool_name end as current_tool_name,
                case when status = 'WORKING' and locked_until <= statement_timestamp() then 'PROCESSING_INTERRUPTED'
                    else failure_code end as failure_code
            from message_job where message_job_id = #{messageJobId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<JobProjectionRow> readJob(@Param("messageJobId") UUID messageJobId);

    @Select("""
            select session.session_id, session.created_at, session.selected_model, job.message_job_id, job.status, job.retry_count, job.model_calls,
                job.phase, job.phase_started_at, job.current_tool_name, job.failure_code
            from conversation_session session
            left join lateral (
                select message_job_id, session_id, status, retry_count, model_calls,
                    case when status = 'WORKING' and locked_until > statement_timestamp() then phase end as phase,
                    case when status = 'WORKING' and locked_until > statement_timestamp() then phase_started_at end as phase_started_at,
                    case when status = 'WORKING' and locked_until > statement_timestamp() then current_tool_name end as current_tool_name,
                    case when status = 'WORKING' and locked_until <= statement_timestamp() then 'PROCESSING_INTERRUPTED'
                        else failure_code end as failure_code
                from message_job where session_id = session.session_id
                order by user_message_sequence desc, message_job_id desc limit 1
            ) job on true
            where session.session_id = #{sessionId}
            """)
    @Options(flushCache = Options.FlushCachePolicy.FALSE, useCache = false)
    List<SessionProjectionRow> readSession(@Param("sessionId") UUID sessionId);

    @TableName("message_job")
    class MessageJobEntity {

        @TableId(value = "message_job_id", type = IdType.INPUT)
        private UUID messageJobId;
        private UUID sessionId;
        private long userMessageSequence;
        private String status;
        private int modelCalls;
        private String modelRouteId;
        private int retryCount;
        private OffsetDateTime availableAt;
        private long claimNumber;
        private String workerId;
        private OffsetDateTime lockedUntil;
        private OffsetDateTime createdAt;
        private OffsetDateTime completedAt;

        public UUID getMessageJobId() {
            return messageJobId;
        }

        public void setMessageJobId(UUID messageJobId) {
            this.messageJobId = messageJobId;
        }

        public UUID getSessionId() {
            return sessionId;
        }

        public void setSessionId(UUID sessionId) {
            this.sessionId = sessionId;
        }

        public long getUserMessageSequence() {
            return userMessageSequence;
        }

        public void setUserMessageSequence(long userMessageSequence) {
            this.userMessageSequence = userMessageSequence;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getModelCalls() {
            return modelCalls;
        }

        public void setModelCalls(int modelCalls) {
            this.modelCalls = modelCalls;
        }

        public String getModelRouteId() {
            return modelRouteId;
        }

        public void setModelRouteId(String modelRouteId) {
            this.modelRouteId = modelRouteId;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }

        public OffsetDateTime getAvailableAt() {
            return availableAt;
        }

        public void setAvailableAt(OffsetDateTime availableAt) {
            this.availableAt = availableAt;
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

        public OffsetDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(OffsetDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public OffsetDateTime getCompletedAt() {
            return completedAt;
        }

        public void setCompletedAt(OffsetDateTime completedAt) {
            this.completedAt = completedAt;
        }
    }

    record SessionRow(UUID sessionId, String selectedModel) {
    }

    record SourceMessageRow(UUID sessionId, String contentHash, UUID messageJobId) {
    }

    record WorkClaimRow(
            UUID messageJobId,
            UUID sessionId,
            String modelKey,
            String modelFingerprint,
            String workerId,
            long claimNumber,
            OffsetDateTime claimedAt,
            OffsetDateTime lockedUntil) {
    }

    record HistoryPageRow(
            UUID sessionMarker,
            Long sequence,
            UUID eventMessageJobId,
            UUID userMessageJobId,
            OffsetDateTime createdAt,
            String role,
            String participantId,
            String userMessage,
            String assistantMessage,
            String assistantToolCallsMessage,
            String calls,
            String toolCallId,
            String toolName,
            String output,
            String code,
            String runtimeMessage) {
    }

    record UserMessageRow(
            long sequence,
            OffsetDateTime createdAt,
            String participantId,
            String message,
            UUID messageJobId) {
    }

    record ToolObservationRow(
            long sequence,
            UUID messageJobId,
            OffsetDateTime createdAt,
            String toolCallId,
            String toolName,
            String output) {
    }

    record AssistantToolCallsRow(
            long sequence,
            UUID messageJobId,
            OffsetDateTime createdAt,
            String message,
            String calls) {
    }

    record AssistantMessageRow(
            long sequence,
            UUID messageJobId,
            OffsetDateTime createdAt,
            String message) {
    }

    record RuntimeMessageRow(
            long sequence,
            UUID messageJobId,
            OffsetDateTime createdAt,
            String code,
            String message) {
    }

    record ModelRouteRow(UUID messageJobId, String modelRouteId) {
    }

    record ModelContinuationRow(long assistantSequence, String modelRouteId, String format, byte[] payload) {
    }

    record UsageCheckpointRow(
            long responseSequence,
            int modelCallOrdinal,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            OffsetDateTime createdAt) {
    }

    record CompactionRow(
            long generation,
            UUID messageJobId,
            String reason,
            String summary,
            long coveredThrough,
            String modelRouteId,
            String modelId,
            long contextWindowTokens,
            String requestShapeFingerprint,
            long estimateBeforeTokens,
            long estimateAfterTokens,
            OffsetDateTime createdAt) {
    }

    record JobProjectionRow(
            UUID messageJobId,
            UUID sessionId,
            String status,
            int retryCount,
            int modelCalls,
            String phase,
            OffsetDateTime phaseStartedAt,
            String currentToolName,
            String failureCode) {
    }

    record SessionProjectionRow(
            UUID sessionId,
            OffsetDateTime createdAt,
            String selectedModel,
            UUID messageJobId,
            String status,
            Integer retryCount,
            Integer modelCalls,
            String phase,
            OffsetDateTime phaseStartedAt,
            String currentToolName,
            String failureCode) {
    }
}
