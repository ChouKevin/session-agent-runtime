#!/usr/bin/env bash
set -euo pipefail

runtime_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
runtime_script="${runtime_root}/docker/runtime.sh"

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

[[ -x "${runtime_script}" ]] || fail "deployment helper must exist and be executable: ${runtime_script}"

test_root="$(mktemp -d)"
trap 'rm -rf -- "${test_root}"' EXIT
mkdir -p "${test_root}/docker" "${test_root}/bin" "${test_root}/caller"
cp -- "${runtime_script}" "${runtime_root}/docker/compose.yaml" "${runtime_root}/docker/.env.example" "${test_root}/docker/"

cat > "${test_root}/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_DOCKER_CALLS}"
EOF
cat > "${test_root}/bin/install" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_INSTALL_CALLS}"
target="${!#}"
/bin/mkdir -p -- "${target}"
EOF
cat > "${test_root}/bin/sudo" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
"$@"
EOF
chmod +x "${test_root}/bin/docker" "${test_root}/bin/install" "${test_root}/bin/sudo"

export PATH="${test_root}/bin:${PATH}"
export FAKE_DOCKER_CALLS="${test_root}/docker-calls.log"
export FAKE_INSTALL_CALLS="${test_root}/install-calls.log"
touch "${FAKE_DOCKER_CALLS}" "${FAKE_INSTALL_CALLS}"

log_directory="${test_root}/host-logs"
other_log_directory="${test_root}/must-not-replace-config"
(
    cd "${test_root}/caller"
    SESSION_AGENT_LOG_DIR="${log_directory}" "${test_root}/docker/runtime.sh" init
)

env_file="${test_root}/docker/.env"
[[ -f "${env_file}" ]] || fail "init must create the local deployment environment file"
grep -Fxq "SESSION_AGENT_LOG_DIR=${log_directory}" "${env_file}" \
    || fail "init must persist the selected absolute log directory in a new environment file"
[[ -d "${log_directory}" ]] || fail "init must prepare the selected host log directory"
grep -Fxq -- "-d -o 10001 -g 10001 -m 0755 -- ${log_directory}" "${FAKE_INSTALL_CALLS}" \
    || fail "init must prepare the log directory for UID/GID 10001"

printf '%s\n' 'LOCAL_SENTINEL=preserve-me' >> "${env_file}"
SESSION_AGENT_LOG_DIR="${other_log_directory}" "${test_root}/docker/runtime.sh" init
grep -Fxq 'LOCAL_SENTINEL=preserve-me' "${env_file}" || fail "init must not overwrite existing local configuration"
grep -Fxq "SESSION_AGENT_LOG_DIR=${log_directory}" "${env_file}" || fail "init must preserve the configured log directory"

(
    cd "${test_root}/caller"
    "${test_root}/docker/runtime.sh" start
    "${test_root}/docker/runtime.sh" status
    "${test_root}/docker/runtime.sh" logs
    "${test_root}/docker/runtime.sh" restart
    "${test_root}/docker/runtime.sh" stop
)

compose_prefix="compose --env-file ${env_file} -f ${test_root}/docker/compose.yaml"
grep -Fxq "${compose_prefix} config --quiet" "${FAKE_DOCKER_CALLS}" || fail "start must validate the Compose configuration"
[[ "$(grep -Fxc "${compose_prefix} up -d --build" "${FAKE_DOCKER_CALLS}")" -eq 2 ]] \
    || fail "start and restart must launch the complete project detached with a build"
[[ "$(grep -Fxc "${compose_prefix} down" "${FAKE_DOCKER_CALLS}")" -eq 2 ]] \
    || fail "restart and stop must bring down the complete project"
grep -Fxq "${compose_prefix} ps" "${FAKE_DOCKER_CALLS}" || fail "status must inspect the complete project"
grep -Fxq "${compose_prefix} logs -f session-agent-runtime" "${FAKE_DOCKER_CALLS}" \
    || fail "logs must follow Runtime output"
if grep -Eq '(^| )(-v|--volumes)( |$)' "${FAKE_DOCKER_CALLS}"; then
    fail "deployment helper must never remove the PostgreSQL volume"
fi
