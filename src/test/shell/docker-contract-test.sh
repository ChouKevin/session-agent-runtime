#!/usr/bin/env bash
set -euo pipefail

runtime_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
compose_file="${runtime_root}/docker/compose.yaml"
dockerfile="${runtime_root}/Dockerfile"
env_example="${runtime_root}/docker/.env.example"

for required_file in "${compose_file}" "${dockerfile}" "${env_example}"; do
    if [[ ! -f "${required_file}" ]]; then
        printf 'missing required Docker contract file: %s\n' "${required_file}" >&2
        exit 1
    fi
done

for required_command in curl docker jq; do
    if ! command -v "${required_command}" >/dev/null 2>&1; then
        printf 'missing required command: %s\n' "${required_command}" >&2
        exit 1
    fi
done

SEMANTIC_API_TOKEN='contract-semantic-token' SESSION_AGENT_POSTGRES_PASSWORD='contract-only-password' docker compose -f "${compose_file}" config --quiet
compose_json="$(SEMANTIC_API_TOKEN='contract-semantic-token' SESSION_AGENT_POSTGRES_PASSWORD='contract-only-password' docker compose -f "${compose_file}" config --format json)"
service_names="$(jq -r '.services | keys | sort | join(",")' <<<"${compose_json}")"
[[ "${service_names}" == "postgres,session-agent-runtime" ]] || {
    printf 'unexpected compose services: %s\n' "${service_names}" >&2
    exit 1
}

jq -e '
    .services.postgres.environment.POSTGRES_DB == "session_agent"
    and .services.postgres.environment.POSTGRES_USER == "session_agent"
    and .services.postgres.environment.POSTGRES_PASSWORD == "contract-only-password"
    and .services["session-agent-runtime"].environment.SERVER_ADDRESS == "0.0.0.0"
    and .services["session-agent-runtime"].ports == [{"mode": "ingress", "host_ip": "127.0.0.1", "target": 8080, "published": "8090", "protocol": "tcp"}]
    and .services.postgres.logging.driver == "json-file"
    and .services["session-agent-runtime"].logging.driver == "json-file"
    and .services.postgres.logging.options["max-size"] == "100m"
    and .services.postgres.logging.options["max-file"] == "5"
    and .services["session-agent-runtime"].logging.options["max-size"] == "100m"
    and .services["session-agent-runtime"].logging.options["max-file"] == "5"
    and .services["session-agent-runtime"].depends_on.postgres.condition == "service_healthy"
    and (.services.postgres.healthcheck.test | length > 0)
    and (.volumes | keys | length == 1)
    and ([.services[] | .volumes[]? | .source] | all(test("(^|:)logs(/|:|$)"; "i") | not))
' <<<"${compose_json}" >/dev/null || {
    printf 'compose isolation, logging, health, or volume contract failed\n' >&2
    exit 1
}

if rg -n '^\s*name:' "${compose_file}" >/dev/null; then
    printf 'compose volume must remain project-scoped without a global name\n' >&2
    exit 1
fi

grep -Fq '127.0.0.1:${SESSION_AGENT_PORT:-8090}:8080' "${compose_file}" || {
    printf 'runtime port is not localhost-bound with the required dynamic default\n' >&2
    exit 1
}

if rg -n 'SESSION_AGENT_POSTGRES_PASSWORD:-|POSTGRES_PASSWORD:.*session_agent' "${compose_file}" >/dev/null; then
    printf 'compose password must not have a committed fallback\n' >&2
    exit 1
fi
if rg -n 'SEMANTIC_API_TOKEN:-' "${compose_file}" >/dev/null; then
    printf 'compose Semantic token must not have a committed fallback\n' >&2
    exit 1
fi

google_live_test="${runtime_root}/src/test/java/com/java/system/sessionagent/model/GoogleModelLiveTest.java"
[[ -f "${google_live_test}" ]] || { printf 'missing GoogleModelLiveTest\n' >&2; exit 1; }
grep -Fq 'GOOGLE_MODEL_LIVE' "${google_live_test}" || {
    printf 'Google model smoke test must remain explicitly opt-in\n' >&2
    exit 1
}
if [[ -e "${runtime_root}/src/test/java/com/java/system/sessionagent/model/GoogleNoToolLiveTest.java" ]]; then
    printf 'obsolete GoogleNoToolLiveTest must not remain\n' >&2
    exit 1
fi

for required_input in SEMANTIC_BASE_URL SEMANTIC_API_TOKEN GOOGLE_API_KEY GOOGLE_GENAI_MODEL; do
    grep -Fq "${required_input}" "${compose_file}" || {
        printf 'missing environment input: %s\n' "${required_input}" >&2
        exit 1
    }
done

for reserved_input in SLACK_APP_TOKEN SLACK_BOT_TOKEN SLACK_BOT_USER_ID; do
    grep -Fq "${reserved_input}: \${${reserved_input}:-}" "${compose_file}" || {
        printf 'missing reserved environment input: %s\n' "${reserved_input}" >&2
        exit 1
    }
    grep -Fxq "${reserved_input}=" "${env_example}" || {
        printf 'environment example must keep %s empty\n' "${reserved_input}" >&2
        exit 1
    }
done

grep -Fxq 'SESSION_AGENT_POSTGRES_PASSWORD=' "${env_example}" || {
    printf 'environment example must not provide a database password\n' >&2
    exit 1
}
if rg -n 'replace-with-a-local-non-secret-password' "${env_example}" >/dev/null; then
    printf 'environment example contains a predictable database password\n' >&2
    exit 1
fi

if rg -n --fixed-strings 'logs/' "${compose_file}" "${dockerfile}" >/dev/null; then
    printf 'host log reference is forbidden\n' >&2
    exit 1
fi

grep -Fq 'FROM maven:' "${dockerfile}" || { printf 'Dockerfile lacks Maven build stage\n' >&2; exit 1; }
grep -Fq 'eclipse-temurin:21' "${dockerfile}" || { printf 'Dockerfile lacks Temurin 21 runtime\n' >&2; exit 1; }
grep -Fq 'session-agent-runtime' "${dockerfile}" || { printf 'Dockerfile does not build the standalone runtime\n' >&2; exit 1; }
