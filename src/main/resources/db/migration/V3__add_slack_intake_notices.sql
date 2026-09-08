alter table slack_event_receipt drop constraint slack_event_receipt_classification_check;
alter table slack_event_receipt add constraint slack_event_receipt_classification_check check (classification in (
    'ACCEPTED', 'BUSY', 'UNBOUND_THREAD', 'BLANK', 'UNSUPPORTED_CONTENT', 'EDIT_OR_DELETE', 'HIDDEN'));

alter table slack_message_receipt drop constraint slack_message_receipt_classification_check;
alter table slack_message_receipt add constraint slack_message_receipt_classification_check check (classification in (
    'ACCEPTED', 'BUSY', 'UNBOUND_THREAD', 'BLANK', 'UNSUPPORTED_CONTENT', 'EDIT_OR_DELETE', 'HIDDEN'));

create or replace function preserve_slack_message_receipt() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' then
        if old.team_id = new.team_id and old.channel_id = new.channel_id and old.message_ts = new.message_ts
            and old.created_at = new.created_at and old.top_level and new.top_level
            and old.classification <> 'ACCEPTED' and old.classification <> 'BUSY'
            and new.classification in ('ACCEPTED', 'BUSY') and old.session_id is null and old.message_job_id is null
            and ((new.classification = 'ACCEPTED' and new.session_id is not null and new.message_job_id is not null)
                or (new.classification = 'BUSY' and new.session_id is null and new.message_job_id is null)) then
            return new;
        end if;
    end if;
    raise exception 'committed conversation rows are append-only';
end;
$$;

alter table slack_delivery add column kind varchar(16) not null default 'ANSWER';
alter table slack_delivery add column team_id varchar(128);
alter table slack_delivery add column message_ts varchar(64);
alter table slack_delivery alter column team_id set not null;
alter table slack_delivery alter column message_ts set not null;
alter table slack_delivery alter column terminal_session_id drop not null;
alter table slack_delivery alter column terminal_sequence drop not null;
alter table slack_delivery alter column message_job_id drop not null;
alter table slack_delivery add constraint slack_delivery_kind_check check (kind in ('QUEUED', 'BUSY', 'ANSWER'));
alter table slack_delivery add constraint slack_delivery_source_check check (
    team_id ~ '[^[:space:]]' and channel_id ~ '[^[:space:]]' and message_ts ~ '[^[:space:]]');
alter table slack_delivery add constraint fk_slack_delivery_source
    foreign key (team_id, channel_id, message_ts) references slack_message_receipt(team_id, channel_id, message_ts);
alter table slack_message_receipt add constraint uq_slack_message_receipt_source_job
    unique (team_id, channel_id, message_ts, message_job_id);
alter table slack_delivery add constraint fk_slack_delivery_source_job
    foreign key (team_id, channel_id, message_ts, message_job_id)
    references slack_message_receipt(team_id, channel_id, message_ts, message_job_id);
alter table slack_delivery add constraint fk_slack_delivery_job
    foreign key (message_job_id) references message_job(message_job_id);
alter table slack_delivery add constraint slack_delivery_kind_identity_check check (
    (kind = 'ANSWER' and terminal_session_id is not null and terminal_sequence is not null and message_job_id is not null)
    or (kind = 'QUEUED' and terminal_session_id is null and terminal_sequence is null and message_job_id is not null)
    or (kind = 'BUSY' and terminal_session_id is null and terminal_sequence is null and message_job_id is null));
create unique index uq_slack_delivery_notice_source on slack_delivery(team_id, channel_id, message_ts, kind);
