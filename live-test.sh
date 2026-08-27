#!/usr/bin/env bash
set -euo pipefail

runtime_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${runtime_root}/docker/compose.yaml"
run_id="$(date -u +%Y%m%d%H%M%S)-$$"
project_name="session-agent-live-${run_id}"

if [[ ! "${project_name}" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
    printf 'invalid generated compose project name\n' >&2
    exit 1
fi

: "${SEMANTIC_BASE_URL:?SEMANTIC_BASE_URL must be set for live acceptance}"
: "${SEMANTIC_API_TOKEN:?SEMANTIC_API_TOKEN must be set for live acceptance}"
: "${GOOGLE_API_KEY:?GOOGLE_API_KEY must be set for live acceptance}"
: "${SESSION_AGENT_POSTGRES_PASSWORD:?SESSION_AGENT_POSTGRES_PASSWORD must be set for live acceptance}"

compose=(docker compose --project-name "${project_name}" -f "${compose_file}")

cleanup() {
    "${compose[@]}" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

export SESSION_AGENT_PORT=0
"${compose[@]}" up --build --wait --wait-timeout 180

published_port="$("${compose[@]}" port session-agent-runtime 8080)"
if [[ ! "${published_port}" =~ ^127\.0\.0\.1:[0-9]+$ ]]; then
    printf 'runtime was not published on an IPv4 loopback port\n' >&2
    exit 1
fi

export SESSION_AGENT_BASE_URL="http://${published_port}"
SESSION_AGENT_LIVE=true mvn -f "${runtime_root}/pom.xml" -q -Dtest=SessionAgentLiveIT test

diagnostic_count="$("${compose[@]}" exec -T postgres \
    psql -U session_agent -d session_agent -tAc \
    "select count(*) from model_call_record")"
jobs_with_gaps="$("${compose[@]}" exec -T postgres \
    psql -U session_agent -d session_agent -tAc \
    "select count(*) from message_job j
       where (select count(*) from model_call_record r
               where r.message_job_id = j.message_job_id) <> j.model_calls
          or (j.model_calls > 0 and
              (select min(r.runtime_call_ordinal) from model_call_record r
                where r.message_job_id = j.message_job_id) <> 1)
          or (j.model_calls > 0 and
              (select max(r.runtime_call_ordinal) from model_call_record r
                where r.message_job_id = j.message_job_id) <> j.model_calls)")"
unmatched_answer_ready="$("${compose[@]}" exec -T postgres \
    psql -U session_agent -d session_agent -tAc \
    "select count(*) from model_call_record p
       where p.phase = 'PLAN' and p.outcome = 'ANSWER_READY'
         and not exists (
             select 1 from model_call_record f
              where f.message_job_id = p.message_job_id
                and f.runtime_call_ordinal = p.runtime_call_ordinal + 1
                and f.phase = 'FINAL_REPLY')")"
completed_without_last_final="$("${compose[@]}" exec -T postgres \
    psql -U session_agent -d session_agent -tAc \
    "select count(*) from (
         select j.message_job_id
           from message_job j
           left join model_call_record r
             on r.message_job_id = j.message_job_id
            and r.phase = 'FINAL_REPLY'
            and r.outcome = 'FINAL_REPLY'
            and r.runtime_call_ordinal = j.model_calls
          where j.status = 'DONE'
          group by j.message_job_id, j.model_calls
         having count(r.diagnostic_id) <> 1
     ) invalid_done_job")"
invalid_provider_attempts="$("${compose[@]}" exec -T postgres \
    psql -U session_agent -d session_agent -tAc \
    "select count(*) from model_call_record where provider_attempt <> 1")"

[[ "${diagnostic_count}" =~ ^[1-9][0-9]*$ ]]
[[ "${jobs_with_gaps}" == "0" ]]
[[ "${unmatched_answer_ready}" == "0" ]]
[[ "${completed_without_last_final}" == "0" ]]
[[ "${invalid_provider_attempts}" == "0" ]]
printf 'MODEL_CALL_DIAGNOSTICS total=%s gaps=%s unmatchedAnswerReady=%s invalidDone=%s invalidAttempts=%s\n' \
    "${diagnostic_count}" "${jobs_with_gaps}" "${unmatched_answer_ready}" \
    "${completed_without_last_final}" "${invalid_provider_attempts}"
