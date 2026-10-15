alter table message_job
    add column phase text,
    add column phase_started_at timestamptz,
    add column current_tool_name text,
    add column failure_code text;

alter table message_job
    add constraint message_job_phase_valid check (
        phase is null or phase in ('PREPARING', 'MODEL_CALL', 'TOOL_EXECUTION', 'HISTORY_COMMIT')),
    add constraint message_job_phase_start_paired check (
        (phase is null) = (phase_started_at is null)),
    add constraint message_job_progress_only_working check (
        status = 'WORKING' or phase is null),
    add constraint message_job_tool_phase_paired check (
        (current_tool_name is not null) = coalesce(phase = 'TOOL_EXECUTION', false)),
    add constraint message_job_failure_allowlisted check (
        failure_code is null or failure_code in (
            'MODEL_CALL_LIMIT_REACHED', 'MODEL_OUTPUT_INVALID', 'MODEL_UNAVAILABLE',
            'CONTEXT_TOO_LARGE', 'COMPACTION_FAILED', 'INVALID_CONVERSATION_HISTORY',
            'STORAGE_UNAVAILABLE', 'PROCESSING_INTERRUPTED'));

-- Only a terminal runtime event denotes failure. A later successful assistant
-- response must not inherit an earlier recoverable runtime failure.
update message_job job
set failure_code = terminal.code
from (
    select distinct on (message.message_job_id)
        message.message_job_id, message.role, runtime.code
    from session_message message
    left join runtime_message runtime
        on runtime.session_id = message.session_id and runtime.sequence = message.sequence
    where message.message_job_id is not null
    order by message.message_job_id, message.sequence desc
) terminal
where job.message_job_id = terminal.message_job_id
    and job.status = 'DONE'
    and terminal.role = 'RUNTIME'
    and terminal.code in (
        'MODEL_CALL_LIMIT_REACHED', 'MODEL_OUTPUT_INVALID', 'MODEL_UNAVAILABLE',
        'CONTEXT_TOO_LARGE', 'COMPACTION_FAILED', 'INVALID_CONVERSATION_HISTORY',
        'STORAGE_UNAVAILABLE', 'PROCESSING_INTERRUPTED');
