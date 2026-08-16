#!/usr/bin/env bash
set -euo pipefail

"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/src/test/shell/docker-contract-test.sh"
