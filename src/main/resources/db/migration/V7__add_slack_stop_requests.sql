alter table slack_event_receipt drop constraint slack_event_receipt_classification_check;
alter table slack_event_receipt add constraint slack_event_receipt_classification_check check (classification in (
    'ACCEPTED', 'BUSY', 'STOP_REQUEST', 'UNBOUND_THREAD', 'BLANK', 'UNSUPPORTED_CONTENT', 'EDIT_OR_DELETE', 'HIDDEN'));

alter table slack_message_receipt drop constraint slack_message_receipt_classification_check;
alter table slack_message_receipt add constraint slack_message_receipt_classification_check check (classification in (
    'ACCEPTED', 'BUSY', 'STOP_REQUEST', 'UNBOUND_THREAD', 'BLANK', 'UNSUPPORTED_CONTENT', 'EDIT_OR_DELETE', 'HIDDEN'));

create or replace function preserve_slack_message_receipt() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' then
        if old.team_id = new.team_id and old.channel_id = new.channel_id and old.message_ts = new.message_ts
            and old.created_at = new.created_at and old.top_level and new.top_level
            and old.classification not in ('ACCEPTED', 'BUSY', 'STOP_REQUEST')
            and new.classification in ('ACCEPTED', 'BUSY') and old.session_id is null and old.message_job_id is null
            and ((new.classification = 'ACCEPTED' and new.session_id is not null and new.message_job_id is not null)
                or (new.classification = 'BUSY' and new.session_id is null and new.message_job_id is null)) then
            return new;
        end if;
    end if;
    raise exception 'committed conversation rows are append-only';
end;
$$;

alter table slack_delivery drop constraint slack_delivery_kind_check;
alter table slack_delivery add constraint slack_delivery_kind_check check (kind in ('QUEUED', 'BUSY', 'STOP', 'ANSWER'));
alter table slack_delivery drop constraint slack_delivery_kind_identity_check;
alter table slack_delivery add constraint slack_delivery_kind_identity_check check (
    (kind = 'ANSWER' and terminal_session_id is not null and terminal_sequence is not null and message_job_id is not null)
    or (kind = 'QUEUED' and terminal_session_id is null and terminal_sequence is null and message_job_id is not null)
    or (kind in ('BUSY', 'STOP') and terminal_session_id is null and terminal_sequence is null and message_job_id is null));
drop index uq_slack_delivery_notice_source;
create unique index uq_slack_delivery_notice_source on slack_delivery(team_id, channel_id, message_ts)
    where kind in ('QUEUED', 'BUSY', 'STOP');
