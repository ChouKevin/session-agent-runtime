#!/usr/bin/env bash
set -euo pipefail

runtime_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
launcher="${runtime_root}/scripts/test-postgres-local.sh"
test_root="$(mktemp -d)"
trap 'rm -rf -- "${test_root}"' EXIT
mkdir -p "${test_root}/bin" "${test_root}/java/bin"

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

cat > "${test_root}/java/bin/java" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' 'openjdk version "21.0.1"' >&2
EOF
cat > "${test_root}/bin/mvn" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${SESSION_AGENT_TEST_POSTGRES_MODE}" == shared ]]
[[ "${SESSION_AGENT_TEST_POSTGRES_USERNAME}" == isolated_test_role ]]
[[ "${SESSION_AGENT_TEST_POSTGRES_PASSWORD}" == 'test value with spaces $ punctuation' ]]
[[ "$*" == '--batch-mode --no-transfer-progress -Ppostgres-it verify' ]]
[[ "${PWD}" == "${EXPECTED_RUNTIME_ROOT}" ]]
touch "${MAVEN_CALLED}"
exit "${MAVEN_EXIT_CODE:-0}"
EOF
chmod +x "${test_root}/java/bin/java" "${test_root}/bin/mvn"
export JAVA_HOME="${test_root}/java"
export PATH="${test_root}/bin:${PATH}"
export EXPECTED_RUNTIME_ROOT="${runtime_root}"
export MAVEN_CALLED="${test_root}/maven-called"
export SESSION_AGENT_TEST_POSTGRES_ENV_FILE="${test_root}/private.env"

if "${launcher}" >"${test_root}/output" 2>&1; then
    fail 'missing configuration must fail without Maven or Docker fallback'
fi
[[ ! -f "${MAVEN_CALLED}" ]] || fail 'missing config invoked Maven'

cat > "${SESSION_AGENT_TEST_POSTGRES_ENV_FILE}" <<'EOF'
SESSION_AGENT_TEST_POSTGRES_MODE=shared
SESSION_AGENT_TEST_POSTGRES_ADMIN_URL=jdbc:postgresql://127.0.0.1:5432/postgres
SESSION_AGENT_TEST_POSTGRES_USERNAME=isolated_test_role
SESSION_AGENT_TEST_POSTGRES_PASSWORD='test value with spaces $ punctuation'
EOF
chmod 0644 "${SESSION_AGENT_TEST_POSTGRES_ENV_FILE}"
if "${launcher}" >"${test_root}/output" 2>&1; then
    fail 'world-readable credentials must fail'
fi
[[ ! -f "${MAVEN_CALLED}" ]] || fail 'unsafe permissions invoked Maven'
chmod 0600 "${SESSION_AGENT_TEST_POSTGRES_ENV_FILE}"
(
    cd "${test_root}"
    "${launcher}" >"${test_root}/output" 2>&1
)
[[ -f "${MAVEN_CALLED}" ]] || fail 'valid owner-only configuration did not invoke Maven'
[[ ! -s "${test_root}/output" ]] || fail 'launcher emitted private configuration'

export MAVEN_EXIT_CODE=23
if "${launcher}" >"${test_root}/output" 2>&1; then
    fail 'launcher hid a Maven failure'
else
    [[ $? -eq 23 ]] || fail 'launcher changed the Maven exit status'
fi

cat > "${SESSION_AGENT_TEST_POSTGRES_ENV_FILE}" <<'EOF'
SESSION_AGENT_TEST_POSTGRES_MODE=shared
SESSION_AGENT_TEST_POSTGRES_ADMIN_URL=jdbc:postgresql://127.0.0.1:5432/postgres
SESSION_AGENT_TEST_POSTGRES_USERNAME=isolated_test_role
EOF
export SESSION_AGENT_TEST_POSTGRES_PASSWORD=inherited_credentials_must_not_fill_the_gap
if "${launcher}" >"${test_root}/output" 2>&1; then
    fail 'partial private configuration borrowed inherited credentials'
fi
printf '%s\n' 'Local PostgreSQL launcher behavior checks passed'
