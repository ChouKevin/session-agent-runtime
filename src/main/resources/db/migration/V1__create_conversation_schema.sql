create table conversation_session (
    session_id uuid primary key,
    source_type varchar(32) not null,
    session_key varchar(256) not null,
    next_sequence bigint not null default 1 check (next_sequence >= 1),
    created_at timestamptz not null,
    unique (source_type, session_key)
);

create table source_message (
    source_type varchar(32) not null,
    source_message_id varchar(256) not null,
    session_id uuid not null references conversation_session(session_id),
    user_message_sequence bigint not null,
    content_hash char(64) not null,
    created_at timestamptz not null,
    primary key (source_type, source_message_id),
    unique (source_type, source_message_id, session_id, user_message_sequence)
);

create table session_message (
    session_id uuid not null references conversation_session(session_id),
    sequence bigint not null check (sequence >= 1),
    message_job_id uuid,
    role varchar(32) not null check (role in ('USER', 'TOOL', 'ASSISTANT_TOOL_CALLS', 'ASSISTANT', 'RUNTIME')),
    created_at timestamptz not null,
    primary key (session_id, sequence),
    unique (session_id, sequence, role),
    check ((role = 'USER') = (message_job_id is null))
);

create table user_message (
    session_id uuid not null,
    sequence bigint not null,
    role varchar(16) not null default 'USER' check (role = 'USER'),
    participant_id varchar(256) not null,
    source_type varchar(32) not null,
    source_message_id varchar(256) not null,
    message text not null check (length(message) > 0),
    primary key (session_id, sequence),
    foreign key (session_id, sequence, role) references session_message(session_id, sequence, role),
    foreign key (source_type, source_message_id, session_id, sequence)
        references source_message(source_type, source_message_id, session_id, user_message_sequence)
);

create table message_job (
    message_job_id uuid primary key,
    session_id uuid not null,
    user_message_sequence bigint not null,
    status varchar(16) not null check (status in ('PENDING', 'WORKING', 'RETRY', 'DONE')),
    model_calls integer not null default 0 check (model_calls >= 0),
    retry_count integer not null default 0 check (retry_count >= 0),
    available_at timestamptz not null,
    claim_number bigint not null default 0 check (claim_number >= 0),
    worker_id varchar(128),
    locked_until timestamptz,
    created_at timestamptz not null,
    completed_at timestamptz,
    unique (message_job_id, session_id),
    unique (session_id, user_message_sequence),
    foreign key (session_id, user_message_sequence) references user_message(session_id, sequence),
    check ((status = 'WORKING') = (worker_id is not null and locked_until is not null and claim_number > 0)),
    check ((status = 'DONE') = (completed_at is not null)),
    check (status <> 'DONE' or (worker_id is null and locked_until is null))
);

alter table session_message add constraint fk_session_message_job
    foreign key (message_job_id, session_id) references message_job(message_job_id, session_id);

create table assistant_message (
    session_id uuid not null,
    sequence bigint not null,
    role varchar(16) not null default 'ASSISTANT' check (role = 'ASSISTANT'),
    message text not null check (length(message) > 0),
    primary key (session_id, sequence),
    foreign key (session_id, sequence, role) references session_message(session_id, sequence, role)
);

create table assistant_tool_calls (
    session_id uuid not null,
    sequence bigint not null,
    role varchar(32) not null default 'ASSISTANT_TOOL_CALLS' check (role = 'ASSISTANT_TOOL_CALLS'),
    message text check (message is null or length(message) > 0),
    calls jsonb not null check (jsonb_typeof(calls) = 'array' and jsonb_array_length(calls) > 0),
    primary key (session_id, sequence),
    foreign key (session_id, sequence, role) references session_message(session_id, sequence, role)
);

create table tool_observation (
    session_id uuid not null,
    sequence bigint not null,
    role varchar(16) not null default 'TOOL' check (role = 'TOOL'),
    tool_call_id text not null check (tool_call_id ~ '[^[:space:]]'),
    tool_name varchar(128) not null check (tool_name ~ '[^[:space:]]'),
    output jsonb not null,
    primary key (session_id, sequence),
    foreign key (session_id, sequence, role) references session_message(session_id, sequence, role)
);

