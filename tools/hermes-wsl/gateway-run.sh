#!/usr/bin/env bash
# Run Hermes Gateway inside WSL (Ubuntu). Default: API Server on 8642, GET /health
# Usage (from Windows): wsl -d Ubuntu -u root bash /mnt/d/u3wv2/tools/hermes-wsl/gateway-run.sh
# Or: wsl -d Ubuntu -u root bash D:/u3wv2/tools/hermes-wsl/gateway-run.sh  (Git Bash style path may differ)
set -euo pipefail
export API_SERVER_ENABLED="${API_SERVER_ENABLED:-true}"
HERMES_ROOT="${HERMES_ROOT:-/mnt/d/u3wv2/.dev/hermes-agent}"
VENV="${HERMES_VENV:-/root/hermes-venv}"
# shellcheck disable=SC1090
source "${VENV}/bin/activate"
cd "${HERMES_ROOT}"
exec hermes gateway run
