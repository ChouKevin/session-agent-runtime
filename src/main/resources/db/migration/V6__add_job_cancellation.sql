alter table message_job
    add column cancellation_requested_at timestamptz;

alter table message_job
    drop constraint message_job_status_check,
    drop constraint message_job_check1,
    drop constraint message_job_check2,
    add constraint message_job_status_check check (status in ('PENDING', 'WORKING', 'RETRY', 'DONE', 'CANCELLED')),
    add constraint message_job_terminal_completed check ((status in ('DONE', 'CANCELLED')) = (completed_at is not null)),
    add constraint message_job_terminal_unowned check (status not in ('DONE', 'CANCELLED') or (worker_id is null and locked_until is null)),
    add constraint message_job_cancelled_requested check (status <> 'CANCELLED' or cancellation_requested_at is not null),
    add constraint message_job_cancelled_not_failure check (status <> 'CANCELLED' or failure_code is null);