create table runtime_message (
    session_id uuid not null,
    sequence bigint not null,
    role varchar(16) not null default 'RUNTIME' check (role = 'RUNTIME'),
    code varchar(64) not null check (code ~ '[^[:space:]]'),
    message text not null check (message ~ '[^[:space:]]'),
    primary key (session_id, sequence),
    foreign key (session_id, sequence, role) references session_message(session_id, sequence, role)
);

create unique index uq_one_working_job_per_session on message_job(session_id) where status = 'WORKING';
create index ix_claimable_job on message_job(available_at, created_at, message_job_id)
    where status in ('PENDING', 'RETRY');

create function reject_committed_row_change() returns trigger language plpgsql as $$
begin
    raise exception 'committed conversation rows are append-only';
end;
$$;

create trigger source_message_append_only before update or delete on source_message
    for each row execute function reject_committed_row_change();
create trigger session_message_append_only before update or delete on session_message
    for each row execute function reject_committed_row_change();
create trigger user_message_append_only before update or delete on user_message
    for each row execute function reject_committed_row_change();
create trigger assistant_message_append_only before update or delete on assistant_message
    for each row execute function reject_committed_row_change();
create trigger assistant_tool_calls_append_only before update or delete on assistant_tool_calls
    for each row execute function reject_committed_row_change();
create trigger tool_observation_append_only before update or delete on tool_observation
    for each row execute function reject_committed_row_change();
create trigger runtime_message_append_only before update or delete on runtime_message
    for each row execute function reject_committed_row_change();

create function restrict_conversation_session_change() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then raise exception 'conversation sessions must not be deleted'; end if;
    if new.session_id is distinct from old.session_id
        or new.source_type is distinct from old.source_type
        or new.session_key is distinct from old.session_key
        or new.created_at is distinct from old.created_at
        or new.next_sequence <> old.next_sequence + 1 then
        raise exception 'conversation session identity is immutable and next sequence must increase';
    end if;
    return new;
end;
$$;

create trigger conversation_session_immutable before update or delete on conversation_session
    for each row execute function restrict_conversation_session_change();

create function reject_message_job_identity_change() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then raise exception 'message jobs must not be deleted'; end if;
    if new.message_job_id is distinct from old.message_job_id
        or new.session_id is distinct from old.session_id
        or new.user_message_sequence is distinct from old.user_message_sequence
        or new.created_at is distinct from old.created_at then
        raise exception 'message job identity is immutable';
    end if;
    return new;
end;
$$;

create trigger message_job_identity_immutable before update or delete on message_job
    for each row execute function reject_message_job_identity_change();

create function require_message_detail() returns trigger language plpgsql as $$
declare detail_count integer;
begin
    select case new.role
        when 'USER' then (select count(*) from user_message where session_id = new.session_id and sequence = new.sequence)
        when 'TOOL' then (select count(*) from tool_observation where session_id = new.session_id and sequence = new.sequence)
        when 'ASSISTANT_TOOL_CALLS' then (select count(*) from assistant_tool_calls where session_id = new.session_id and sequence = new.sequence)
        when 'ASSISTANT' then (select count(*) from assistant_message where session_id = new.session_id and sequence = new.sequence)
        when 'RUNTIME' then (select count(*) from runtime_message where session_id = new.session_id and sequence = new.sequence)
    end into detail_count;
    if detail_count <> 1 then raise exception 'session message must have exactly one matching detail'; end if;
    return new;
end;
$$;

create constraint trigger session_message_requires_detail after insert on session_message
    deferrable initially deferred for each row execute function require_message_detail();
create constraint trigger tool_observation_requires_one_detail after insert on tool_observation
    deferrable initially deferred for each row execute function require_message_detail();

create function require_source_user() returns trigger language plpgsql as $$
declare user_count integer;
begin
    select count(*) into user_count from user_message
        where source_type = new.source_type and source_message_id = new.source_message_id
          and session_id = new.session_id and sequence = new.user_message_sequence;
    if user_count <> 1 then raise exception 'source message must identify exactly one user message'; end if;
    return new;
end;
$$;

create constraint trigger source_message_requires_user after insert on source_message
    deferrable initially deferred for each row execute function require_source_user();
