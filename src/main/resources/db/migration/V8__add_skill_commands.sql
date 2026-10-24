alter table session_message drop constraint session_message_role_check;
alter table session_message add constraint session_message_role_check
    check (role in ('USER', 'TOOL', 'ASSISTANT_TOOL_CALLS', 'ASSISTANT', 'RUNTIME', 'SKILL'));

alter table message_job drop constraint message_job_failure_allowlisted;
alter table message_job add constraint message_job_failure_allowlisted check (
    failure_code is null or failure_code in (
        'MODEL_CALL_LIMIT_REACHED', 'MODEL_OUTPUT_INVALID', 'MODEL_UNAVAILABLE',
        'CONTEXT_TOO_LARGE', 'COMPACTION_FAILED', 'INVALID_CONVERSATION_HISTORY',
        'STORAGE_UNAVAILABLE', 'PROCESSING_INTERRUPTED', 'SKILL_UNAVAILABLE'));

create table skill_command (
    message_job_id uuid primary key,
    session_id uuid not null,
    requested_name text not null,
    question text not null,
    disposition varchar(16) not null default 'PENDING' check (disposition in ('PENDING','ACCEPTED','REJECTED')),
    resolution_sequence bigint,
    foreign key (message_job_id, session_id) references message_job(message_job_id, session_id),
    foreign key (session_id, resolution_sequence) references session_message(session_id, sequence),
    check ((disposition = 'PENDING') = (resolution_sequence is null))
);

create table skill_message (
    session_id uuid not null,
    sequence bigint not null,
    role varchar(16) not null default 'SKILL' check (role = 'SKILL'),
    uri text not null check (length(uri) > 0),
    sha256 char(64) not null check (sha256 ~ '^[0-9a-f]{64}$'),
    content text not null,
    primary key (session_id, sequence),
    foreign key (session_id, sequence, role) references session_message(session_id, sequence, role)
);
create unique index uq_one_skill_per_job on session_message(message_job_id) where role = 'SKILL';
create trigger skill_message_append_only before update or delete on skill_message
    for each row execute function reject_committed_row_change();

create function restrict_skill_command_change() returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' then
        if new.disposition <> 'PENDING' then raise exception 'skill commands must start pending'; end if;
        return new;
    end if;
    if tg_op = 'DELETE' then raise exception 'skill commands must not be deleted'; end if;
    if new.message_job_id is distinct from old.message_job_id
        or new.session_id is distinct from old.session_id
        or new.requested_name is distinct from old.requested_name
        or new.question is distinct from old.question
        or old.disposition <> 'PENDING' or new.disposition = 'PENDING' then
        raise exception 'skill command permits only one resolution';
    end if;
    return new;
end;
$$;
create trigger skill_command_immutable before insert or update or delete on skill_command
    for each row execute function restrict_skill_command_change();

create or replace function require_message_detail() returns trigger language plpgsql as $$
declare detail_count integer;
begin
    select case new.role
        when 'USER' then (select count(*) from user_message where session_id = new.session_id and sequence = new.sequence)
        when 'TOOL' then (select count(*) from tool_observation where session_id = new.session_id and sequence = new.sequence)
        when 'ASSISTANT_TOOL_CALLS' then (select count(*) from assistant_tool_calls where session_id = new.session_id and sequence = new.sequence)
        when 'ASSISTANT' then (select count(*) from assistant_message where session_id = new.session_id and sequence = new.sequence)
        when 'RUNTIME' then (select count(*) from runtime_message where session_id = new.session_id and sequence = new.sequence)
        when 'SKILL' then (select count(*) from skill_message where session_id = new.session_id and sequence = new.sequence)
    end into detail_count;
    if detail_count is distinct from 1 then raise exception 'session message must have exactly one matching detail'; end if;
    return new;
end;
$$;
create constraint trigger skill_message_requires_detail after insert on skill_message
    deferrable initially deferred for each row execute function require_message_detail();

create function require_skill_resolution() returns trigger language plpgsql as $$
declare command skill_command; event session_message;
begin
    if tg_table_name = 'session_message' then
        if new.role <> 'SKILL' then return new; end if;
    end if;
    select * into command from skill_command where message_job_id = new.message_job_id;
    if command.message_job_id is null then raise exception 'skill event requires command'; end if;
    if tg_table_name = 'session_message' then
        if command.disposition <> 'ACCEPTED' or command.resolution_sequence is distinct from new.sequence then
            raise exception 'skill event must be the accepted resolution';
        end if;
    end if;
    if command.disposition = 'PENDING' then
        if exists(select 1 from session_message where message_job_id = command.message_job_id and role = 'SKILL') then
            raise exception 'pending command cannot have skill event';
        end if;
        return new;
    end if;
    select * into event from session_message
        where session_id = command.session_id and sequence = command.resolution_sequence;
    if event.message_job_id is distinct from command.message_job_id
        or (command.disposition = 'ACCEPTED' and event.role is distinct from 'SKILL')
        or (command.disposition = 'REJECTED' and (event.role is distinct from 'RUNTIME' or not exists(
            select 1 from runtime_message where session_id = command.session_id and sequence = command.resolution_sequence
                and code = 'SKILL_UNAVAILABLE' and message = 'Requested skill is unavailable.'))) then
        raise exception 'skill resolution must identify matching same-job event';
    end if;
    return new;
end;
$$;
create constraint trigger skill_command_requires_resolution after insert or update on skill_command
    deferrable initially deferred for each row execute function require_skill_resolution();
create constraint trigger skill_event_requires_resolution after insert on session_message
    deferrable initially deferred for each row execute function require_skill_resolution();
