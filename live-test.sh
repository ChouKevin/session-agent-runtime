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
