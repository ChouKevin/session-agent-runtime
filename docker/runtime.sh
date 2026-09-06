#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
runtime_root="$(cd "${script_directory}/.." && pwd)"
compose_file="${script_directory}/compose.yaml"
env_example="${script_directory}/.env.example"
env_file="${script_directory}/.env"
runtime_uid=10001
runtime_gid=10001

usage() {
    printf '%s\n' \
        "Usage: $0 init|start|stop|restart|status|logs" \
        "" \
        "  init     Create docker/.env when absent and prepare the host log directory" \
        "  start    Validate configuration, build, and start the complete project" \
        "  stop     Stop the complete project without deleting PostgreSQL data" \
        "  restart  Stop, rebuild, and start the complete project" \
        "  status   Show Compose service status" \
        "  logs     Follow session-agent-runtime container logs"
}

fail() {
    printf 'deployment helper: %s\n' "$1" >&2
    exit 1
}

require_file() {
    [[ -f "$1" ]] || fail "missing required file: $1"
}

configured_log_directory() {
    local configured_directory="${SESSION_AGENT_LOG_DIR:-}"
    local line
    if [[ -z "${configured_directory}" && -f "${env_file}" ]]; then
        while IFS= read -r line || [[ -n "${line}" ]]; do
            if [[ "${line}" == SESSION_AGENT_LOG_DIR=* ]]; then
                configured_directory="${line#SESSION_AGENT_LOG_DIR=}"
            fi
        done < "${env_file}"
    fi
    configured_directory="${configured_directory%$'\r'}"
    if [[ -z "${configured_directory}" ]]; then
        configured_directory="${runtime_root}/logs"
    fi
    [[ "${configured_directory}" == /* ]] \
        || fail "SESSION_AGENT_LOG_DIR must be empty or an absolute path"
    [[ "${configured_directory}" != *$'\n'* ]] \
        || fail "SESSION_AGENT_LOG_DIR must not contain a newline"

    local normalized_directory
    normalized_directory="$(realpath -m -- "${configured_directory}")"
    local parent_directory
    parent_directory="$(dirname -- "${normalized_directory}")"
    if [[ "${normalized_directory}" == "/" || "${parent_directory}" == "/" \
            || "${normalized_directory}" == "${runtime_root}" \
            || "${normalized_directory}" == "${script_directory}" ]]; then
        fail "refusing unsafe host log directory: ${normalized_directory}"
    fi
    printf '%s\n' "${normalized_directory}"
}

create_environment_if_absent() {
    local log_directory="$1"
    if [[ -f "${env_file}" ]]; then
        printf 'Preserving existing deployment configuration: %s\n' "${env_file}"
        return
    fi

    require_file "${env_example}"
    local temporary_file
    temporary_file="$(mktemp "${script_directory}/.env.tmp.XXXXXX")"
    local line
    while IFS= read -r line || [[ -n "${line}" ]]; do
        if [[ "${line}" == SESSION_AGENT_LOG_DIR=* ]]; then
            printf 'SESSION_AGENT_LOG_DIR=%s\n' "${log_directory}"
        else
            printf '%s\n' "${line}"
        fi
    done < "${env_example}" > "${temporary_file}"
    chmod 0600 "${temporary_file}"
    mv -- "${temporary_file}" "${env_file}"
    printf 'Created deployment configuration: %s\n' "${env_file}"
}

prepare_log_directory() {
    local log_directory="$1"
    local install_arguments=(-d -o "${runtime_uid}" -g "${runtime_gid}" -m 0755 -- "${log_directory}")
    if [[ "$(id -u)" -eq 0 ]]; then
        install "${install_arguments[@]}"
    elif command -v sudo >/dev/null 2>&1; then
        sudo install "${install_arguments[@]}"
    else
        fail "root privileges are required to prepare ${log_directory}; install sudo or run init as root"
    fi
    printf 'Prepared host log directory for %s:%s: %s\n' "${runtime_uid}" "${runtime_gid}" "${log_directory}"
}

require_deployment() {
    require_file "${compose_file}"
    require_file "${env_file}"
    command -v docker >/dev/null 2>&1 || fail "docker is required"
}

compose() {
    docker compose --env-file "${env_file}" -f "${compose_file}" "$@"
}

start_project() {
    require_deployment
    prepare_log_directory "$(configured_log_directory)"
    compose config --quiet
    compose up -d --build
}

command_name="${1:-}"
case "${command_name}" in
    init)
        log_directory="$(configured_log_directory)"
        create_environment_if_absent "${log_directory}"
        prepare_log_directory "${log_directory}"
        printf 'Edit %s, then run: %s start\n' "${env_file}" "$0"
        ;;
    start)
        start_project
        ;;
    stop)
        require_deployment
        compose down
        ;;
    restart)
        require_deployment
        prepare_log_directory "$(configured_log_directory)"
        compose config --quiet
        compose down
        compose up -d --build
        ;;
    status)
        require_deployment
        compose ps
        ;;
    logs)
        require_deployment
        compose logs -f session-agent-runtime
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac
