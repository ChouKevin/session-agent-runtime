alter table message_job
    add column ordinary_calls integer not null default 0 check (ordinary_calls >= 0),
    add column execution_mode varchar(16) not null default 'NORMAL' check (execution_mode in ('NORMAL', 'REPORT_ONLY')),
    add column control_stage varchar(16) not null default 'INTENT' check (control_stage in ('INTENT', 'EXECUTION', 'WRAP_UP')),
    add column intent_attempted boolean not null default false,
    add column wrap_attempted boolean not null default false,
    add column follow_up_intent varchar(16) check (follow_up_intent in ('CONTINUE', 'DELIVER', 'CLARIFY', 'NEW_QUESTION'));

-- Preserve active budgets conservatively; historical failures do not imply waiting.
update message_job set ordinary_calls = model_calls, control_stage = 'EXECUTION';

create table session_waiting_choice (
    session_id uuid primary key references conversation_session(session_id),
    origin_job uuid not null,
    response_sequence bigint not null,
    generation bigint not null check (generation > 0),
    pending boolean not null,
    foreign key (origin_job, session_id) references message_job(message_job_id, session_id),
    foreign key (session_id, response_sequence) references assistant_message(session_id, sequence)
);

alter table message_job add column completion_disposition varchar(16) not null default 'NONE'
    check (completion_disposition in ('NONE','ANSWER','WAITING'));

create table skill_load_attempt (
    attempt_id uuid primary key,
    ordinal bigint generated always as identity unique,
    message_job_id uuid not null,
    session_id uuid not null,
    source varchar(16) not null check (source in ('AUTOMATIC','EXPLICIT')),
    skill_name varchar(64) not null,
    resource_uri text not null,
    resource_kind varchar(16) not null check (resource_kind in ('DEFINITION','REFERENCE')),
    worker_id varchar(128) not null,
    claim_number bigint not null check (claim_number > 0),
    model_call_number bigint,
    tool_ordinal integer,
    status varchar(32) not null default 'STARTED'
        check (status in ('STARTED','COMMITTED_SUCCESS','COMMITTED_FAILURE','INTERRUPTED')),
    history_sequence bigint,
    sha256 varchar(64),
    error_code varchar(64),
    started_at timestamptz not null default clock_timestamp(),
    finished_at timestamptz,
    started_sent_at timestamptz,
    result_sent_at timestamptz,
    foreign key (message_job_id,session_id) references message_job(message_job_id,session_id),
    foreign key (session_id,history_sequence) references session_message(session_id,sequence),
    unique nulls not distinct (message_job_id,source,claim_number,model_call_number,tool_ordinal),
    check ((source='EXPLICIT' and model_call_number is null and tool_ordinal is null)
        or (source='AUTOMATIC' and model_call_number > 0 and tool_ordinal >= 0)),
    check ((status in ('COMMITTED_SUCCESS','COMMITTED_FAILURE')) = (history_sequence is not null)),
    check ((status='COMMITTED_SUCCESS') = (sha256 is not null)),
    check ((status='STARTED') = (finished_at is null))
);
create index ix_skill_load_pending on skill_load_attempt(message_job_id,ordinal) where result_sent_at is null;

-- Claim recovery and terminal transitions cannot leave an abandoned read looking active.
create function interrupt_abandoned_skill_loads() returns trigger language plpgsql as $$
begin
    update skill_load_attempt set status='INTERRUPTED',finished_at=clock_timestamp(),error_code='SKILL_INTERRUPTED'
    where message_job_id=new.message_job_id and status='STARTED'
        and (new.status <> 'WORKING' or claim_number <> new.claim_number or worker_id is distinct from new.worker_id);
    return new;
end;
$$;
create trigger reconcile_skill_loads after update of status,claim_number on message_job
    for each row execute function interrupt_abandoned_skill_loads();

alter table slack_question_progress
    add column presentation_id uuid references skill_load_attempt(attempt_id),
    add column presentation_kind varchar(32) not null default 'PROGRESS',
    add column skill_name varchar(64),
    add column resource_kind varchar(16);

-- Replace only the old state vocabulary constraint; preserve all lease/revision invariants.
do $$
declare state_constraint text;
begin
    select conname into strict state_constraint from pg_constraint
        where conrelid='slack_question_progress'::regclass and contype='c'
            and conkey = array[(select attnum from pg_attribute
                where attrelid='slack_question_progress'::regclass and attname='observed_state')]
            and pg_get_constraintdef(oid) like '%QUEUED%';
    execute format('alter table slack_question_progress drop constraint %I',state_constraint);
end;
$$;
alter table slack_question_progress add constraint ck_slack_progress_state check
    (observed_state is null or observed_state in ('QUEUED','PREPARING','MODEL_CALL','TOOL_EXECUTION',
        'HISTORY_COMMIT','RETRY','STOPPING','INTERRUPTED','DELIVERING','COMPLETED','CANCELLED','FAILED','DELIVERY_FAILED',
        'WAITING_USER','SKILL_LOADING','SKILL_LOADED','SKILL_FAILED','SKILL_INTERRUPTED'));

-- Preserve V10's complete text/file delivery predicate rather than duplicating it.
alter view slack_progress_observation rename to slack_job_progress_observation;
create view slack_progress_observation as
select base.message_job_id,base.job_status,base.answers_delivered,
    case when pending.attempt_id is not null then case pending.status
            when 'STARTED' then 'SKILL_LOADING'
            when 'COMMITTED_SUCCESS' then 'SKILL_LOADED'
            when 'COMMITTED_FAILURE' then 'SKILL_FAILED'
            else 'SKILL_INTERRUPTED' end
        when job.status='DONE' and job.completion_disposition='WAITING'
            and base.state not in ('FAILED','DELIVERY_FAILED') then 'WAITING_USER'
        else base.state end as state,
    pending.attempt_id as presentation_id,coalesce(pending.status,'PROGRESS') as presentation_kind,
    pending.skill_name,pending.resource_kind
from slack_job_progress_observation base join message_job job using(message_job_id)
left join lateral (
    select load.attempt_id,load.status,load.skill_name,load.resource_kind
    from skill_load_attempt load where load.message_job_id=base.message_job_id and load.result_sent_at is null
        and (load.status <> 'STARTED' or load.started_sent_at is null)
    order by (load.status='STARTED'),load.ordinal limit 1
) pending on true;
