alter table message_job
    add column cache_usage_known_calls integer,
    add column cache_usage_input_tokens bigint,
    add column cache_usage_cached_input_tokens bigint,
    add column cache_usage_last_ordinal integer;

-- Preserve historical jobs as untracked; defaults apply only to future inserts.
alter table message_job
    alter column cache_usage_known_calls set default 0,
    alter column cache_usage_input_tokens set default 0,
    alter column cache_usage_cached_input_tokens set default 0,
    alter column cache_usage_last_ordinal set default 0;

alter table message_job add constraint message_job_cache_usage_valid check (
    (cache_usage_known_calls is null and cache_usage_input_tokens is null
        and cache_usage_cached_input_tokens is null and cache_usage_last_ordinal is null)
    or
    (cache_usage_known_calls is not null and cache_usage_input_tokens is not null
        and cache_usage_cached_input_tokens is not null and cache_usage_last_ordinal is not null
        and cache_usage_known_calls >= 0
        and cache_usage_known_calls <= cache_usage_last_ordinal
        and cache_usage_last_ordinal <= model_calls
        and cache_usage_input_tokens >= 0
        and cache_usage_cached_input_tokens >= 0
        and cache_usage_cached_input_tokens <= cache_usage_input_tokens)
);
