#!/usr/bin/env bash
set -euo pipefail
# Never enable tracing: this file loads private test credentials.
set +x

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
runtime_root="$(cd "${script_directory}/.." && pwd)"
config_file="${SESSION_AGENT_TEST_POSTGRES_ENV_FILE:-${HOME}/.local/share/local-postgres/session-agent-tests.env}"

fail() {
    printf 'local PostgreSQL tests: %s\n' "$1" >&2
    exit 1
}

[[ $# -eq 0 ]] || fail 'set SESSION_AGENT_TEST_POSTGRES_ENV_FILE to override the private configuration file; no positional arguments are accepted'
[[ -f "${config_file}" && -r "${config_file}" && ! -L "${config_file}" ]] \
    || fail 'missing private test configuration; provision it or set SESSION_AGENT_TEST_POSTGRES_ENV_FILE (no container fallback)'
[[ "$(stat -c '%u' -- "${config_file}")" == "${UID}" \
    && "$(stat -c '%a' -- "${config_file}")" == 600 ]] \
    || fail 'private test configuration must be owned by the current user with mode 600'

# The explicitly trusted, owner-only file contains shell-safe assignments.
# Clear inherited credentials so an incomplete file cannot borrow runtime credentials.
unset SESSION_AGENT_TEST_POSTGRES_MODE SESSION_AGENT_TEST_POSTGRES_ADMIN_URL \
    SESSION_AGENT_TEST_POSTGRES_USERNAME SESSION_AGENT_TEST_POSTGRES_PASSWORD
source "${config_file}"
[[ "${SESSION_AGENT_TEST_POSTGRES_MODE:-}" == shared ]] || fail 'private test configuration must explicitly select shared mode'
[[ "${SESSION_AGENT_TEST_POSTGRES_ADMIN_URL:-}" == jdbc:postgresql://127.0.0.1:5432/postgres \
    || "${SESSION_AGENT_TEST_POSTGRES_ADMIN_URL:-}" == jdbc:postgresql://localhost:5432/postgres ]] \
    || fail 'test admin URL must address loopback port 5432 database postgres without parameters'
[[ -n "${SESSION_AGENT_TEST_POSTGRES_USERNAME:-}" && -n "${SESSION_AGENT_TEST_POSTGRES_PASSWORD:-}" ]] \
    || fail 'private test configuration must contain dedicated test-role credentials'
export SESSION_AGENT_TEST_POSTGRES_MODE SESSION_AGENT_TEST_POSTGRES_ADMIN_URL \
    SESSION_AGENT_TEST_POSTGRES_USERNAME SESSION_AGENT_TEST_POSTGRES_PASSWORD

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
[[ -x "${JAVA_HOME}/bin/java" ]] || fail 'Java 21 is required; set JAVA_HOME to a Java 21 installation'
java_version="$("${JAVA_HOME}/bin/java" -version 2>&1)"
[[ "${java_version}" =~ version\ \"21[.\"] ]] || fail 'JAVA_HOME must select Java 21'
export PATH="${JAVA_HOME}/bin:${PATH}"
command -v mvn >/dev/null 2>&1 || fail 'Maven is required on PATH'
cd "${runtime_root}"
exec mvn --batch-mode --no-transfer-progress -Ppostgres-it verify
