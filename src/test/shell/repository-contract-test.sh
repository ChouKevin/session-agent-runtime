#!/usr/bin/env bash
set -euo pipefail

runtime_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"

[[ "$(git -C "${runtime_root}" rev-parse --show-toplevel)" == "${runtime_root}" ]]
[[ ! -d "${runtime_root}/session-agent-runtime" ]]

for required_file in pom.xml Dockerfile README.md live-test.sh .gitignore; do
    [[ -f "${runtime_root}/${required_file}" ]] || {
        printf 'missing standalone repository file: %s\n' "${required_file}" >&2
        exit 1
    }
done

for ignored_path in .env target/ build/ reports/; do
    git -C "${runtime_root}" check-ignore -q "${ignored_path}" || {
        printf 'generated or secret path is not ignored: %s\n' "${ignored_path}" >&2
        exit 1
    }
done

if git -C "${runtime_root}" ls-files \
    | rg -n '(^|/)(target|build|reports)(/|$)|(^|/)\.env$' >/dev/null; then
    printf 'generated output or local environment is tracked\n' >&2
    exit 1
fi

if rg -n --hidden \
    --glob '!docs/superpowers/**' \
    --glob '!src/test/shell/repository-contract-test.sh' \
    --glob '!src/test/shell/docker-contract-test.sh' \
    'java-agent-starter|\.runtime/sources|com\.java\.system\.agent' \
    "${runtime_root}/pom.xml" "${runtime_root}/Dockerfile" \
    "${runtime_root}/README.md" "${runtime_root}/src" \
    "${runtime_root}/docker" "${runtime_root}/fixtures" >/dev/null; then
    printf 'standalone repository still references its former parent or old agent\n' >&2
    exit 1
fi

workflow="${runtime_root}/.github/workflows/ci.yml"
for command in \
    'mvn -q test' \
    'mvn -q -Ppostgres-it verify' \
    'mvn -q -f fixtures/payment-service/pom.xml test' \
    'mvn -q -f fixtures/order-service/pom.xml test' \
    'bash src/test/shell/docker-contract-test.sh' \
    'docker build -t session-agent-runtime:ci .'; do
    grep -Fq "${command}" "${workflow}" || {
        printf 'CI is missing required command: %s\n' "${command}" >&2
        exit 1
    }
done
