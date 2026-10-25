create table generated_artifact (
    artifact_id uuid primary key,
    session_id uuid not null,
    message_job_id uuid not null,
    model_call_number bigint not null check (model_call_number > 0),
    tool_ordinal integer not null check (tool_ordinal >= 0),
    title text not null check (char_length(title) <= 120),
    filename text not null,
    media_type text not null,
    content bytea not null check (octet_length(content) <= 1048576),
    tool_name text not null,
    observation_token uuid not null unique,
    expected_observation jsonb not null,
    input_arguments jsonb not null,
    observation_sequence bigint,
    released boolean not null default false,
    created_at timestamptz not null default clock_timestamp(),
    unique (message_job_id, model_call_number, tool_ordinal),
    foreign key (message_job_id, session_id) references message_job(message_job_id, session_id),
    foreign key (session_id, observation_sequence) references tool_observation(session_id, sequence),
    check (not released or observation_sequence is not null)
);
create index generated_artifact_released_job on generated_artifact(message_job_id) where released;

-- Every required item, including released tool artifacts, is snapshotted before acknowledgement.
alter table slack_delivery add column items_prepared boolean not null default false;
alter table slack_delivery_part add column presentation text not null default 'PLAIN'
    check (presentation in ('PLAIN', 'MARKDOWN', 'FILE'));

-- Released V9 already owns the complete durable part set. Preserve its identities,
-- plain presentation and acknowledgement evidence; never regenerate that answer.
update slack_delivery delivery set items_prepared = true
where exists (select 1 from slack_delivery_part part where part.delivery_id = delivery.delivery_id);

create table slack_delivery_upload (
    delivery_id uuid not null,
    part_index integer not null,
    identity uuid not null,
    filename text not null,
    title text not null,
    media_type text not null,
    content bytea not null,
    state text not null check (state in ('PREPARED', 'ALLOCATED', 'UPLOADED', 'FINALIZING', 'SENT', 'FAILED', 'UNCONFIRMED')),
    file_id text,
    upload_url text,
    finalization_claim bigint,
    primary key (delivery_id, part_index),
    unique (delivery_id, identity),
    foreign key (delivery_id, part_index) references slack_delivery_part(delivery_id, part_index),
    check ((state = 'PREPARED') or (state = 'FAILED') or (file_id is not null and upload_url is not null)),
    check (state <> 'FINALIZING' or finalization_claim is not null)
);

alter table slack_delivery drop constraint slack_delivery_failure_category_check;
alter table slack_delivery add constraint slack_delivery_failure_category_check
    check (failure_category in ('RATE_LIMIT', 'TRANSIENT', 'PERMANENT', 'UNCONFIRMED'));

-- Completion observes the entire prepared item set, including durable upload evidence.
create or replace view slack_progress_observation as
select job.message_job_id, job.status as job_status, answers.delivered as answers_delivered,
    case
        when job.status = 'CANCELLED' then 'CANCELLED'
        when job.status = 'DONE' and job.failure_code is not null then 'FAILED'
        when job.cancellation_requested_at is not null then 'STOPPING'
        when job.status = 'DONE' then case
            when answers.failed then 'DELIVERY_FAILED'
            when answers.delivered then 'COMPLETED'
            else 'DELIVERING' end
        when job.status = 'RETRY' then 'RETRY'
        when job.status = 'WORKING' and job.locked_until > statement_timestamp() then coalesce(job.phase, 'PREPARING')
        when job.status = 'WORKING' then 'INTERRUPTED'
        else 'QUEUED'
    end as state
from message_job job
cross join lateral (
    select count(*) > 0 and coalesce(bool_and(answer.status = 'SENT' and answer.items_prepared
        and exists (select 1 from slack_delivery_part part where part.delivery_id = answer.delivery_id)
        and not exists (select 1 from slack_delivery_part part
            where part.delivery_id = answer.delivery_id and (part.status <> 'SENT'
                or (part.presentation = 'FILE' and not exists (
                    select 1 from slack_delivery_upload upload
                    where upload.delivery_id = part.delivery_id and upload.part_index = part.part_index
                        and upload.state = 'SENT'))))), false) as delivered,
        coalesce(bool_or(answer.status = 'FAILED' or exists (
            select 1 from slack_delivery_part part
            where part.delivery_id = answer.delivery_id and part.status = 'FAILED') or exists (
            select 1 from slack_delivery_upload upload
            where upload.delivery_id = answer.delivery_id and upload.state in ('FAILED', 'UNCONFIRMED'))), false) as failed
    from slack_delivery answer
    where answer.message_job_id = job.message_job_id and answer.kind = 'ANSWER'
) answers;
