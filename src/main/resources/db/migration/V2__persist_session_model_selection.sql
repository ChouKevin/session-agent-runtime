alter table conversation_session add column selected_model varchar(64) not null;

alter table message_job
    add column model_key varchar(64) not null,
    add column model_fingerprint varchar(80) not null;

alter table context_usage_checkpoint
    add column model_key varchar(64) not null,
    add column model_fingerprint varchar(80) not null;

create or replace function restrict_conversation_session_change() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then raise exception 'conversation sessions must not be deleted'; end if;
    if new.session_id is distinct from old.session_id
        or new.source_type is distinct from old.source_type
        or new.session_key is distinct from old.session_key
        or new.created_at is distinct from old.created_at
        or (new.next_sequence is distinct from old.next_sequence
            and new.next_sequence is distinct from old.next_sequence + 1) then
        raise exception 'conversation session identity is immutable and next sequence must increase';
    end if;
    return new;
end;
$$;

create or replace function reject_message_job_identity_change() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then raise exception 'message jobs must not be deleted'; end if;
    if new.message_job_id is distinct from old.message_job_id
        or new.session_id is distinct from old.session_id
        or new.user_message_sequence is distinct from old.user_message_sequence
        or new.created_at is distinct from old.created_at
        or new.model_key is distinct from old.model_key
        or new.model_fingerprint is distinct from old.model_fingerprint then
        raise exception 'message job identity is immutable';
    end if;
    return new;
end;
$$;
