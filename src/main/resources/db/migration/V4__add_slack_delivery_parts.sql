create table slack_delivery_part (
    delivery_id uuid not null references slack_delivery(delivery_id),
    part_index integer not null check (part_index >= 0),
    part_text text not null check (length(part_text) > 0),
    status varchar(16) not null check (status in ('PENDING', 'SENT', 'FAILED')),
    slack_message_ts varchar(64),
    sent_at timestamptz,
    primary key (delivery_id, part_index),
    check ((status = 'SENT') = (slack_message_ts is not null and sent_at is not null)),
    check (status <> 'FAILED' or slack_message_ts is null or length(slack_message_ts) > 0)
);

create index ix_slack_delivery_part_pending on slack_delivery_part(delivery_id, part_index)
    where status = 'PENDING';
