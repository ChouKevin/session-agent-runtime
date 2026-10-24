-- The composite FK prevents a target from borrowing another question's notice.
alter table slack_delivery add constraint uq_slack_delivery_progress_identity
    unique (delivery_id, message_job_id, kind, team_id, channel_id, message_ts, slack_message_ts);

create table slack_question_progress (
    message_job_id uuid primary key references message_job(message_job_id),
    notice_delivery_id uuid not null unique,
    notice_kind varchar(16) not null default 'QUEUED' check (notice_kind = 'QUEUED'),
    team_id varchar(128) not null,
    channel_id varchar(128) not null,
    source_message_ts varchar(64) not null,
    target_message_ts varchar(64) not null,
    question_permalink text,
    observed_state varchar(32),
    revision bigint not null default 0 check (revision >= 0),
    observed_at timestamptz,
    last_sent_revision bigint not null default 0 check (last_sent_revision >= 0 and last_sent_revision <= revision),
    last_sent_at timestamptz,
    next_due_at timestamptz not null default clock_timestamp(),
    worker_id varchar(128),
    claim_number bigint not null default 0 check (claim_number >= 0),
    locked_until timestamptz,
    consecutive_failures integer not null default 0 check (consecutive_failures >= 0),
    failure_category varchar(16) check (failure_category in ('RATE_LIMIT', 'TRANSIENT', 'PERMANENT')),
    terminal_settle_until timestamptz,
    retired boolean not null default false,
    foreign key (notice_delivery_id, message_job_id, notice_kind, team_id, channel_id, source_message_ts, target_message_ts)
        references slack_delivery(delivery_id, message_job_id, kind, team_id, channel_id, message_ts, slack_message_ts),
    check ((worker_id is null) = (locked_until is null)),
    check (worker_id is null or claim_number > 0),
    check (not retired or worker_id is null),
    check ((observed_state is null) = (observed_at is null)),
    check ((revision = 0) = (observed_state is null)),
    check ((last_sent_revision = 0) = (last_sent_at is null)),
    check (observed_state is null or observed_state in ('QUEUED','PREPARING','MODEL_CALL','TOOL_EXECUTION',
        'HISTORY_COMMIT','RETRY','STOPPING','INTERRUPTED','DELIVERING','COMPLETED','CANCELLED','FAILED','DELIVERY_FAILED'))
);
create index ix_slack_progress_due on slack_question_progress(next_due_at, message_job_id) where not retired;
create table slack_progress_workspace (
    team_id varchar(128) primary key,
    next_allowed_at timestamptz not null default clock_timestamp()
);

-- One statement snapshot observes job, answer deliveries and every persisted part.
-- Delivery success is independent of presentation failure/cancellation precedence.
create view slack_progress_observation as
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
    select count(*) > 0 and coalesce(bool_and(answer.status = 'SENT'
        and exists (select 1 from slack_delivery_part part where part.delivery_id = answer.delivery_id)
        and not exists (select 1 from slack_delivery_part part
            where part.delivery_id = answer.delivery_id and part.status <> 'SENT')), false) as delivered,
        coalesce(bool_or(answer.status = 'FAILED' or exists (
            select 1 from slack_delivery_part part
            where part.delivery_id = answer.delivery_id and part.status = 'FAILED')), false) as failed
    from slack_delivery answer
    where answer.message_job_id = job.message_job_id and answer.kind = 'ANSWER'
) answers;
